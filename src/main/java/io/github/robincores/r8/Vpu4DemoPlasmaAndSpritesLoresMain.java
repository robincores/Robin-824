package io.github.robincores.r8;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v4 demo (R816-friendly, v4-optimized):
 * <ul>
 *   <li>Underlay is an 8-bit index field encoded as 8 x 1bpp bitplanes (planes 0..7) in LORES group0.</li>
 *   <li>Plasma animates via PALETTE CYCLING only (VRAM indices stay static).</li>
 *   <li>Ball "rotates" by rewriting only its 64 tiles/frame into sprite bank (VBANK=8).</li>
 *   <li>Shadow is stable Bayer dither (no shimmer), updated only in a union bbox region.</li>
 *   <li>No copper.</li>
 *   <li>128 sprites total: 64 ball tiles + 64 sparkles.</li>
 * </ul>
 *
 * <p>v4 specifics:</p>
 * <ul>
 *   <li>Playfield uses the v4 GROUP descriptor table (MMIO 0x0400..).</li>
 *   <li>Sprites use OAM in VBANK=9 (tables bank), stride=32 bytes.</li>
 *   <li>Sprite X is in OUTPUT pixels; sprite Y is in SOURCE lines (0..199). Output is 640x400.</li>
 *   <li>To emulate "LORES coords" (320 wide) for sprite placement, we store X = xSrc*2 and set XSTEP = 0x0080 (2x wide).</li>
 * </ul>
 */
public final class Vpu4DemoPlasmaAndSpritesLoresMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- v4 MMIO ----
    private static final int REG_CTRL     = 0x0000;
    private static final int REG_TX_CTRL  = 0x0006;  // text off for this demo
    private static final int REG_SPR_CTRL = 0x0014;  // bit0 sprites enable

    private static final int PAL_BASE     = 0x0100;

    // Group table
    private static final int GROUP_BASE   = 0x0400;
    private static final int GROUP_STRIDE = 0x0010;

    // CTRL bits (v4)
    private static final int CTRL_ENABLE  = 0x01;

    // Sprite ctrl bits
    private static final int SPR_EN       = 0x01;

    // Group flags (match VPU_v4)
    private static final int GF_LORES     = 0x01;
    private static final int GF_WRAP_X    = 0x02;
    private static final int GF_WRAP_Y    = 0x04;
    private static final int GF_OPAQUE0   = 0x08;

    // Banked memory
    private static final int VB_SPR = 8;  // sprite pattern bank (16K)
    private static final int VB_TBL = 9;  // OAM + copper tables bank (16K)

    // OAM format (v4)
    private static final int SPR_COUNT  = 128;
    private static final int SPR_STRIDE = 32;

    // OAM field offsets (within one 32-byte sprite record)
    private static final int O_X_L      = 0;
    private static final int O_X_H      = 1;
    private static final int O_Y_L      = 2;
    private static final int O_Y_H      = 3;
    private static final int O_W        = 4;
    private static final int O_H        = 5;
    private static final int O_DATA_L   = 6;
    private static final int O_DATA_H   = 7;
    private static final int O_ATTR     = 8;
    private static final int O_PAL      = 9;
    private static final int O_COL_ID   = 10;
    private static final int O_PRIORITY = 11;
    private static final int O_XSTEP_L  = 12;
    private static final int O_XSTEP_H  = 13;
    private static final int O_YSTEP_L  = 14;
    private static final int O_YSTEP_H  = 15;
    private static final int O_TILT_DX  = 16;
    private static final int O_LINK     = 17;

    // ATTR bits (match VPU_v4)
    private static final int SA_EN      = 0x01;
    private static final int SA_HFLIP   = 0x02;
    private static final int SA_VFLIP   = 0x04;
    private static final int SA_CHAIN   = 0x08;
    private static final int SA_COLLIDE = 0x10;
    private static final int SA_TILT_EN = 0x80;

    // 4bpp 8x8 tiles
    private static final int TILE_BYTES = 32;

    // LORES source geometry (320x200 -> output 640x400)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BYTES_PER_ROW = SRC_W / 8; // 40 bytes per plane per scanline

    // Ball: 64x64 source pixels => 8x8 tiles => 64 sprites
    private static final int BALL_SZ      = 64;
    private static final int BALL_TILES_X = BALL_SZ / 8; // 8
    private static final int BALL_TILES_Y = BALL_SZ / 8; // 8
    private static final int BALL_SPRITES = BALL_TILES_X * BALL_TILES_Y; // 64

    // Sparkles: 64 sprites
    private static final int SPARK_SPRITES = 64;
    private static final int TOTAL_SPRITES = 128;

    // Tile indices inside sprite bank
    private static final int TILE_BALL_BASE = 0;     // 0..63
    private static final int TILE_SPARKLE   = 64;    // one shared tile

    // Plasma palette layout:
    //  32..143  : 112 colors (normal)
    //  144..255 : 112 colors (shadow)
    private static final int PLASMA_PAL_BASE    = 32;
    private static final int PLASMA_COLORS      = 112;
    private static final int PLASMA_PAL_SHADOW  = PLASMA_PAL_BASE + PLASMA_COLORS; // 144

    // Stable Bayer 8x8
    private static final int[] BAYER8 = {
            0, 32,  8, 40,  2, 34, 10, 42,
            48,16, 56, 24, 50, 18, 58, 26,
            12,44,  4, 36, 14, 46,  6, 38,
            60,28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43,  1, 33,  9, 41,
            51,19, 59, 27, 49, 17, 57, 25,
            15,47,  7, 39, 13, 45,  5, 37,
            63,31, 55, 23, 61, 29, 53, 21
    };

    // Precomputed sine and distance tables
    private static final int[] SIN256 = new int[256];           // -127..127
    private static final int[] DIST   = new int[SRC_W * SRC_H]; // scaled dist
    static {
        for (int i = 0; i < 256; i++) {
            SIN256[i] = (int) Math.round(Math.sin(i * (2.0 * Math.PI / 256.0)) * 127.0);
        }
        int cx = SRC_W / 2;
        int cy = SRC_H / 2;
        for (int y = 0; y < SRC_H; y++) {
            for (int x = 0; x < SRC_W; x++) {
                int dx = x - cx;
                int dy = y - cy;
                double d = Math.sqrt(dx * dx + dy * dy);
                DIST[y * SRC_W + x] = (int) Math.round(d * 2.4);
            }
        }
    }

    // v4 sprite scaling: emulate v3 LORES horizontal doubling (2x)
    private static final int XSTEP_2X = 0x0080; // 0.5 src px / out px => 2x magnification
    private static final int YSTEP_1X = 0x0100;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);
        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU v4 Demo: Plasma + Rotating Ball + 128 Sprites — FPS --");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu4-plasma-sprites-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v4 vpu, int cyclesPerFrame) {
        // Core on, text off, sprites on
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00);
        vpu.writeMmio(REG_SPR_CTRL, (byte) SPR_EN);

        // Group0: planes 0..7 => 8bpp, LORES
        writeGroup0_8bppLores(vpu);

        installFixedPalettes(vpu);

        // Plasma values (0..111) computed once
        final byte[] plasmaVal = buildStaticPlasmaValues();

        // Fill underlay once into planes 0..7 (8bpp via 8 planes)
        fillLores8bppPlanar(vpu, plasmaVal);

        // Sparkle tile once (VBANK=8)
        writeSparkleTile4bpp(vpu, TILE_SPARKLE, /*nibble=*/15);

        // Disable all sprites first
        for (int i = 0; i < TOTAL_SPRITES; i++) {
            writeSpriteTile8x8(vpu, i, 0, 0, 0, 0, false, false, false, /*priority=*/200, XSTEP_2X, YSTEP_1X);
        }

        // Ball physics (16.16)
        int x = (40 << 16);
        int y = (25 << 16);
        int vx = (140 << 16) / 60;
        int vy = 0;
        int g  = (520 << 16) / 60 / 60;

        int minX = 0;
        int maxX = SRC_W - BALL_SZ;

        int minY = 8;
        int floorY = 175;
        int groundY = floorY - BALL_SZ;

        // Shadow bbox tracking
        int prevSX0 = 0, prevSY0 = 0, prevSX1 = -1, prevSY1 = -1;

        double ang = 0.0;
        int phase = 0;

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        final long frameNanos = 1_000_000_000L / 60L;
        long next = System.nanoTime();

        while (running.get()) {
            // Palette cycling only (Amiga trick)
            updatePlasmaPalette(vpu, phase);

            // Ball physics
            x += vx;
            vy += g;
            y += vy;

            int xi = x >> 16;
            if (xi < minX) { x = (minX << 16); vx = -vx; }
            else if (xi > maxX) { x = (maxX << 16); vx = -vx; }

            int yi = y >> 16;
            if (yi < minY) {
                y = (minY << 16);
                vy = -vy;
            } else if (yi > groundY) {
                y = (groundY << 16);
                vy = (int) (-vy * 0.92);
                if (abs(vy) < (80 << 16) / 60) vy = -(220 << 16) / 60;
            }

            int bx = x >> 16;
            int by = y >> 16;

            // Rotate ball texture by rewriting 64 tiles/frame into sprite bank
            ang += 0.07 + (Math.abs(vx >> 16) * 0.0006);
            int[] ballPix = buildBoingBallPixelsRotated(BALL_SZ, ang);
            writeBallTiles4bpp(vpu, TILE_BALL_BASE, ballPix, BALL_SZ);

            // Shadow: stable Bayer, update only bbox union (write underlay indices into planes 0..7)
            int ballCx = bx + (BALL_SZ / 2);
            int ballBottom = by + BALL_SZ;
            int height = clamp(floorY - ballBottom, 0, 140);

            int shCx = clamp(ballCx + 8, 0, SRC_W - 1);
            int shCy = floorY;

            double k = 1.0 - (height / 140.0) * 0.55;
            int rx = clamp((int) Math.round(38 * k), 12, 40);
            int ry = clamp((int) Math.round(12 * k), 6, 14);

            int shA = clamp((int) Math.round(200 * (1.0 - (height / 140.0) * 0.70)), 40, 210);

            int sx0 = clamp(shCx - rx - 2, 0, SRC_W - 1);
            int sx1 = clamp(shCx + rx + 2, 0, SRC_W - 1);
            int sy0 = clamp(shCy - ry - 2, 0, SRC_H - 1);
            int sy1 = clamp(shCy + ry + 2, 0, SRC_H - 1);

            int ux0 = prevSX1 >= prevSX0 ? Math.min(prevSX0, sx0) : sx0;
            int uy0 = prevSY1 >= prevSY0 ? Math.min(prevSY0, sy0) : sy0;
            int ux1 = prevSX1 >= prevSX0 ? Math.max(prevSX1, sx1) : sx1;
            int uy1 = prevSY1 >= prevSY0 ? Math.max(prevSY1, sy1) : sy1;

            applyShadowRegion8bppPlanar(vpu, plasmaVal, ux0, uy0, ux1, uy1, shCx, shCy, rx, ry, shA);

            prevSX0 = sx0; prevSY0 = sy0; prevSX1 = sx1; prevSY1 = sy1;

            // Sprites: ball (64). Coordinates are LORES source pixels: convert X to output pixels by *2.
            int si = 0;
            for (int ty = 0; ty < BALL_TILES_Y; ty++) {
                for (int tx = 0; tx < BALL_TILES_X; tx++) {
                    int tile = TILE_BALL_BASE + (ty * BALL_TILES_X) + tx;
                    int sx = bx + (tx * 8);
                    int sy = by + (ty * 8);
                    writeSpriteTile8x8(vpu, si++, sx, sy, tile, /*palBank=*/1,
                            false, false, true, /*priority=*/200, XSTEP_2X, YSTEP_1X);
                }
            }

            // Sprites: sparkles (64) with gentle v4-style fixed-point scaling pulse.
            int centerX = SRC_W / 2;
            int centerY = SRC_H / 2;

            for (int i = 0; i < SPARK_SPRITES; i++) {
                int spr = BALL_SPRITES + i;

                int a0 = (phase + i * 7) & 255;
                int a1 = ((phase << 1) + i * 11) & 255;

                int dx = (SIN256[a0] * 78) / 127;
                int dy = (SIN256[a1] * 48) / 127;

                int wobX = (SIN256[(a1 + 64) & 255] * 16) / 127;
                int wobY = (SIN256[(a0 + 128) & 255] * 12) / 127;

                int sx = centerX + dx + wobX;
                int sy = centerY + dy + wobY;

                boolean en = ((phase + i * 9) & 16) == 0;

                // pulse scale around 2.0x (LORES semantics): s in [1.6..2.4]
                double s = 2.0 + 0.4 * (SIN256[(phase + i * 13) & 255] / 127.0);
                int xstep = (int) Math.round(256.0 / s);
                if (xstep < 1) xstep = 1;
                if (xstep > 0xFFFF) xstep = 0xFFFF;

                // Keep Y scaling stable (YSTEP=1.0 in source-line space).
                writeSpriteTile8x8(vpu, spr, sx, sy, TILE_SPARKLE, /*palBank=*/0,
                        false, false, en, /*priority=*/220, xstep, YSTEP_1X);
            }

            // Tick exactly one frame
            tickExactlyOneFrame(vpu, cyclesPerFrame);

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                double fps = (fpsFrames * 1_000_000_000.0) / (now - fpsT0);
                final String title = String.format("VPU v4 Demo: Plasma + Rotating Ball + 128 Sprites — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            phase = (phase + 2) & 255;

            // Pace
            next += frameNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else next = System.nanoTime();
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // v4 Group config: group0 uses planes 0..7 as 8bpp, LORES
    // ---------------------------------------------------------------------

    private static void writeGroup0_8bppLores(VPU_v4 vpu) {
        int base = GROUP_BASE;

        vpu.writeMmio(base + 0, (byte) 0);     // PLANE_START
        vpu.writeMmio(base + 1, (byte) 8);     // PLANE_COUNT
        vpu.writeMmio(base + 2, (byte) 0);     // PAL_BASE

        writeMmio16(vpu, base + 3, 0);         // SCROLL_X
        vpu.writeMmio(base + 5, (byte) 0);     // SCROLL_Y
        vpu.writeMmio(base + 6, (byte) 0);     // PRIORITY

        // LORES. Wrap is fine; OPAQUE0 makes index 0 a real color (palette[0]).
        vpu.writeMmio(base + 7, (byte) (GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0));

        writeMmio16(vpu, base + 8, 0);         // BPL_OFS (14-bit)

        // Disable other groups
        for (int g = 1; g < 4; g++) {
            vpu.writeMmio(GROUP_BASE + g * GROUP_STRIDE + 1, (byte) 0); // PLANE_COUNT=0
        }
    }

    private static void writeMmio16(VPU_v4 vpu, int addr, int value) {
        vpu.writeMmio(addr & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeMmio((addr + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // Underlay: plasma values (0..111) and planar 8bpp encoding (planes 0..7)
    // ---------------------------------------------------------------------

    private static byte[] buildStaticPlasmaValues() {
        byte[] v = new byte[SRC_W * SRC_H];

        for (int y = 0; y < SRC_H; y++) {
            for (int x = 0; x < SRC_W; x++) {
                int d = DIST[y * SRC_W + x];

                int s0 = SIN256[(x * 3) & 255];
                int s1 = SIN256[(y * 4) & 255];
                int s2 = SIN256[(d * 2) & 255];
                int s3 = SIN256[((x + y) * 2) & 255];

                int s = s0 + s1 + s2 + s3; // ~[-508..508]

                int vv = (s + 512) * PLASMA_COLORS;
                vv >>= 10; // ~ /1024
                if (vv < 0) vv = 0;
                if (vv >= PLASMA_COLORS) vv = PLASMA_COLORS - 1;

                v[y * SRC_W + x] = (byte) vv;
            }
        }
        return v;
    }

    private static void fillLores8bppPlanar(VPU_v4 vpu, byte[] plasmaVal) {
        int[] planeByte = new int[8];

        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * BYTES_PER_ROW;
            int pixBase = y * SRC_W;

            for (int bx = 0; bx < BYTES_PER_ROW; bx++) {
                Arrays.fill(planeByte, 0);
                int x0 = bx << 3;

                for (int i = 0; i < 8; i++) {
                    int v = plasmaVal[pixBase + x0 + i] & 0xFF;     // 0..111
                    int idx = PLASMA_PAL_BASE + v;                  // 32..143 (normal)
                    int bit = 1 << (7 - i);

                    for (int p = 0; p < 8; p++) {
                        if (((idx >>> p) & 1) != 0) planeByte[p] |= bit;
                    }
                }

                int ofs = rowBase + bx;
                for (int p = 0; p < 8; p++) {
                    vpu.writeVramPlane(p, ofs, (byte) planeByte[p]);
                }
            }
        }
    }

    private static void applyShadowRegion8bppPlanar(
            VPU_v4 vpu, byte[] plasmaVal,
            int x0, int y0, int x1, int y1,
            int shCx, int shCy, int rx, int ry, int shA
    ) {
        if (x1 < x0 || y1 < y0) return;

        int rx2 = rx * rx;
        int ry2 = ry * ry;
        if (rx2 <= 0 || ry2 <= 0) return;

        int[] planeByte = new int[8];

        int bx0 = x0 >> 3;
        int bx1 = x1 >> 3;

        for (int y = y0; y <= y1; y++) {
            int dy = y - shCy;
            int dy2 = dy * dy;

            int rowBase = y * BYTES_PER_ROW;

            for (int bx = bx0; bx <= bx1; bx++) {
                Arrays.fill(planeByte, 0);
                int xBase = bx << 3;

                for (int i = 0; i < 8; i++) {
                    int x = xBase + i;
                    if (x < 0 || x >= SRC_W) continue;

                    int idx = plasmaIndexWithShadow(plasmaVal, x, y, shCx, dy2, rx2, ry2, shA);
                    int bit = 1 << (7 - i);

                    for (int p = 0; p < 8; p++) {
                        if (((idx >>> p) & 1) != 0) planeByte[p] |= bit;
                    }
                }

                int ofs = rowBase + bx;
                for (int p = 0; p < 8; p++) {
                    vpu.writeVramPlane(p, ofs, (byte) planeByte[p]);
                }
            }
        }
    }

    private static int plasmaIndexWithShadow(
            byte[] plasmaVal,
            int x, int y,
            int shCx, int dy2, int rx2, int ry2, int shA
    ) {
        int baseV = plasmaVal[y * SRC_W + x] & 0xFF; // 0..111

        int dx = x - shCx;
        long lhs = (long) dx * dx * ry2 + (long) dy2 * rx2;
        long rhs = (long) rx2 * ry2;

        if (lhs >= rhs) {
            return PLASMA_PAL_BASE + baseV;
        }

        long inside = rhs - lhs;
        int fall = (int) (inside * shA / rhs);       // 0..shA

        int thr = BAYER8[(x & 7) | ((y & 7) << 3)] * 4; // 0..252
        boolean shadowOn = (thr < fall);

        return (shadowOn ? PLASMA_PAL_SHADOW : PLASMA_PAL_BASE) + baseV;
    }

    // ---------------------------------------------------------------------
    // Palette: fixed base + per-frame cycling (writes 32..255)
    // ---------------------------------------------------------------------

    private static void installFixedPalettes(VPU_v4 vpu) {
        // 0..15: VGA-ish
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);

        // Bank 1 (16..31): ball palette
        int[] bank1 = {
                0x000000, 0x1A0A0A, 0x5A0000, 0xA00000,
                0xFF0000, 0xFF6060, 0x1A1A1A, 0x606060,
                0xB0B0B0, 0xFFFFFF, 0xFFF0F0, 0x000000,
                0x000000, 0x000000, 0x000000, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, 16 + i, bank1[i]);

        updatePlasmaPalette(vpu, 0);
    }

    private static void updatePlasmaPalette(VPU_v4 vpu, int phase) {
        for (int i = 0; i < PLASMA_COLORS; i++) {
            double h = (((i * 2) + phase) & 255) / 256.0;

            int rgbN = hsvToRgb888(h, 1.0, 0.92);
            writeRgb888ToPal(vpu, PLASMA_PAL_BASE + i, rgbN);

            int rgbS = hsvToRgb888(h, 1.0, 0.35);
            writeRgb888ToPal(vpu, PLASMA_PAL_SHADOW + i, rgbS);
        }
    }

    private static int hsvToRgb888(double h, double s, double v) {
        double hh = (h % 1.0) * 6.0;
        int sector = (int) Math.floor(hh);
        double f = hh - sector;

        double p = v * (1.0 - s);
        double q = v * (1.0 - s * f);
        double t = v * (1.0 - s * (1.0 - f));

        double r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }

        int R = (int) Math.round(r * 255.0);
        int G = (int) Math.round(g * 255.0);
        int B = (int) Math.round(b * 255.0);
        return (R << 16) | (G << 8) | B;
    }

    private static void writeRgb888ToPal(VPU_v4 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // Ball: build pixels (nibbles 0..15). 0 means transparent in sprite tiles.
    // ---------------------------------------------------------------------

    private static int[] buildBoingBallPixelsRotated(int size, double angleRad) {
        int[] out = new int[size * size];

        double r = (size - 1) * 0.5;
        double cx0 = r;
        double cy0 = r;

        // fixed world light
        double lx = -0.35, ly = -0.30, lz = 0.88;
        double lLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= lLen; ly /= lLen; lz /= lLen;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double nx = (x - cx0) / r;
                double ny = (y - cy0) / r;
                double rr = nx * nx + ny * ny;
                if (rr > 1.0) { out[y * size + x] = 0; continue; }

                double nz = Math.sqrt(1.0 - rr);

                double diff = nx * lx + ny * ly + nz * lz;
                if (diff < 0) diff = 0;

                double phi = Math.atan2(ny, nx) + angleRad;

                double u = (phi / (2.0 * Math.PI) + 0.5) * 12.0;
                double v = (Math.acos(nz) / Math.PI) * 12.0;
                int check = (((int) Math.floor(u)) + ((int) Math.floor(v))) & 1;

                if (rr > 0.94) { out[y * size + x] = 1; continue; } // outline

                int pix;
                if (check == 0) {
                    if (diff < 0.22) pix = 2;
                    else if (diff < 0.48) pix = 3;
                    else if (diff < 0.72) pix = 4;
                    else pix = 5;
                } else {
                    if (diff < 0.22) pix = 6;
                    else if (diff < 0.48) pix = 7;
                    else if (diff < 0.72) pix = 8;
                    else pix = 9;
                }

                // specular
                double hx = lx;
                double hy = ly;
                double hz = lz + 1.0;
                double hLen = Math.sqrt(hx * hx + hy * hy + hz * hz);
                hx /= hLen; hy /= hLen; hz /= hLen;
                double spec = nx * hx + ny * hy + nz * hz;
                if (spec < 0) spec = 0;
                spec = Math.pow(spec, 22.0);
                if (spec > 0.30) pix = 10;

                out[y * size + x] = pix;
            }
        }
        return out;
    }

    private static void writeBallTiles4bpp(VPU_v4 vpu, int tileBase, int[] pix, int size) {
        int tilesX = size / 8;
        int tilesY = size / 8;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIndex = tileBase + (ty * tilesX) + tx;
                writeTileFromPixelsToSpriteBank(vpu, tileIndex, pix, size, tx * 8, ty * 8);
            }
        }
    }

    private static void writeTileFromPixelsToSpriteBank(VPU_v4 vpu, int tileIndex, int[] pix, int size, int x0, int y0) {
        int base = tileIndex * TILE_BYTES;
        for (int y = 0; y < 8; y++) {
            int yy = y0 + y;
            for (int xPair = 0; xPair < 4; xPair++) {
                int xx = x0 + (xPair * 2);
                int hi = pix[yy * size + xx] & 0x0F;
                int lo = pix[yy * size + (xx + 1)] & 0x0F;
                int packed = (hi << 4) | lo;
                vpu.writeVramPlane(VB_SPR, base + (y * 4) + xPair, (byte) packed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sparkle tile (sprite bank)
    // ---------------------------------------------------------------------

    private static void writeSparkleTile4bpp(VPU_v4 vpu, int tileIndex, int nibble) {
        int base = tileIndex * TILE_BYTES;
        for (int y = 0; y < 8; y++) {
            for (int xPair = 0; xPair < 4; xPair++) {
                int x0 = xPair * 2;

                int p0 = sparklePixel(x0, y) ? nibble : 0;
                int p1 = sparklePixel(x0 + 1, y) ? nibble : 0;

                int packed = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                vpu.writeVramPlane(VB_SPR, base + y * 4 + xPair, (byte) packed);
            }
        }
    }

    private static boolean sparklePixel(int x, int y) {
        return (x == 3 && y >= 1 && y <= 6)
                || (y == 3 && x >= 1 && x <= 6)
                || (x == 4 && y == 4);
    }

    // ---------------------------------------------------------------------
    // v4 OAM writes (32 bytes)
    // ---------------------------------------------------------------------

    private static void writeSpriteTile8x8(
            VPU_v4 vpu, int spriteIndex,
            int xSrc, int ySrc, int tileIndex, int palBank,
            boolean hflip, boolean vflip, boolean enable,
            int priority, int xstep88, int ystep88
    ) {
        int base = spriteIndex * SPR_STRIDE;

        // v4: X is in OUTPUT pixels. Emulate v3 LORES coords by: outX = xSrc*2.
        int outX = xSrc << 1;

        // Y is in SOURCE lines already.
        int outY = ySrc;

        int attr = 0;
        if (enable) attr |= SA_EN;
        if (hflip)  attr |= SA_HFLIP;
        if (vflip)  attr |= SA_VFLIP;

        int w = 8;
        int h = 8;
        int dataOfs = (tileIndex * TILE_BYTES) & 0x3FFF;

        // Write OAM into tables bank (VBANK=9), little-endian fields.
        writeTbl16s(vpu, base + O_X_L, outX);
        writeTbl16s(vpu, base + O_Y_L, outY);

        vpu.writeVramPlane(VB_TBL, (base + O_W) & 0x3FFF, (byte) (w & 0xFF));
        vpu.writeVramPlane(VB_TBL, (base + O_H) & 0x3FFF, (byte) (h & 0xFF));

        writeTbl16(vpu, base + O_DATA_L, dataOfs);

        vpu.writeVramPlane(VB_TBL, (base + O_ATTR) & 0x3FFF, (byte) (attr & 0xFF));
        vpu.writeVramPlane(VB_TBL, (base + O_PAL) & 0x3FFF, (byte) (palBank & 0x0F));
        vpu.writeVramPlane(VB_TBL, (base + O_COL_ID) & 0x3FFF, (byte) 0);
        vpu.writeVramPlane(VB_TBL, (base + O_PRIORITY) & 0x3FFF, (byte) (priority & 0xFF));

        writeTbl16(vpu, base + O_XSTEP_L, xstep88 & 0xFFFF);
        writeTbl16(vpu, base + O_YSTEP_L, ystep88 & 0xFFFF);

        vpu.writeVramPlane(VB_TBL, (base + O_TILT_DX) & 0x3FFF, (byte) 0);
        vpu.writeVramPlane(VB_TBL, (base + O_LINK) & 0x3FFF, (byte) 0);
    }

    private static void writeTbl16(VPU_v4 vpu, int addr, int valueU16) {
        vpu.writeVramPlane(VB_TBL, (addr) & 0x3FFF, (byte) (valueU16 & 0xFF));
        vpu.writeVramPlane(VB_TBL, (addr + 1) & 0x3FFF, (byte) ((valueU16 >>> 8) & 0xFF));
    }

    private static void writeTbl16s(VPU_v4 vpu, int addr, int valueS16) {
        int v = valueS16 & 0xFFFF;
        writeTbl16(vpu, addr, v);
    }

    // ---------------------------------------------------------------------
    // Timing helper
    // ---------------------------------------------------------------------

    private static void tickExactlyOneFrame(VPU_v4 vpu, int cyclesPerFrame) {
        final int chunk = 5_000;
        int done = 0;
        while (done < cyclesPerFrame) {
            int step = Math.min(chunk, cyclesPerFrame - done);
            vpu.tick(step);
            done += step;
        }
    }

    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : Math.min(v, hi);
    }

    private static int abs(int v) {
        return v < 0 ? -v : v;
    }

    @Override
    public void stop() {
        running.set(false);

        if (fxTimer != null) {
            fxTimer.stop();
            fxTimer = null;
        }
        if (emuThread != null) {
            try { emuThread.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
