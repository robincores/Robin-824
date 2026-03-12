package io.github.robincores.r8;

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
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * Manual VPU demo (UPDATED): Text overlay exercising text RAM + cursor.
 *
 * Updated for NEW VPU:
 * - Mode 4 removed (REG_MODE is 0..3 only)
 * - Text exists ONLY via MMIO overlay personality (Text Buffer + Font RAM)
 * - New MMIO memory map:
 *     Text RAM:   0x2000–0x2F9F
 *     Font RAM:   0x3000–0x3FFF
 *     Overlay select: REG_OVL_MODE (0x001F) bit0: 0=text, 1=tiles
 *
 * Notes:
 * - Emulation runs on a background thread (DO NOT block the JavaFX Application Thread).
 * - VPU requires calling vpu.fxPulse() on the JavaFX thread to present updates (pull-based blit).
 *   We do that using an AnimationTimer.
 * - This demo uses a stable cadence (parkNanos) for smoother motion than Thread.sleep().
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

    // FX-thread present pulse
    private AnimationTimer fxTimer;

    // ---- NEW MMIO constants ----
    private static final int REG_MODE      = 0x0002; // 0..3
    private static final int REG_TX_CTRL   = 0x0007;
    private static final int REG_TX_CUR_X  = 0x0008;
    private static final int REG_TX_CUR_Y  = 0x0009;
    private static final int REG_OVL_MODE  = 0x001F; // bit0: 0=text personality, 1=tiles

    private static final int TEXT_BASE = 0x2000; // NEW MAP
    private static final int TEXT_COLS = 80;
    private static final int TEXT_ROWS = 25;

    // TX_CTRL bits (must match VPU)
    private static final int TX_EN          = 0x01;
    private static final int TX_CURSOR_EN   = 0x02;
    private static final int TX_CURSOR_BLINK= 0x08;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VpuDemoTextOnlyMain (overlay text, new MMIO map)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        // Minimal CPU/sink wiring (VPU uses InterruptSink only for IRQs).
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        VPU_v2 vpu = new VPU_v2(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Drive VPU present on the JavaFX thread
        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoTextOnlyMain-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // Select TEXT overlay personality (Text Buffer + Font RAM)
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00);

        // Choose any graphics mode (0..3). We'll just use mode 0 (background).
        vpu.writeMmio(REG_MODE, (byte) 0);

        // Enable text + cursor + blink
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK));

        writeText(vpu, 0, 0, "TEXT OVERLAY (80x25, 8x16) — typewriter demo (Mode 4 removed)", 0x0F, 0x01);
        writeText(vpu, 0, 1, "Close window to stop.", 0x0E, 0x01);

        int x = 0, y = 3;
        int frame = 0;

        // smoother pacing than Thread.sleep()
        final long frameNanosTarget = (long) (1_000_000_000L / 70.0); // ~70Hz
        long next = System.nanoTime();

        while (running.get()) {
            // Write one character every few frames
            if ((frame % 3) == 0) {
                char ch = (char) ('A' + ((frame / 3) % 26));
                putChar(vpu, x, y, ch, 0x0A, 0x00); // green on black
                x++;
                if (x >= TEXT_COLS) { x = 0; y++; }
                if (y >= TEXT_ROWS) { y = 3; clearArea(vpu, 0, 3, TEXT_COLS, 22, 0x00, 0x00); }

                // Move cursor (screen coords)
                vpu.writeMmio(REG_TX_CUR_X, (byte) x);
                vpu.writeMmio(REG_TX_CUR_Y, (byte) y);
            }

            // Run one frame
            vpu.tick(cyclesPerFrame);

            frame++;

            // pace
            next += frameNanosTarget;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else next = System.nanoTime();
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    private static void putChar(VPU_v2 vpu, int x, int y, char ch, int fg, int bg) {
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int o = TEXT_BASE + (y * TEXT_COLS + x) * 2;
        vpu.writeMmio(o, (byte) (ch & 0xFF));
        vpu.writeMmio(o + 1, (byte) attr);
    }

    private static void clearArea(VPU_v2 vpu, int x, int y, int w, int h, int fg, int bg) {
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                putChar(vpu, x + xx, y + yy, ' ', fg, bg);
            }
        }
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