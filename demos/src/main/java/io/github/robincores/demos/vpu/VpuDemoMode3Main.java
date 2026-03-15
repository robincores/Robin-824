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
 * Demo: MODE 3 (160x200 Mode-X-ish 8bpp) scaled to 640x400.
 *
 * Updated for:
 * - Mode 4 removed (REG_MODE is 0..3 only)
 * - Text exists ONLY via MMIO overlay personality (Text Buffer + Font RAM)
 * - New MMIO memory map:
 *     Text:   0x2000–0x2F9F
 *     Font:   0x3000–0x3FFF
 *     Overlay select: REG_OVL_MODE (0x001F) bit0: 0=text, 1=tiles
 *
 * Notes:
 * - Emulation runs on a background thread.
 * - VPU requires calling vpu.fxPulse() on the JavaFX thread to present updates (pull-based blit).
 *   We do that using an AnimationTimer.
 *
 * Improvements:
 *  - Smooth 240-color cyclic palette (keeps 0..15 VGA-ish for readable text)
 *  - Plasma pattern (no wrap seams)
 *  - Optional page flip using FB_BASE (2 pages fit inside each 16K plane)
 *  - HUD text includes source/output resolution + layout facts
 */
public final class VpuDemoMode3Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // VPU MMIO offsets
    private static final int REG_MODE       = 0x0002;
    private static final int REG_FB_BASE_L  = 0x0005;
    private static final int REG_FB_BASE_H  = 0x0006;
    private static final int REG_TX_CTRL    = 0x0007;
    private static final int REG_OVL_MODE   = 0x001F; // bit0: 0=text personality, 1=sprite/tile personality

    // Palette MMIO
    private static final int PAL_BASE       = 0x0100;

    // Text RAM MMIO (new map)
    private static final int TEXT_BASE      = 0x2000;
    private static final int TEXT_COLS      = 80;

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_TRANSPARENT_BG = 0x04;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;

    // FX-thread present pulse
    private AnimationTimer fxTimer;

    // ---- LUTs ----
    private static final int[] SIN8 = new int[256];   // 0..255
    private static final int[] MAP240 = new int[256]; // 0..239

    static {
        for (int i = 0; i < 256; i++) {
            double a = (i * 2.0 * Math.PI) / 256.0;
            SIN8[i] = (int) Math.round(127.5 + 127.5 * Math.sin(a));
        }
        for (int i = 0; i < 256; i++) {
            MAP240[i] = (i * 239 + 127) / 255;
        }
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VPU Demo: MODE 3 (160x200 8bpp Mode-X) — plasma + smooth palette (new MMIO map)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v2 vpu = new VPU_v2(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Drive VPU present on the JavaFX thread
        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoMode3Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // Select TEXT overlay personality explicitly.
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00);

        // MODE 3: 160x200, 8bpp across 4 byte-planes, scaled to 640x400
        vpu.writeMmio(REG_MODE, (byte) 3);

        // Text overlay on top (transparent bg so graphics show through)
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_TRANSPARENT_BG));

        // Install palette: keep 0..15 VGA-ish, set 16..255 smooth cyclic.
        installPalette(vpu);

        final int srcW = 160;
        final int srcH = 200;
        final int bpl = srcW / 4;           // 40 bytes/line/plane
        final int pageBytes = srcH * bpl;   // 8000 bytes per plane
        final int page0 = 0;
        final int page1 = pageBytes;        // fits in 16K

        // HUD
        writeText(vpu, 0, 0,
                "MODE 3: 160x200 8bpp (Mode-X byte planes)  ->  640x400 (x4 + double-scan)",
                0x0F, 0x00);
        writeText(vpu, 0, 1,
                String.format("VRAM: 4 planes x 16KB | bytes/line/plane: %d | page: %d bytes | FB_BASE: 0x%04X/0x%04X",
                        bpl, pageBytes, page0, page1),
                0x0E, 0x00);
        writeText(vpu, 0, 2,
                "Palette: VGA(0..15) + Smooth(16..255) | Plasma: sin LUT | Page flip | FPS: --",
                0x0E, 0x00);

        // Precompute base phases
        final int[] x3 = new int[srcW];
        final int[] x5 = new int[srcW];
        for (int x = 0; x < srcW; x++) {
            x3[x] = (x * 3) & 0xFF;
            x5[x] = (x * 5) & 0xFF;
        }
        final int[] y2 = new int[srcH];
        final int[] y4 = new int[srcH];
        for (int y = 0; y < srcH; y++) {
            y2[y] = (y * 2) & 0xFF;
            y4[y] = (y * 4) & 0xFF;
        }

        final int[] sxA = new int[srcW];
        final int[] sxB = new int[srcW];
        final int[] syA = new int[srcH];
        final int[] syB = new int[srcH];

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int frame = 0;
        while (running.get()) {
            int t1 = frame & 0xFF;
            int t2 = (frame * 2) & 0xFF;
            int t3 = (frame * 3) & 0xFF;

            for (int x = 0; x < srcW; x++) {
                sxA[x] = SIN8[(x3[x] + t1) & 0xFF];
                sxB[x] = SIN8[(x5[x] + t3) & 0xFF];
            }
            for (int y = 0; y < srcH; y++) {
                syA[y] = SIN8[(y2[y] + t2) & 0xFF];
                syB[y] = SIN8[(y4[y] + t1) & 0xFF];
            }

            // Page flip: draw into back page, then display it via FB_BASE.
            int fb = ((frame & 1) == 0) ? page0 : page1;

            for (int y = 0; y < srcH; y++) {
                int lineBase = fb + (y * bpl);
                int ya = syA[y];
                int yb = syB[y];

                for (int byteX = 0; byteX < bpl; byteX++) {
                    int x0 = (byteX << 2);
                    int ofs = lineBase + byteX;

                    int v0 = (sxA[x0] + sxB[x0] + ya + yb) >> 2;
                    int v1 = (sxA[x0 + 1] + sxB[x0 + 1] + ya + yb) >> 2;
                    int v2 = (sxA[x0 + 2] + sxB[x0 + 2] + ya + yb) >> 2;
                    int v3 = (sxA[x0 + 3] + sxB[x0 + 3] + ya + yb) >> 2;

                    int idx0 = 16 + MAP240[v0];
                    int idx1 = 16 + MAP240[v1];
                    int idx2 = 16 + MAP240[v2];
                    int idx3 = 16 + MAP240[v3];

                    vpu.writeVramPlane(0, ofs, (byte) idx0);
                    vpu.writeVramPlane(1, ofs, (byte) idx1);
                    vpu.writeVramPlane(2, ofs, (byte) idx2);
                    vpu.writeVramPlane(3, ofs, (byte) idx3);
                }
            }

            // Present page
            vpu.writeMmio(REG_FB_BASE_L, (byte) (fb & 0xFF));
            vpu.writeMmio(REG_FB_BASE_H, (byte) ((fb >>> 8) & 0xFF));

            // Run one frame worth of VPU time
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(chunk);
            }

            // FPS
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                writeText(vpu, 70, 2, String.format("%5.1f", fps), 0x0F, 0x00);
                fpsT0 = now;
                fpsFrames = 0;
            }

            frame++;

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    private static void installPalette(VPU_v2 vpu) {
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) {
            writeRgb888ToPal(vpu, i, rgb16[i]);
        }
        for (int i = 16; i < 256; i++) {
            double a = ((i - 16) * 2.0 * Math.PI) / 239.0;
            int r = (int) Math.round(127.5 + 127.5 * Math.sin(a));
            int g = (int) Math.round(127.5 + 127.5 * Math.sin(a + 2.0 * Math.PI / 3.0));
            int b = (int) Math.round(127.5 + 127.5 * Math.sin(a + 4.0 * Math.PI / 3.0));
            int rgb = (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            writeRgb888ToPal(vpu, i, rgb);
        }
    }

    private static int clamp8(int v) {
        return (v < 0) ? 0 : (Math.min(v, 255));
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

    private static void writeText(VPU_v2 vpu, int x, int y, String s, int fg, int bg) {
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int base = TEXT_BASE + (y * TEXT_COLS + x) * 2;
        int n = Math.min(s.length(), TEXT_COLS - x);
        for (int i = 0; i < n; i++) {
            int o = base + (i * 2);
            vpu.writeMmio(o, (byte) (s.charAt(i) & 0xFF));
            vpu.writeMmio(o + 1, (byte) attr);
        }
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