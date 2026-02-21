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
 * Manual VPU demo exercising FONT RAM writes (custom glyph upload).
 *
 * Notes:
 * - The emulation loop runs on a background thread (DO NOT block the JavaFX Application Thread),
 *   because VPU.requestBlit() uses Platform.runLater().
 * - If you run as a JPMS module (-m ...), ensure your module-info exports/opens io.github.robincores.r8 to javafx.graphics.
 */
public final class VpuDemoFontRamMain extends Application {

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
        stage.setTitle("VpuDemoFontRamMain");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        // Minimal CPU/sink wiring (VPU uses InterruptSink only for vblank IRQ).
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoFontRamMain-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame) {

// Mode 4: text-only so the font is easy to see.
        vpu.writeMmio(0x0002, (byte) 4);

// Upload a custom glyph into FONT RAM (8x16).
// FONT_BASE = 0x1400, glyph rows are at: FONT_BASE + (ch<<4) + row
        int ch = '@'; // overwrite '@' so it's easy to type/see
        uploadCustomGlyph(vpu, ch);

// Header
        writeText(vpu, 0, 0, "FONT RAM demo — custom '@' glyph uploaded at runtime", 0x0F, 0x01);
        writeText(vpu, 0, 1, "If you see a smiley/face made of pixels, FONT RAM writes work.", 0x0E, 0x01);

// Fill screen with '@'
        for (int y = 4; y < 22; y++) {
            for (int x = 0; x < 80; x++) {
                putChar(vpu, x, y, (char) ch, 0x0E, 0x00);
            }
        }

        int frame = 0;
        while (running.get()) {
            // Animate colors (swap fg colors every few frames)
            int fg = 1 + ((frame >> 3) & 0x0F);
            for (int y = 4; y < 22; y++) {
                for (int x = 0; x < 80; x++) {
                    putChar(vpu, x, y, (char) ch, fg, 0x00);
                }
            }

            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) vpu.tick(chunk);

            frame++;
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
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

    private static void uploadCustomGlyph(VPU vpu, int ch) {
        // A simple 8x16 "face" pattern.
        // Each byte is 8 pixels, bit7 is leftmost.
        int[] rows = new int[] {
                0b00111100,
                0b01000010,
                0b10100101,
                0b10000001,
                0b10100101,
                0b10011001,
                0b01000010,
                0b00111100,
                0b00000000,
                0b00111100,
                0b01000010,
                0b10000001,
                0b10100101,
                0b10000001,
                0b01000010,
                0b00111100
        };

        int base = 0x1400 + ((ch & 0xFF) << 4);
        for (int r = 0; r < 16; r++) {
            vpu.writeMmio(base + r, (byte) (rows[r] & 0xFF));
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
