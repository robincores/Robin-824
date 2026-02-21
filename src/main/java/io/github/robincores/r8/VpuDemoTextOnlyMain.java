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
 * Manual VPU demo for MODE 4 (text-only) exercising text RAM + cursor.
 *
 * Notes:
 * - The emulation loop runs on a background thread (DO NOT block the JavaFX Application Thread),
 *   because VPU.requestBlit() uses Platform.runLater().
 * - If you run as a JPMS module (-m ...), ensure your module-info exports/opens io.github.robincores.r8 to javafx.graphics.
 */
public final class VpuDemoTextOnlyMain extends Application {

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
        stage.setTitle("VpuDemoTextOnlyMain");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        // Minimal CPU/sink wiring (VPU uses InterruptSink only for vblank IRQ).
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoTextOnlyMain-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame) {

// Mode 4: text-only
        vpu.writeMmio(0x0002, (byte) 4);

// Enable cursor + blink (TX_CTRL bits are used in mode 4 too)
        vpu.writeMmio(0x0007, (byte) (0x02 | 0x08)); // CURSOR_EN + CURSOR_BLINK

        writeText(vpu, 0, 0, "TEXT MODE (80x25, 8x16) — typewriter demo", 0x0F, 0x01);
        writeText(vpu, 0, 1, "Close window to stop.", 0x0E, 0x01);

        int x = 0, y = 3;
        int frame = 0;

        while (running.get()) {
            // Write one character every few frames
            if ((frame % 3) == 0) {
                char ch = (char) ('A' + ((frame / 3) % 26));
                putChar(vpu, x, y, ch, 0x0A, 0x00); // green on black
                x++;
                if (x >= 80) { x = 0; y++; }
                if (y >= 25) { y = 3; clearArea(vpu, 0, 3, 80, 22, 0x00, 0x00); }

                // Move cursor
                vpu.writeMmio(0x0008, (byte) x); // CUR_X
                vpu.writeMmio(0x0009, (byte) y); // CUR_Y
            }

            // Run one frame
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) vpu.tick(chunk);

            frame++;
            try { Thread.sleep(14); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });

    }


    private static void putChar(VPU vpu, int x, int y, char ch, int fg, int bg) {
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int cols = 80;
        int o = 0x0300 + (y * cols + x) * 2;
        vpu.writeMmio(o, (byte) (ch & 0xFF));
        vpu.writeMmio(o + 1, (byte) attr);
    }

    private static void clearArea(VPU vpu, int x, int y, int w, int h, int fg, int bg) {
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                putChar(vpu, x + xx, y + yy, ' ', fg, bg);
            }
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
