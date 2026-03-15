package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v2;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo (Option 1 / R816-friendly):
 *  - MODE 2 underlay is a STATIC plasma index field written ONCE into VRAM.
 *  - Plasma animates via PALETTE CYCLING each frame (Amiga-style trick).
 *  - Ball rotates by rewriting only its 64 tiles per frame (~2KB) into tile RAM.
 *  - Shadow does NOT rotate / shimmer: stable Bayer dither, no time component.
 *  - NO copper.
 *  - 128 sprites total: 64 for the 64x64 ball (8x8 tiles), + 64 sparkles.
 *
 * Notes:
 *  - Sprites are in SOURCE coords (MODE 2 source = 320x200).
 *  - Underlay is Mode-X style: plane = (x & 3), ofs = y*BPL + (x>>2).
 */
public final class VpuDemoPlasmaAndSpritesMode2Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // framebuffer width/height (output)
            640, 400,   // canvas width/height
            400,        // vblankStart (scanline)
            449,        // scanlinesPerFrame
            400         // cyclesPerScanline
    );

    // ---- VPU MMIO offsets ----
    private static final int REG_MODE     = 0x0002;
    private static final int REG_TX_CTRL  = 0x0007;
    private static final int REG_OVL_MODE = 0x001F;

    private static final int PAL_BASE     = 0x0100;
    private static final int OAM_BASE     = 0x0300;
    private static final int OAM_STRIDE   = 8;
    private static final int OVERLAY_BASE = 0x2000;

    // MODE 2 source resolution
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL   = SRC_W / 4; // 80 bytes per row per plane

    // Ball: 64x64 source pixels => 8x8 tiles => 64 sprites
    private static final int BALL_SZ      = 64;
    private static final int BALL_TILES_X = BALL_SZ / 8; // 8
    private static final int BALL_TILES_Y = BALL_SZ / 8; // 8
    private static final int BALL_SPRITES = BALL_TILES_X * BALL_TILES_Y; // 64

    // Sparkles: 64 sprites
    private static final int SPARK_SPRITES = 64;
    private static final int TOTAL_SPRITES = 128;

    // Tile indices
    private static final int TILE_BALL_BASE = 0;    // 0..63
    private static final int TILE_SPARKLE   = 64;   // one tile shared by all sparkles

    // Plasma palette layout (Amiga-ish palette trick):
    //  - 32..143  : 112 colors (NORMAL ramp)
    //  - 144..255 : 112 colors (SHADOW ramp = darker version of the same ramp)
    private static final int PLASMA_PAL_BASE    = 32;
    private static final int PLASMA_COLORS      = 112;
    private static final int PLASMA_PAL_SHADOW  = PLASMA_PAL_BASE + PLASMA_COLORS; // 144

    // Precomputed sine and distance tables (for static plasma field and sparkle motion)
    private static final int[] SIN256 = new int[256]; // -127..127
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

    // Stable Bayer 8x8 (for non-rotating, non-shimmer shadow edge)
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

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER); // center canvas in the window
        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU Demo: MODE 2 Palette-Plasma + Rotating Ball + 128 Sprites — FPS --");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v2 vpu = new VPU_v2(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoPlasmaAndSpritesMode2Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // MODE 2 underlay + sprite overlay personality
        vpu.writeMmio(REG_MODE, (byte) 2);
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x01); // sprite personality
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);     // text off

        // Install base palettes:
        // 0..15  : VGA-ish
        // 16..31 : ball palette bank (palBank=1)
        // 32..255: plasma ramps (normal + shadow), which we will cycle each frame
        installFixedPalettes(vpu);

        // Precompute STATIC plasma value field (0..111) once.
        final byte[] plasmaVal = buildStaticPlasmaValues();

        // Fill Mode 2 VRAM ONCE with initial indices (normal ramp).
        fillMode2WithPlasma(vpu, plasmaVal);

        // Build sparkle tile once (tile 64)
        writeSparkleTile4bpp(vpu, TILE_SPARKLE, /*nibble=*/15);

        // Disable all sprites first
        for (int i = 0; i < TOTAL_SPRITES; i++) {
            writeSprite(vpu, i, 0, 0, 0, 0, false, false, false);
        }

        // Ball physics (16.16)
        int x = (40 << 16);
        int y = (25 << 16);
        int vx = (140 << 16) / 60;
        int vy = (0 << 16);
        int g  = (520 << 16) / 60 / 60;

        int minX = 0;
        int maxX = SRC_W - BALL_SZ;

        int minY = 8;
        int floorY = 175;                 // floor line in SOURCE pixels
        int groundY = floorY - BALL_SZ;   // top-of-ball at rest on floor

        // Shadow bookkeeping (we update only the union bbox each frame)
        int prevSX0 = 0, prevSY0 = 0, prevSX1 = -1, prevSY1 = -1;

        // Rotation state (radians)
        double ang = 0.0;

        // Palette cycling phase (0..255)
        int phase = 0;

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        while (running.get()) {
            // --------- palette trick plasma animation (Amiga style) ----------
            // Only update palette entries; VRAM indices remain static.
            updatePlasmaPalette(vpu, phase);

            // --------- ball physics ----------
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

            // --------- rotate ball texture (NOT the lighting) ----------
            // Pattern rotates (phi += ang), shading stays stable (world light).
            ang += 0.07 + (Math.abs(vx >> 16) * 0.0006);
            int[] ballPix = buildBoingBallPixelsRotated(BALL_SZ, ang);
            writeBallTiles4bpp(vpu, TILE_BALL_BASE, ballPix, BALL_SZ);

            // --------- update shadow in underlay (stable dither, no shimmer) ----------
            // Shadow center tracks ball X; shadow size/intensity depends on height above floor.
            int ballCx = bx + (BALL_SZ / 2);
            int ballBottom = by + BALL_SZ;
            int height = clamp(floorY - ballBottom, 0, 140); // 0..140

            // Shadow ellipse params (tuned for "boing" feel)
            int shCx = clamp(ballCx + 8, 0, SRC_W - 1);  // slight offset
            int shCy = floorY;
            double k = 1.0 - (height / 140.0) * 0.55;    // higher => smaller
            int rx = (int) Math.round(38 * k);
            int ry = (int) Math.round(12 * k);
            rx = clamp(rx, 12, 40);
            ry = clamp(ry, 6, 14);

            int shA = (int) Math.round(200 * (1.0 - (height / 140.0) * 0.70)); // higher => lighter
            shA = clamp(shA, 40, 210);

            int sx0 = clamp(shCx - rx - 2, 0, SRC_W - 1);
            int sx1 = clamp(shCx + rx + 2, 0, SRC_W - 1);
            int sy0 = clamp(shCy - ry - 2, 0, SRC_H - 1);
            int sy1 = clamp(shCy + ry + 2, 0, SRC_H - 1);

            // union bbox of previous+current shadow area
            int ux0 = prevSX1 >= prevSX0 ? Math.min(prevSX0, sx0) : sx0;
            int uy0 = prevSY1 >= prevSY0 ? Math.min(prevSY0, sy0) : sy0;
            int ux1 = prevSX1 >= prevSX0 ? Math.max(prevSX1, sx1) : sx1;
            int uy1 = prevSY1 >= prevSY0 ? Math.max(prevSY1, sy1) : sy1;

            applyShadowRegionMode2(vpu, plasmaVal, ux0, uy0, ux1, uy1, shCx, shCy, rx, ry, shA);

            prevSX0 = sx0; prevSY0 = sy0; prevSX1 = sx1; prevSY1 = sy1;

            // --------- sprites: ball (64) ----------
            int si = 0;
            for (int ty = 0; ty < BALL_TILES_Y; ty++) {
                for (int tx = 0; tx < BALL_TILES_X; tx++) {
                    int tile = TILE_BALL_BASE + (ty * BALL_TILES_X) + tx; // 0..63
                    int sx = bx + (tx * 8);
                    int sy = by + (ty * 8);
                    writeSprite(vpu, si++, sx, sy, tile, /*palBank=*/1, false, false, true);
                }
            }

            // --------- sprites: sparkles (64) ----------
            int centerX = SRC_W / 2;
            int centerY = SRC_H / 2;
            for (int i = 0; i < SPARK_SPRITES; i++) {
                int idx = BALL_SPRITES + i;

                int a0 = (phase + i * 7) & 255;
                int a1 = ((phase << 1) + i * 11) & 255;

                int dx = (SIN256[a0] * 78) / 127;
                int dy = (SIN256[a1] * 48) / 127;

                int wobX = (SIN256[(a1 + 64) & 255] * 16) / 127;
                int wobY = (SIN256[(a0 + 128) & 255] * 12) / 127;

                int sx = centerX + dx + wobX;
                int sy = centerY + dy + wobY;

                // sparkle "twinkle": half of them blink each frame
                boolean en = ((phase + i * 9) & 16) == 0;

                writeSprite(vpu, idx, sx, sy, TILE_SPARKLE, /*palBank=*/0, false, false, en);
            }

            // --------- run one frame worth of VPU time ----------
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(chunk);
            }

            // FPS in title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU Demo: MODE 2 Palette-Plasma + Rotating Ball + 128 Sprites — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            phase = (phase + 2) & 255;

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // =========================================================================
    // Static plasma value field (0..111) + VRAM fill once
    // =========================================================================

    private static byte[] buildStaticPlasmaValues() {
        byte[] v = new byte[SRC_W * SRC_H];

        for (int y = 0; y < SRC_H; y++) {
            for (int x = 0; x < SRC_W; x++) {
                int d = DIST[y * SRC_W + x];

                int s0 = SIN256[(x * 3) & 255];
                int s1 = SIN256[(y * 4) & 255];
                int s2 = SIN256[(d * 2) & 255];
                int s3 = SIN256[((x + y) * 2) & 255];

                // range approx [-508..508]
                int s = s0 + s1 + s2 + s3;

                // Map to 0..111 (PLASMA_COLORS-1)
                int vv = (s + 512) * PLASMA_COLORS;
                vv = vv >> 10; // /1024-ish
                if (vv < 0) vv = 0;
                if (vv >= PLASMA_COLORS) vv = PLASMA_COLORS - 1;

                v[y * SRC_W + x] = (byte) vv;
            }
        }
        return v;
    }

    private static void fillMode2WithPlasma(VPU_v2 vpu, byte[] plasmaVal) {
        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * BPL;
            int pixBase = y * SRC_W;
            for (int byteX = 0; byteX < BPL; byteX++) {
                int x0 = (byteX << 2);
                int ofs = rowBase + byteX;

                int v0 = plasmaVal[pixBase + x0] & 0xFF;
                int v1 = plasmaVal[pixBase + x0 + 1] & 0xFF;
                int v2 = plasmaVal[pixBase + x0 + 2] & 0xFF;
                int v3 = plasmaVal[pixBase + x0 + 3] & 0xFF;

                vpu.writeVramPlane(0, ofs, (byte) (PLASMA_PAL_BASE + v0));
                vpu.writeVramPlane(1, ofs, (byte) (PLASMA_PAL_BASE + v1));
                vpu.writeVramPlane(2, ofs, (byte) (PLASMA_PAL_BASE + v2));
                vpu.writeVramPlane(3, ofs, (byte) (PLASMA_PAL_BASE + v3));
            }
        }
    }

    // =========================================================================
    // Shadow update: write only union bbox; stable dither; uses shadow palette ramp
    // =========================================================================

    private static void applyShadowRegionMode2(
            VPU_v2 vpu, byte[] plasmaVal,
            int x0, int y0, int x1, int y1,
            int shCx, int shCy, int rx, int ry, int shA
    ) {
        if (x1 < x0 || y1 < y0) return;

        int rx2 = rx * rx;
        int ry2 = ry * ry;
        if (rx2 <= 0 || ry2 <= 0) return;

        for (int y = y0; y <= y1; y++) {
            int dy = y - shCy;
            int dy2 = dy * dy;

            int rowBase = y * BPL;
            int pixBase = y * SRC_W;

            // process per Mode-X byte (4 pixels)
            int bx0 = x0 >> 2;
            int bx1 = x1 >> 2;

            for (int byteX = bx0; byteX <= bx1; byteX++) {
                int ofs = rowBase + byteX;
                int px = byteX << 2;

                int idx0 = plasmaIndexWithShadow(plasmaVal, px + 0, y, shCx, dy2, rx2, ry2, shA);
                int idx1 = plasmaIndexWithShadow(plasmaVal, px + 1, y, shCx, dy2, rx2, ry2, shA);
                int idx2 = plasmaIndexWithShadow(plasmaVal, px + 2, y, shCx, dy2, rx2, ry2, shA);
                int idx3 = plasmaIndexWithShadow(plasmaVal, px + 3, y, shCx, dy2, rx2, ry2, shA);

                vpu.writeVramPlane(0, ofs, (byte) idx0);
                vpu.writeVramPlane(1, ofs, (byte) idx1);
                vpu.writeVramPlane(2, ofs, (byte) idx2);
                vpu.writeVramPlane(3, ofs, (byte) idx3);
            }
        }
    }

    private static int plasmaIndexWithShadow(
            byte[] plasmaVal,
            int x, int y,
            int shCx, int dy2, int rx2, int ry2, int shA
    ) {
        if (x < 0 || x >= SRC_W) return PLASMA_PAL_BASE; // shouldn't happen

        int baseV = plasmaVal[y * SRC_W + x] & 0xFF; // 0..111

        // ellipse implicit: (dx^2)/rx^2 + (dy^2)/ry^2 < 1
        int dx = x - shCx;
        long lhs = (long) dx * dx * ry2 + (long) dy2 * rx2;
        long rhs = (long) rx2 * ry2;

        if (lhs >= rhs) {
            return PLASMA_PAL_BASE + baseV; // normal ramp
        }

        // inside ellipse: compute falloff (0..shA) from distance to edge (approx)
        // use normalized "how deep inside" = 1 - lhs/rhs
        long inside = rhs - lhs;
        int fall = (int) (inside * shA / rhs); // 0..shA

        // stable Bayer threshold (no time term => no shimmer/rotation)
        int thr = BAYER8[(x & 7) | ((y & 7) << 3)] * 4; // 0..252
        boolean shadowOn = (thr < fall);

        return (shadowOn ? PLASMA_PAL_SHADOW : PLASMA_PAL_BASE) + baseV;
    }

    // =========================================================================
    // Palette: fixed base + per-frame cycling (Amiga trick)
    // =========================================================================

    private static void installFixedPalettes(VPU_v2 vpu) {
        // 0..15: VGA-ish
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);

        // Bank 1 (16..31): ball palette (nibbles 1..10 used)
        int[] bank1 = {
                0x000000, // 16 (unused)
                0x1A0A0A, // 17 outline
                0x5A0000, // 18 dark red
                0xA00000, // 19 mid red
                0xFF0000, // 20 bright red
                0xFF6060, // 21 highlight red
                0x1A1A1A, // 22 dark gray
                0x606060, // 23 mid gray
                0xB0B0B0, // 24 light gray
                0xFFFFFF, // 25 white
                0xFFF0F0, // 26 warm highlight
                0x000000, // 27
                0x000000, // 28
                0x000000, // 29
                0x000000, // 30
                0xFFFFFF  // 31
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, 16 + i, bank1[i]);

        // Plasma ramps will be written by updatePlasmaPalette() each frame.
        updatePlasmaPalette(vpu, 0);
    }

    private static void updatePlasmaPalette(VPU_v2 vpu, int phase) {
        // 112 colors normal, 112 colors shadow (darker)
        for (int i = 0; i < PLASMA_COLORS; i++) {
            // Hue wheel with phase shift (palette cycling)
            double h = (((i * 2) + phase) & 255) / 256.0; // 0..1
            double s = 1.0;

            // normal ramp
            int rgbN = hsvToRgb888(h, s, 0.92);
            writeRgb888ToPal(vpu, PLASMA_PAL_BASE + i, rgbN);

            // shadow ramp (same hue, lower value)
            int rgbS = hsvToRgb888(h, s, 0.35);
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

    private static void writeRgb888ToPal(VPU_v2 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    // =========================================================================
    // Ball: generate rotated texture (phi += angle), lighting stays stable
    // =========================================================================

    private static int[] buildBoingBallPixelsRotated(int size, double angleRad) {
        int[] out = new int[size * size];

        double r = (size - 1) * 0.5;
        double cx0 = r;
        double cy0 = r;

        // fixed world light (so highlight doesn't rotate with texture)
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

                // texture rotation (around Z axis in screen space): rotate phi
                double phi = Math.atan2(ny, nx) + angleRad;

                // "grid" parameterization
                double u = (phi / (2.0 * Math.PI) + 0.5) * 12.0;
                double v = (Math.acos(nz) / Math.PI) * 12.0;
                int check = (((int) Math.floor(u)) + ((int) Math.floor(v))) & 1;

                // outline
                if (rr > 0.94) { out[y * size + x] = 1; continue; }

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

                // specular (fixed relative to light => stable)
                double hx = lx;
                double hy = ly;
                double hz = lz + 1.0;
                double hLen = Math.sqrt(hx * hx + hy * hy + hz * hz);
                hx /= hLen; hy /= hLen; hz /= hLen;
                double spec = nx * hx + ny * hy + nz * hz;
                if (spec < 0) spec = 0;
                spec = Math.pow(spec, 22.0);
                if (spec > 0.30) pix = 10;

                out[y * size + x] = pix; // 0..15 nibble
            }
        }
        return out;
    }

    private static void writeBallTiles4bpp(VPU_v2 vpu, int tileBase, int[] pix, int size) {
        int tilesX = size / 8;
        int tilesY = size / 8;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIndex = tileBase + (ty * tilesX) + tx;
                writeTileFromPixels(vpu, tileIndex, pix, size, tx * 8, ty * 8);
            }
        }
    }

    private static void writeTileFromPixels(VPU_v2 vpu, int tileIndex, int[] pix, int size, int x0, int y0) {
        int base = OVERLAY_BASE + (tileIndex * 32);
        for (int y = 0; y < 8; y++) {
            int yy = y0 + y;
            for (int xPair = 0; xPair < 4; xPair++) {
                int xx = x0 + (xPair * 2);
                int hi = pix[yy * size + xx] & 0x0F;
                int lo = pix[yy * size + (xx + 1)] & 0x0F;
                int packed = (hi << 4) | lo;
                int o = base + (y * 4) + xPair;
                vpu.writeMmio(o, (byte) packed);
            }
        }
    }

    // =========================================================================
    // Sparkle tile
    // =========================================================================

    private static void writeSparkleTile4bpp(VPU_v2 vpu, int tileIndex, int nibble) {
        int base = OVERLAY_BASE + (tileIndex * 32);
        for (int y = 0; y < 8; y++) {
            for (int xPair = 0; xPair < 4; xPair++) {
                int x0 = xPair * 2;

                int p0 = sparklePixel(x0, y) ? nibble : 0;
                int p1 = sparklePixel(x0 + 1, y) ? nibble : 0;

                int packed = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                vpu.writeMmio(base + y * 4 + xPair, (byte) packed);
            }
        }
    }

    private static boolean sparklePixel(int x, int y) {
        return (x == 3 && y >= 1 && y <= 6)
                || (y == 3 && x >= 1 && x <= 6)
                || (x == 4 && y == 4);
    }

    // =========================================================================
    // OAM helper
    // =========================================================================

    private static void writeSprite(
            VPU_v2 vpu, int spriteIndex,
            int x, int y, int tile, int palBank,
            boolean hflip, boolean vflip, boolean enable
    ) {
        int base = OAM_BASE + (spriteIndex * OAM_STRIDE);

        int xClamped = clamp(x, 0, 1023);
        int yClamped = clamp(y, 0, 1023);

        int xLo = xClamped & 0xFF;
        int yLo = yClamped & 0xFF;
        int xyhi = ((xClamped >> 8) & 0x03) | (((yClamped >> 8) & 0x03) << 2);

        int attr = (palBank & 0x0F) << 4;
        if (enable) attr |= 0x01;
        if (hflip)  attr |= 0x02;
        if (vflip)  attr |= 0x04;

        vpu.writeMmio(base + 0, (byte) yLo);
        vpu.writeMmio(base + 1, (byte) xLo);
        vpu.writeMmio(base + 2, (byte) (tile & 0xFF));
        vpu.writeMmio(base + 3, (byte) (attr & 0xFF));
        vpu.writeMmio(base + 4, (byte) (xyhi & 0xFF));
        vpu.writeMmio(base + 5, (byte) 0);
        vpu.writeMmio(base + 6, (byte) 0);
        vpu.writeMmio(base + 7, (byte) 0);
    }

    // =========================================================================
    // utils
    // =========================================================================

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (Math.min(v, hi));
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