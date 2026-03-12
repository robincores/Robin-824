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
 * Manual VPU demo: VGA-style HARDWARE scrolling text (CRTC Start Address equivalent).
 *
 * Updated for NEW VPU:
 * - Mode 4 removed (REG_MODE is 0..3 only)
 * - Text exists ONLY via MMIO overlay personality (Text Buffer + Font RAM)
 * - New MMIO memory map:
 *     Text RAM:   0x2000–0x2F9F
 *     Font RAM:   0x3000–0x3FFF
 *     Copper RAM: 0x1000–0x1FFF
 *     Overlay select: REG_OVL_MODE (0x001F) bit0: 0=text, 1=tiles
 *
 * The demo keeps text RAM as a ring buffer (80x25), scrolling by advancing TX_ORIGIN by 80 cells.
 * This is the BASIC-friendly approach: cheap scroll, no memmove.
 *
 * Smoothness improvements:
 * - time-paced loop (stable cadence, less jitter)
 * - vpu.tick(cyclesPerFrame) in one call for consistent frame stepping
 */
public final class VpuDemoTextHwScrollMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private static final int TEXT_BASE = 0x2000; // NEW MAP
    private static final int TEXT_COLS = 80;
    private static final int TEXT_ROWS = 25;
    private static final int TOTAL_CELLS = TEXT_COLS * TEXT_ROWS;

    // VPU regs
    private static final int REG_MODE        = 0x0002; // 0..3
    private static final int REG_TX_CTRL     = 0x0007;
    private static final int REG_TX_CUR_X    = 0x0008;
    private static final int REG_TX_CUR_Y    = 0x0009;

    // Hardware scroll origin (cell offset)
    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;

    private static final int REG_OVL_MODE    = 0x001F; // bit0: 0=text personality, 1=tiles

    // TX_CTRL bits
    private static final int TX_EN           = 0x01;
    private static final int TX_CURSOR_EN    = 0x02;
    private static final int TX_CURSOR_BLINK = 0x08;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;

    private AnimationTimer fxTimer;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VPU Text HW Scroll Demo (VGA-style Start Address) — new MMIO map");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v2 vpu = new VPU_v2(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // FX-thread present pulse
        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Select TEXT overlay personality explicitly.
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00);

        // Pick any graphics mode (0..3). Text overlay will draw on top.
        // Use mode 0 for a clean background.
        vpu.writeMmio(REG_MODE, (byte) 0);

        // Text visible; cursor on
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK));

        TerminalHwScroll term = new TerminalHwScroll(vpu, 0x07, 0x00);
        term.clear();
        term.setColor(0x0F, 0x01);
        term.println("R816 BASIC TERMINAL — HARDWARE SCROLL (ring buffer)");
        term.setColor(0x0E, 0x00);
        term.println("Scroll is done by TX_ORIGIN += 80 (no memmove).");
        term.println("Close window to stop.");
        term.println("");

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, term, cyclesPerFrame), "vpu-text-hwscroll-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, TerminalHwScroll term, int cyclesPerFrame) {
        int n = 1;

        // Smooth pacing target
        final long frameNanosTarget = (long) (1_000_000_000L / 70.0); // ~70Hz
        long next = System.nanoTime();

        while (running.get()) {
            term.setColor(0x0A, 0x00);
            term.print("READY. ");
            term.setColor(0x0F, 0x00);
            term.print("PRINT \"HELLO\"  ");
            term.setColor(0x0E, 0x00);
            term.println("#" + n);

            term.setColor(0x07, 0x00);
            term.println("Line wraps at 80 cols and scrolls at row 25 without copying memory.");

            // Update cursor regs (screen coords)
            vpu.writeMmio(REG_TX_CUR_X, (byte) term.curX);
            vpu.writeMmio(REG_TX_CUR_Y, (byte) term.curY);

            // Run a few frames with stable cadence
            for (int f = 0; f < 6 && running.get(); f++) {
                vpu.tick(cyclesPerFrame);

                next += frameNanosTarget;
                long sleep = next - System.nanoTime();
                if (sleep > 0) LockSupport.parkNanos(sleep);
                else next = System.nanoTime();
            }

            n++;
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
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

    /**
     * BASIC-friendly terminal that uses TX_ORIGIN as a ring buffer scroll pointer.
     */
    private static final class TerminalHwScroll {
        private final VPU_v2 vpu;

        private int fg;
        private int bg;

        private int curX = 0;
        private int curY = 0;

        // origin in cells (0..1999)
        private int origin = 0;

        TerminalHwScroll(VPU_v2 vpu, int fg, int bg) {
            this.vpu = vpu;
            this.fg = fg & 0x0F;
            this.bg = bg & 0x0F;
            writeOrigin();
        }

        void setColor(int fg, int bg) {
            this.fg = fg & 0x0F;
            this.bg = bg & 0x0F;
        }

        void clear() {
            for (int cell = 0; cell < TOTAL_CELLS; cell++) {
                writeCell(cell, ' ', fg, bg);
            }
            origin = 0;
            curX = 0;
            curY = 0;
            writeOrigin();
        }

        void print(String s) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\n') newline();
                else if (ch == '\r') curX = 0;
                else putChar(ch);
            }
        }

        void println(String s) { print(s); newline(); }

        private void putChar(char ch) {
            if (curX >= TEXT_COLS) newline();

            int screenCell = origin + curY * TEXT_COLS + curX;
            screenCell %= TOTAL_CELLS;

            writeCell(screenCell, ch, fg, bg);
            curX++;
        }

        private void newline() {
            curX = 0;
            curY++;
            if (curY >= TEXT_ROWS) {
                scrollUp();
                curY = TEXT_ROWS - 1;
            }
        }

        /**
         * Hardware scroll: advance origin by one row and clear the new bottom row.
         */
        private void scrollUp() {
            origin += TEXT_COLS;
            origin %= TOTAL_CELLS;
            writeOrigin();

            int bottom = origin + (TEXT_ROWS - 1) * TEXT_COLS;
            bottom %= TOTAL_CELLS;

            for (int x = 0; x < TEXT_COLS; x++) {
                int cell = bottom + x;
                if (cell >= TOTAL_CELLS) cell -= TOTAL_CELLS;
                writeCell(cell, ' ', fg, bg);
            }
        }

        private void writeOrigin() {
            vpu.writeMmio(REG_TX_ORIGIN_L, (byte) (origin & 0xFF));
            vpu.writeMmio(REG_TX_ORIGIN_H, (byte) ((origin >>> 8) & 0xFF));
        }

        private void writeCell(int cellIndex, char ch, int fg, int bg) {
            int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
            int o = TEXT_BASE + (cellIndex << 1);
            vpu.writeMmio(o, (byte) (ch & 0xFF));
            vpu.writeMmio(o + 1, (byte) attr);
        }
    }
}