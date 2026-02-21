package io.github.robincores.r8;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * Manual VPU demo for MODE 0 (640x200 bitplane 4bpp) doubled to 640x400.
 *
 * IMPORTANT:
 * - The emulation loop MUST NOT run on the JavaFX Application Thread, because VPU.requestBlit()
 *   uses Platform.runLater(). If you block the FX thread, you will see a black window.
 *
 * Demo:
 * - Mode 0 color bars (16 colors) in true 4bpp bitplane format
 * - Moving 8-pixel highlight stripe
 * - Text overlay title (transparent bg)
 */
public final class VpuDemoMode0Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("R816 VPU Demo (Mode 0 bitplane bars)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        // Minimal CPU/sink wiring (VPU uses InterruptSink only for vblank IRQ).
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Mode 0 (640x200 4bpp bitplane) doubled to 640x400.
        vpu.writeMmio(0x0002, (byte) 0);

        // Text overlay on (transparent bg so graphics show through).
        vpu.writeMmio(0x0007, (byte) (0x01 | 0x02 | 0x04 | 0x08));
        writeText(vpu, 0, 0, "R816 VPU DEMO  (Mode 0 bitplane)", 0x0F, 0x00);
        writeText(vpu, 0, 1, "Close window to stop.", 0x0E, 0x00);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        // Draw the bars once as the base picture.
        drawVerticalBarsMode0(vpu);

        // Run emulation in a background thread (keeps FX thread responsive).
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu-demo-mode0");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame) {
        int frame = 0;

        while (running.get()) {
            int x = (frame * 2) % 640;

            // Draw highlight stripe (aligned to 8px so we can write whole bytes).
            drawVerticalStripeMode0(vpu, x, 0x0F);

            // Advance one frame worth of VPU time.
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(Math.min(chunk, cyclesPerFrame - done));
            }

            // Restore by redrawing the base bars (demo-simple; later you can do XOR/ROP).
            drawVerticalBarsMode0(vpu);

            frame++;

            // ~70Hz target => ~14ms.
            try {
                Thread.sleep(14);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Platform.runLater(() -> {
            // Nothing to do: user closes stage. But if loop ends, exit app.
            Platform.exit();
        });
    }

    private static void drawVerticalBarsMode0(VPU vpu) {
        // Mode 0: 640x200, 4bpp bitplane, bytes per line per plane = 80
        final int bpl = 80;
        final int h = 200;

        // 16 bars, 40 pixels each (640/16)
        for (int bar = 0; bar < 16; bar++) {
            int xStart = bar * 40;
            int xEnd = xStart + 40;
            int color = bar & 0x0F;

            // Precompute plane bytes: full-on or full-off for all 8 pixels in the byte.
            byte p0 = (byte) (((color & 0x1) != 0) ? 0xFF : 0x00);
            byte p1 = (byte) (((color & 0x2) != 0) ? 0xFF : 0x00);
            byte p2 = (byte) (((color & 0x4) != 0) ? 0xFF : 0x00);
            byte p3 = (byte) (((color & 0x8) != 0) ? 0xFF : 0x00);

            for (int y = 0; y < h; y++) {
                int lineBase = y * bpl;

                for (int x = xStart; x < xEnd; x += 8) {
                    int ofs = lineBase + (x >> 3);

                    vpu.writeVramPlane(0, ofs, p0);
                    vpu.writeVramPlane(1, ofs, p1);
                    vpu.writeVramPlane(2, ofs, p2);
                    vpu.writeVramPlane(3, ofs, p3);
                }
            }
        }
    }

    private static void drawVerticalStripeMode0(VPU vpu, int x, int color) {
        // Draw an 8-pixel wide stripe aligned to byte boundary for simplicity.
        int xAligned = (x & ~7);
        int bx = xAligned >> 3;

        final int bpl = 80;
        final int h = 200;

        byte p0 = (byte) (((color & 0x1) != 0) ? 0xFF : 0x00);
        byte p1 = (byte) (((color & 0x2) != 0) ? 0xFF : 0x00);
        byte p2 = (byte) (((color & 0x4) != 0) ? 0xFF : 0x00);
        byte p3 = (byte) (((color & 0x8) != 0) ? 0xFF : 0x00);

        for (int y = 0; y < h; y++) {
            int ofs = y * bpl + bx;
            vpu.writeVramPlane(0, ofs, p0);
            vpu.writeVramPlane(1, ofs, p1);
            vpu.writeVramPlane(2, ofs, p2);
            vpu.writeVramPlane(3, ofs, p3);
        }
    }

    private static void writeText(VPU vpu, int x, int y, String s, int fg, int bg) {
        // TEXT_BASE = 0x0300, 80x25, 2 bytes per cell: [ch, attr], attr=(bg<<4)|fg
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int cols = 80;
        int base = 0x0300 + (y * cols + x) * 2;
        for (int i = 0; i < s.length(); i++) {
            int o = base + (i * 2);
            vpu.writeMmio(o, (byte) (s.charAt(i) & 0xFF));
            vpu.writeMmio(o + 1, (byte) attr);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (emuThread != null) {
            try {
                emuThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
