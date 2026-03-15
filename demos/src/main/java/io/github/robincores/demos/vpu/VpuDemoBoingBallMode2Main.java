package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v2;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo: Amiga-style "Boing Ball" using the VPU sprite/tile overlay.
 *
 * Uses MODE 2 (320x200 8bpp Mode-X-ish) scaled to 640x400 (square pixels).
 *
 * NOTE on coordinates:
 *  - With VPU_fixed_srcsprites.java, sprite X/Y are in SOURCE pixels of the active mode.
 *    For MODE 2 that is 320x200.
 */
public final class VpuDemoBoingBallMode2Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU MMIO offsets ----
    private static final int REG_MODE     = 0x0002;
    private static final int REG_TX_CTRL  = 0x0007;
    private static final int REG_OVL_MODE = 0x001F;

    private static final int PAL_BASE     = 0x0100;

    private static final int OAM_BASE     = 0x0300;
    private static final int OAM_STRIDE   = 8;

    private static final int OVERLAY_BASE = 0x2000;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // MODE 2 source resolution
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL = SRC_W / 4; // 80

    // Boing ball in source pixels: 64x64 -> output 128x128
    private static final int BALL_SZ = 64;
    private static final int BALL_TILES_X = BALL_SZ / 8; // 8
    private static final int BALL_TILES_Y = BALL_SZ / 8; // 8
    private static final int BALL_SPRITES = BALL_TILES_X * BALL_TILES_Y; // 64

    private static final int HORIZON_Y = 85;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU Demo: Boing Ball (MODE 2) — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoBoingBallMode2Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        vpu.writeMmio(REG_MODE, (byte) 2);
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x01); // sprite personality
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);

        installPalette(vpu);
        fillBackgroundMode2(vpu);

        // Build the ball as 64 tiles (8x8 tile grid)
        int[] ballPix = buildBoingBallPixels(BALL_SZ);
        writeBallTiles4bpp(vpu, /*tileBase=*/0, ballPix, BALL_SZ);

        // Disable all sprites initially
        for (int i = 0; i < 128; i++) {
            writeSprite(vpu, i, 0, 0, 0, 0, false, false, false);
        }

        // Motion (16.16 fixed)
        int x = (40 << 16);
        int y = (25 << 16);
        int vx = (140 << 16) / 60;  // px/sec
        int vy = (0 << 16);
        int g  = (520 << 16) / 60 / 60;

        int minX = 0;
        int maxX = SRC_W - BALL_SZ;

        int minY = 8;
        int groundY = 175 - BALL_SZ;

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        while (running.get()) {
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

            // Place 8x8 sprites
            int si = 0;
            for (int ty = 0; ty < BALL_TILES_Y; ty++) {
                for (int tx = 0; tx < BALL_TILES_X; tx++) {
                    int tile = (ty * BALL_TILES_X) + tx; // 0..63
                    int sx = bx + (tx * 8);
                    int sy = by + (ty * 8);
                    writeSprite(vpu, si++, sx, sy, tile, /*palBank=*/1, false, false, true);
                }
            }

            // Run one frame worth of VPU time
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
                final String title = String.format("VPU Demo: Boing Ball (MODE 2) — FPS %.1f", fps);
                Platform.runLater(() -> {
                    if (stage != null) stage.setTitle(title);
                });
                fpsT0 = now;
                fpsFrames = 0;
            }

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Background (MODE 2)
    // ---------------------------------------------------------------------

    private static void fillBackgroundMode2(VPU_v2 vpu) {
        for (int y = 0; y < SRC_H; y++) {
            int lineBase = y * BPL;
            for (int byteX = 0; byteX < BPL; byteX++) {
                int x0 = (byteX << 2);
                int ofs = lineBase + byteX;

                int idx0 = bgIndexFor(x0 + 0, y);
                int idx1 = bgIndexFor(x0 + 1, y);
                int idx2 = bgIndexFor(x0 + 2, y);
                int idx3 = bgIndexFor(x0 + 3, y);

                vpu.writeVramPlane(0, ofs, (byte) idx0);
                vpu.writeVramPlane(1, ofs, (byte) idx1);
                vpu.writeVramPlane(2, ofs, (byte) idx2);
                vpu.writeVramPlane(3, ofs, (byte) idx3);
            }
        }
    }

    private static int bgIndexFor(int x, int y) {
        // Sky
        if (y < HORIZON_Y) {
            int t = (y * 79) / Math.max(1, (HORIZON_Y - 1)); // 0..79
            int base = 32 + t; // 32..111

            // Clouds: more subtle at this res
            int c = 0;
            c += blob(x, y,  80, 28, 60, 16);
            c += blob(x, y, 165, 22, 70, 18);
            c += blob(x, y, 260, 35, 55, 14);
            if (c > 0) {
                int ct = Math.min(11, c);
                return 120 + ct; // 120..131 cloud ramp
            }

            // Sun glow
            int dx = x - 260;
            int dy = y - 25;
            int d2 = dx * dx + dy * dy;
            if (d2 < 1600) {
                int s = 15 - Math.min(15, d2 / 110);
                return 140 + (s / 2); // 140..147 warm glow
            }

            return base;
        }

        // Horizon
        if (y == HORIZON_Y) return 150;

        // Floor
        int dy = y - HORIZON_Y;

        // Pseudo-perspective: tile size grows with dy
        int tileX = 2 + (dy / 3);
        int tileY = 2 + (dy / 7);

        int gx = x / tileX;
        int gy = dy / tileY;
        int check = (gx + gy) & 1;

        boolean line = (x % tileX == 0) || (dy % tileY == 0);

        int shade = 15 - Math.min(15, (dy * 15) / (SRC_H - HORIZON_Y));
        int base = check == 0 ? 176 : 192;
        int idx = base + (shade / 2); // 176..183 or 192..199
        if (line) idx = 208 + (shade / 3); // 208..213

        // vignette
        int cx = SRC_W / 2;
        int dd = x - cx;
        int v = Math.min(20, (dd * dd) / 900);
        idx = Math.max(160, idx - (v / 4));

        return idx;
    }

    private static int blob(int x, int y, int cx, int cy, int rx, int ry) {
        int dx = x - cx;
        int dy = y - cy;
        int d = (dx * dx) * 100 / (rx * rx) + (dy * dy) * 100 / (ry * ry);
        if (d > 100) return 0;
        return (100 - d) / 9; // 0..11
    }

    // ---------------------------------------------------------------------
    // Palette
    // ---------------------------------------------------------------------

    private static void installPalette(VPU_v2 vpu) {
        // 0..15: VGA-ish
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);

        // Bank 1 (16..31): Boing ball
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

        // Sky 32..111 (80 entries)
        for (int i = 0; i < 80; i++) {
            double t = i / 79.0;
            int r = (int) lerp(2, 90, t);
            int g = (int) lerp(10, 160, t);
            int b = (int) lerp(40, 255, t);
            writeRgb888ToPal(vpu, 32 + i, (r << 16) | (g << 8) | b);
        }

        // Clouds 120..131
        for (int i = 0; i < 12; i++) {
            int c = (int) lerp(210, 255, i / 11.0);
            writeRgb888ToPal(vpu, 120 + i, (c << 16) | (c << 8) | c);
        }

        // Sun glow 140..147
        for (int i = 0; i < 8; i++) {
            int r = (int) lerp(255, 255, i / 7.0);
            int g = (int) lerp(240, 170, i / 7.0);
            int b = (int) lerp(180, 80, i / 7.0);
            writeRgb888ToPal(vpu, 140 + i, (r << 16) | (g << 8) | b);
        }

        // Horizon 150
        writeRgb888ToPal(vpu, 150, 0xFFE6A0);

        // Floor ramps 160..199
        for (int i = 0; i < 40; i++) {
            int c = (int) lerp(30, 210, i / 39.0);
            writeRgb888ToPal(vpu, 160 + i, (c << 16) | (c << 8) | c);
        }

        // Grid highlight 208..215
        for (int i = 0; i < 8; i++) {
            int c = (int) lerp(120, 255, i / 7.0);
            writeRgb888ToPal(vpu, 208 + i, (c << 16) | (c << 8) | c);
        }

        // Fill remaining indices
        for (int i = 216; i < 256; i++) {
            int r = 8, g = 10, b = 14;
            writeRgb888ToPal(vpu, i, (r << 16) | (g << 8) | b);
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
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

    // ---------------------------------------------------------------------
    // Ball generation -> tiles
    // ---------------------------------------------------------------------

    private static int[] buildBoingBallPixels(int size) {
        int[] out = new int[size * size];

        double r = (size - 1) * 0.5;
        double cx0 = r;
        double cy0 = r;

        double lx = -0.35, ly = -0.30, lz = 0.88;
        double lLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= lLen; ly /= lLen; lz /= lLen;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double nx = (x - cx0) / r;
                double ny = (y - cy0) / r;
                double rr = nx * nx + ny * ny;
                if (rr > 1.0) {
                    out[y * size + x] = 0;
                    continue;
                }

                double nz = Math.sqrt(1.0 - rr);

                double diff = nx * lx + ny * ly + nz * lz;
                if (diff < 0) diff = 0;

                double phi = Math.atan2(ny, nx);
                double u = (phi / (2.0 * Math.PI) + 0.5) * 12.0;
                double v = (Math.acos(nz) / Math.PI) * 12.0;
                int check = (((int) Math.floor(u)) + ((int) Math.floor(v))) & 1;

                if (rr > 0.94) {
                    out[y * size + x] = 1; // outline
                    continue;
                }

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

    // ---------------------------------------------------------------------
    // OAM
    // ---------------------------------------------------------------------

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
