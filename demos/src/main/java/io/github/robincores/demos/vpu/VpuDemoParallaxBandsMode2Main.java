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
 * Demo: MODE 2 three-band horizontal parallax using COPPER mid-screen scroll changes.
 *
 * Bands:
 *  - SKY      (top): slow scroll
 *  - MOUNTAIN (mid): medium scroll
 *  - GROUND   (bottom): fast scroll
 *
 * Implementation:
 *  - We draw a static scene once into VRAM (MODE 2 indices).
 *  - Each frame we rewrite a tiny copper list:
 *      scan=0        -> set FB_BASE+XPAN for SKY
 *      scan=SKY_END  -> set FB_BASE+XPAN for MOUNTAIN
 *      scan=MID_END  -> set FB_BASE+XPAN for GROUND
 *
 * Notes:
 *  - This uses the VPU copper path (no raster IRQ needed).
 *  - FB_BASE is used only as a *horizontal* byte offset (0..BPL-1) to avoid vertical shifting.
 *  - XPAN provides fine scroll (0..3) in Mode 2.
 */
public final class VpuDemoParallaxBandsMode2Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // framebuffer
            640, 400,   // canvas
            400,        // vblankStart
            449,        // scanlinesPerFrame
            400         // cyclesPerScanline
    );

    // ---- VPU MMIO offsets ----
    private static final int REG_CTRL        = 0x0000;
    private static final int REG_MODE        = 0x0002;
    private static final int REG_FB_BASE_L   = 0x0005;
    private static final int REG_XPAN        = 0x000C;
    private static final int REG_TX_CTRL     = 0x0007;

    private static final int REG_COP_CTRL    = 0x0016;
    private static final int REG_COP_LEN_L   = 0x0017;
    private static final int REG_COP_LEN_H   = 0x0018;

    private static final int PAL_BASE        = 0x0100;
    private static final int COPPER_BASE     = 0x1000;

    // Copper flags (must match VPU)
    private static final int COP_CTRL_EN      = 0x01;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END     = 0x80;

    // MODE 2 source resolution
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL   = SRC_W / 4; // 80 bytes/row per plane (Mode-X)

    // Output height = 400 (double-scan), so yOut in [0..399], ySrc = yOut>>1 in [0..199]
    private static final int OUT_H = 400;

    // Band boundaries in OUTPUT scanlines (pick even numbers for neat ySrc boundaries)
    private static final int SKY_END_YOUT = 160;  // ySrc=80
    private static final int MID_END_YOUT = 280;  // ySrc=140

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

        stage.setTitle("VPU Demo: MODE 2 Copper 3-Band Parallax — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoParallaxBandsMode2Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // Enable VPU, MODE 2, text OFF
        vpu.writeMmio(REG_CTRL, (byte) 0x01);
        vpu.writeMmio(REG_MODE, (byte) 2);
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);

        // Palette + static scene
        installPalette(vpu);
        fillSceneMode2(vpu);

        // Enable copper (we’ll rewrite the list each frame)
        vpu.writeMmio(REG_COP_CTRL, (byte) COP_CTRL_EN);
        // 7 entries * 8 bytes = 56 bytes
        vpu.writeMmio(REG_COP_LEN_L, (byte) (56 & 0xFF));
        vpu.writeMmio(REG_COP_LEN_H, (byte) ((56 >>> 8) & 0xFF));

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int skyScrollPx = 0;
        int midScrollPx = 0;
        int grdScrollPx = 0;

        while (running.get()) {
            // Different speeds (source pixels/frame)
            skyScrollPx = (skyScrollPx + 1) % SRC_W; // slow
            midScrollPx = (midScrollPx + 2) % SRC_W; // medium
            grdScrollPx = (grdScrollPx + 4) % SRC_W; // fast

            // Write copper list for this frame
            writeCopperParallaxListMode2(vpu, skyScrollPx, midScrollPx, grdScrollPx);

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
                final String title = String.format(
                        "VPU Demo: MODE 2 Copper 3-Band Parallax — FPS %.1f  (sky=%d mid=%d grd=%d)",
                        fps, skyScrollPx, midScrollPx, grdScrollPx
                );
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Copper list: set FB_BASE (WRITE16) + XPAN (8-bit) at 3 scanline bands
    // ---------------------------------------------------------------------

    private static void writeCopperParallaxListMode2(VPU_v2 vpu, int skyPx, int midPx, int grdPx) {
        int pos = 0;

        // Band 0 (scan 0)
        pos = writeScrollAt(vpu, pos, 0, skyPx);

        // Band 1 (scan SKY_END)
        pos = writeScrollAt(vpu, pos, SKY_END_YOUT, midPx);

        // Band 2 (scan MID_END)
        pos = writeScrollAt(vpu, pos, MID_END_YOUT, grdPx);

        // END
        writeCopperEnd(vpu, pos);
    }

    private static int writeScrollAt(VPU_v2 vpu, int pos, int scan, int scrollPx) {
        // Mode 2:
        // - coarse byte scroll: 1 byte = 4 source pixels
        // - fine scroll: XPAN 0..3
        int fine = scrollPx & 3;
        int coarseBytes = (scrollPx >> 2) % BPL; // constrain to row length (no vertical shift)

        // Entry A: FB_BASE (WRITE16 to REG_FB_BASE_L)
        pos = writeCopperEntry16(vpu, pos, scan, REG_FB_BASE_L, coarseBytes);

        // Entry B: XPAN (8-bit)
        pos = writeCopperEntry8(vpu, pos, scan, REG_XPAN, fine);

        return pos;
    }

    private static int writeCopperEntry16(VPU_v2 vpu, int pos, int scan, int reg, int value16) {
        int vLo = value16 & 0xFF;
        int vHi = (value16 >>> 8) & 0xFF;
        int flags = COP_FLAG_WRITE16;

        writeMmioU16(vpu, COPPER_BASE + pos + 0, scan);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, reg);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) vLo);
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) vHi);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) flags);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
        return pos + 8;
    }

    private static int writeCopperEntry8(VPU_v2 vpu, int pos, int scan, int reg, int value8) {
        int flags = 0;

        writeMmioU16(vpu, COPPER_BASE + pos + 0, scan);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, reg);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) (value8 & 0xFF));
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) flags);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
        return pos + 8;
    }

    private static void writeCopperEnd(VPU_v2 vpu, int pos) {
        writeMmioU16(vpu, COPPER_BASE + pos + 0, 0);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, 0);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) COP_FLAG_END);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
    }

    private static void writeMmioU16(VPU_v2 vpu, int o, int v) {
        vpu.writeMmio(o,     (byte) (v & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((v >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // Static scene in VRAM (MODE 2)
    // ---------------------------------------------------------------------

    private static void fillSceneMode2(VPU_v2 vpu) {
        for (int y = 0; y < SRC_H; y++) {
            int lineBase = y * BPL;
            for (int byteX = 0; byteX < BPL; byteX++) {
                int x0 = byteX << 2;
                int ofs = lineBase + byteX;

                int i0 = sceneIndex(x0 + 0, y);
                int i1 = sceneIndex(x0 + 1, y);
                int i2 = sceneIndex(x0 + 2, y);
                int i3 = sceneIndex(x0 + 3, y);

                // Mode-X planes: pixel (x mod 4) stored in plane (x mod 4)
                vpu.writeVramPlane(0, ofs, (byte) i0);
                vpu.writeVramPlane(1, ofs, (byte) i1);
                vpu.writeVramPlane(2, ofs, (byte) i2);
                vpu.writeVramPlane(3, ofs, (byte) i3);
            }
        }
    }

    private static int sceneIndex(int x, int y) {
        // Tiny guard to reduce edge artifacts if FB_BASE shifts rows slightly
        if (x < 4) return 32;

        // Bands in SOURCE y:
        // sky: 0..79
        // mid: 80..139
        // ground: 140..199
        if (y < 80) {
            // Sky gradient + soft clouds
            int g = 32 + (y * 48) / 79; // 32..80
            int c = cloud(x, y);
            if (c > 0) return 96 + Math.min(31, c); // 96..127 (cloud ramp)
            return g;
        }

        if (y < 140) {
            // Mountains: silhouette height map, then shade
            int horizon = 108;
            int h = horizon
                    - 12
                    - (int) Math.round(10 * Math.sin((x * 2.0 * Math.PI) / 70.0))
                    - (int) Math.round(6 * Math.sin((x * 2.0 * Math.PI) / 29.0));

            if (y < h) {
                // still sky behind peaks
                int g = 42 + ((y - 70) * 22) / 69; // 42..64-ish
                return g;
            }

            int dy = y - h;
            int shade = Math.min(63, dy * 3);
            return 128 + shade; // 128..191 mountain ramp
        }

        // Ground: perspective-ish grid
        int dy = y - 140; // 0..59
        int tileX = 2 + (dy / 3);
        int tileY = 2 + (dy / 6);

        int gx = x / tileX;
        int gy = dy / tileY;
        int check = (gx + gy) & 1;

        boolean line = (x % tileX == 0) || (dy % tileY == 0);

        int shade = 63 - Math.min(63, (dy * 63) / 59);
        int base = check == 0 ? 192 : 208; // 2 ramps
        int idx = base + (shade >> 1);     // 192..223 or 208..239
        if (line) idx = 240 + (shade >> 3); // 240..247 grid highlight
        return clamp(idx, 192, 255);
    }

    private static int cloud(int x, int y) {
        // Cheap blob-ish clouds using a few sine lumps
        double a = Math.sin((x + y * 2) * 0.06);
        double b = Math.sin((x * 0.11) + (y * 0.07));
        double c = Math.sin((x * 0.04) - (y * 0.09));
        double v = (a + b + c) / 3.0; // [-1..1]
        // clouds strongest in upper third
        double band = (y < 55) ? (1.0 - (y / 55.0)) : 0.0;
        double t = (v * 0.5 + 0.5) * band;
        int level = (int) Math.round(t * 36.0) - 10; // roughly [-10..26]
        return Math.max(0, level);
    }

    // ---------------------------------------------------------------------
    // Palette
    // ---------------------------------------------------------------------

    private static void installPalette(VPU_v2 vpu) {
        // 0..15: VGA-ish base (handy for debugging)
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);

        // 32..95: sky gradient ramp (64 entries)
        for (int i = 0; i < 64; i++) {
            double t = i / 63.0;
            int r = (int) lerp(10, 90, t);
            int g = (int) lerp(30, 170, t);
            int b = (int) lerp(80, 255, t);
            writeRgb888ToPal(vpu, 32 + i, (r << 16) | (g << 8) | b);
        }

        // 96..127: clouds (32 entries)
        for (int i = 0; i < 32; i++) {
            int c = (int) lerp(210, 255, i / 31.0);
            writeRgb888ToPal(vpu, 96 + i, (c << 16) | (c << 8) | c);
        }

        // 128..191: mountains (64 entries)
        for (int i = 0; i < 64; i++) {
            double t = i / 63.0;
            int r = (int) lerp(25, 120, t);
            int g = (int) lerp(25, 110, t);
            int b = (int) lerp(30, 130, t);
            writeRgb888ToPal(vpu, 128 + i, (r << 16) | (g << 8) | b);
        }

        // 192..239: ground ramps (48 entries)
        for (int i = 0; i < 48; i++) {
            double t = i / 47.0;
            int r = (int) lerp(20, 120, t);
            int g = (int) lerp(40, 170, t);
            int b = (int) lerp(20, 80, t);
            writeRgb888ToPal(vpu, 192 + i, (r << 16) | (g << 8) | b);
        }

        // 240..255: grid highlight (16 entries)
        for (int i = 0; i < 16; i++) {
            int c = (int) lerp(110, 255, i / 15.0);
            writeRgb888ToPal(vpu, 240 + i, (c << 16) | (c << 8) | c);
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

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : Math.min(v, hi);
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