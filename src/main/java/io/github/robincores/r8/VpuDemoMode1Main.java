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
 * Manual VPU demo for MODE 1 (320x200 4bpp bitplane) scaled to 640x400.
 *
 * Notes:
 * - The emulation loop runs on a background thread (DO NOT block the JavaFX Application Thread),
 *   because VPU.requestBlit() uses Platform.runLater().
 * - If you run as a JPMS module (-m ...), ensure your module-info exports/opens io.github.robincores.r8 to javafx.graphics.
 */
public final class VpuDemoMode1Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VpuDemoMode1Main");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        // Minimal CPU/sink wiring (VPU uses InterruptSink only for vblank IRQ).
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoMode1Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame) {

// Mode 1: 320x200 4bpp bitplane, scaled to 640x400 (2x X + double-scan Y).
        vpu.writeMmio(0x0002, (byte) 1);

// Text overlay on top (transparent bg so graphics show through).
        vpu.writeMmio(0x0007, (byte) (0x01 | 0x04)); // TX_EN + TRANSPARENT_BG
        writeText(vpu, 0, 0, "MODE 1: 320x200 bitplane (4bpp) scaled to 640x400", 0x0F, 0x00);
        writeText(vpu, 0, 1, "16-color bars + moving stripe (close window to stop)", 0x0E, 0x00);

        drawBarsMode1(vpu);

        int frame = 0;
        while (running.get()) {
            int x = (frame * 3) % 320;         // source-space X
            drawStripeMode1(vpu, x, 0x0F);      // bright stripe

            // Run one frame worth of VPU time
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) vpu.tick(chunk);

            // Restore by redrawing bars (simple demo)
            drawBarsMode1(vpu);

            frame++;
            try { Thread.sleep(14); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });

    }


    private static void drawBarsMode1(VPU vpu) {
        // Mode 1: 320x200, bytes per line per plane = 40 (320/8)
        final int bpl = 40;
        final int h = 200;

        // 16 bars, 20 pixels each (320/16)
        for (int bar = 0; bar < 16; bar++) {
            int xStart = bar * 20;
            int xEnd = xStart + 20;
            int color = bar & 0x0F;

            for (int y = 0; y < h; y++) {
                int lineBase = y * bpl;

                // Iterate 8-pixel chunks (byte-aligned)
                for (int x = xStart; x < xEnd; x += 8) {
                    int bx = (x >> 3);
                    int ofs = lineBase + bx;

                    vpu.writeVramPlane(0, ofs, (byte) (((color & 0x1) != 0) ? 0xFF : 0x00));
                    vpu.writeVramPlane(1, ofs, (byte) (((color & 0x2) != 0) ? 0xFF : 0x00));
                    vpu.writeVramPlane(2, ofs, (byte) (((color & 0x4) != 0) ? 0xFF : 0x00));
                    vpu.writeVramPlane(3, ofs, (byte) (((color & 0x8) != 0) ? 0xFF : 0x00));
                }
            }
        }
    }

    private static void drawStripeMode1(VPU vpu, int x, int color) {
        // 8-pixel wide stripe aligned to byte boundary
        int xAligned = (x & ~7);
        int bx = xAligned >> 3;

        final int bpl = 40;
        final int h = 200;

        for (int y = 0; y < h; y++) {
            int ofs = y * bpl + bx;
            vpu.writeVramPlane(0, ofs, (byte) (((color & 0x1) != 0) ? 0xFF : 0x00));
            vpu.writeVramPlane(1, ofs, (byte) (((color & 0x2) != 0) ? 0xFF : 0x00));
            vpu.writeVramPlane(2, ofs, (byte) (((color & 0x4) != 0) ? 0xFF : 0x00));
            vpu.writeVramPlane(3, ofs, (byte) (((color & 0x8) != 0) ? 0xFF : 0x00));
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
