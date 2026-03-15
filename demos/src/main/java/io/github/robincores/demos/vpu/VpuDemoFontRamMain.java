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
 * Manual VPU demo exercising FONT RAM writes (custom glyph upload).
 *
 * Updated for:
 * - Mode 4 removed (REG_MODE is 0..3 only)
 * - Text is ONLY via MMIO overlay personality (Text Buffer + Font RAM)
 * - New MMIO memory map:
 *   - OAM:   0x0300–0x06FF (not used here)
 *   - Copper 0x1000–0x1FFF
 *   - Text:  0x2000–0x2F9F (80×25×2)
 *   - Font:  0x3000–0x3FFF (8×16×256)
 *   - Overlay personality select: REG_OVL_MODE (0x001F), bit0: 0=text, 1=tiles
 *
 * Notes:
 * - The emulation loop runs on a background thread (DO NOT block the JavaFX Application Thread).
 * - VPU requires calling vpu.fxPulse() on the JavaFX thread to present updates (pull-based blit).
 *   We do that using an AnimationTimer.
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

    private AnimationTimer fxTimer;

    // --- New MMIO addresses (final spec) ---
    private static final int REG_MODE     = 0x0002; // 0..3
    private static final int REG_TX_CTRL  = 0x0007; // bit0 TX_EN
    private static final int REG_OVL_MODE = 0x001F; // bit0: 0=text, 1=sprites/tiles

    private static final int TEXT_COLS = 80;
    private static final int TEXT_ROWS = 25;

    private static final int TEXT_BASE = 0x2000; // 80×25×2 = 4000 bytes
    private static final int FONT_BASE = 0x3000; // 4096 bytes (8×16×256)

    // TX_CTRL bits (keep in sync with VPU)
    private static final int TX_EN = 0x01;
    // If you later want cursor etc, add those bits here.

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VpuDemoFontRamMain (new MMIO map, no Mode 4)");
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

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoFontRamMain-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // Select TEXT overlay personality explicitly.
        // REG_OVL_MODE bit0: 0=text, 1=tiles
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00);

        // Choose any graphics mode 0..3. Background VRAM defaults to 0 so you get a black backdrop.
        vpu.writeMmio(REG_MODE, (byte) 1); // MODE 1 is a nice default (320×200 4bpp scaled)

        // Enable text overlay (now the ONLY way to show text; no Mode 4 exists).
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN));

        // Upload a custom glyph into FONT RAM (8×16).
        int ch = '@'; // overwrite '@' so it's easy to type/see
        uploadCustomGlyph(vpu, ch);

        // Header
        writeText(vpu, 0, 0, "FONT RAM demo — custom '@' glyph uploaded at runtime", 0x0F, 0x01);
        writeText(vpu, 0, 1, "If you see a smiley/face made of pixels, FONT RAM writes work.", 0x0E, 0x01);
        writeText(vpu, 0, 2, "Text lives at 0x2000; Font lives at 0x3000 (new MMIO map).", 0x0B, 0x01);

        // Fill screen with '@'
        for (int y = 4; y < 22; y++) {
            for (int x = 0; x < TEXT_COLS; x++) {
                putChar(vpu, x, y, (char) ch, 0x0E, 0x00);
            }
        }

        int frame = 0;
        while (running.get()) {
            // Animate colors (swap fg colors every few frames)
            int fg = 1 + ((frame >> 3) & 0x0F);
            for (int y = 4; y < 22; y++) {
                for (int x = 0; x < TEXT_COLS; x++) {
                    putChar(vpu, x, y, (char) ch, fg, 0x00);
                }
            }

            // Run roughly one frame worth of VPU time
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) vpu.tick(chunk);

            frame++;
            try { Thread.sleep(60); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    private static void putChar(VPU_v2 vpu, int x, int y, char ch, int fg, int bg) {
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int o = TEXT_BASE + ((y * TEXT_COLS + x) * 2);
        vpu.writeMmio(o, (byte) (ch & 0xFF));
        vpu.writeMmio(o + 1, (byte) attr);
    }

    private static void writeText(VPU_v2 vpu, int x, int y, String s, int fg, int bg) {
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int base = TEXT_BASE + ((y * TEXT_COLS + x) * 2);
        int n = Math.min(s.length(), TEXT_COLS - x);
        for (int i = 0; i < n; i++) {
            int o = base + (i * 2);
            vpu.writeMmio(o, (byte) (s.charAt(i) & 0xFF));
            vpu.writeMmio(o + 1, (byte) attr);
        }
    }

    private static void uploadCustomGlyph(VPU_v2 vpu, int ch) {
        // A simple 8×16 "face" pattern.
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

        int base = FONT_BASE + ((ch & 0xFF) << 4);
        for (int r = 0; r < 16; r++) {
            vpu.writeMmio(base + r, (byte) (rows[r] & 0xFF));
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