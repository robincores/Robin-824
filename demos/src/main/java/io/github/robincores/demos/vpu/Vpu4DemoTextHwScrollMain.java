package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v4 demo: hardware-scrolling text using TX_ORIGIN as a ring buffer.
 * Text + font stay in MMIO (0x2000..0x3FFF), so this is a clean "power-on terminal" demo.
 */
public final class Vpu4DemoTextHwScrollMain extends Application {

    // ---------------------------------------------------------------------
    // Display config / timing
    // ---------------------------------------------------------------------

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // internal render buffer
            640, 400,   // canvas size
            400,        // cyclesPerScanline (demo pacing)
            449,        // scanlinesPerFrame
            400         // vblankStart
    );

    private static final double TARGET_HZ = 70.0;
    private static final int FRAMES_PER_MESSAGE = 6;

    // ---------------------------------------------------------------------
    // Text mode constants
    // ---------------------------------------------------------------------

    private static final int TEXT_BASE = 0x2000;
    private static final int TEXT_COLS = 80;
    private static final int TEXT_ROWS = 25;
    private static final int TOTAL_CELLS = TEXT_COLS * TEXT_ROWS;

    // ---------------------------------------------------------------------
    // VPU v4 registers used by this demo
    // ---------------------------------------------------------------------

    private static final int REG_TX_CTRL     = 0x0006;
    private static final int REG_TX_CUR_X    = 0x0009;
    private static final int REG_TX_CUR_Y    = 0x000A;
    private static final int REG_TX_ORIGIN_L = 0x000B;
    private static final int REG_TX_ORIGIN_H = 0x000C;

    // TX_CTRL bits (v4 kept these)
    private static final int TX_EN           = 0x01;
    private static final int TX_CURSOR_EN    = 0x02;
    private static final int TX_CURSOR_BLINK = 0x08;

    // ---------------------------------------------------------------------
    // App state
    // ---------------------------------------------------------------------

    private volatile boolean running = true;
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        // --- JavaFX UI ---
        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setScene(new Scene(new StackPane(canvas), DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Text HW Scroll Demo — TX_ORIGIN ring buffer");
        stage.setResizable(false);
        stage.show();

        // --- Bus / CPU / VPU ---
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Present on FX thread (pull-based)
        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Text overlay enabled (background irrelevant; this is a text demo)
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK));

        TerminalHwScroll term = new TerminalHwScroll(vpu, 0x07, 0x00);
        seedBanner(term);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemoLoop(vpu, term, cyclesPerFrame), "vpu4-text-hwscroll-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private static void seedBanner(TerminalHwScroll term) {
        term.clear();

        term.setColor(0x0F, 0x01);
        term.println("R816 BASIC TERMINAL — VPU v4 HW SCROLL (ring buffer)");

        term.setColor(0x0E, 0x00);
        term.println("Scroll is TX_ORIGIN += 80 (no memmove).\n");

        term.setColor(0x07, 0x00);
        term.println("In v4, OAM/copper moved to banked tables, but text stayed MMIO.");
        term.println("So BASIC still boots into a terminal with zero VRAM setup.");
        term.println("");
    }

    private void runDemoLoop(VPU_v4 vpu, TerminalHwScroll term, int cyclesPerFrame) {
        int n = 1;

        final long frameNanosTarget = (long) (1_000_000_000L / TARGET_HZ);
        long next = System.nanoTime();

        while (running) {
            term.setColor(0x0A, 0x00);
            term.print("READY. ");

            term.setColor(0x0F, 0x00);
            term.print("PRINT \"HELLO\"  ");

            term.setColor(0x0E, 0x00);
            term.println("#" + n);

            term.setColor(0x07, 0x00);
            term.println("Wrap 80 cols; scroll at row 25; no copying.");

            vpu.writeMmio(REG_TX_CUR_X, (byte) term.getCursorX());
            vpu.writeMmio(REG_TX_CUR_Y, (byte) term.getCursorY());

            for (int f = 0; f < FRAMES_PER_MESSAGE && running; f++) {
                vpu.tick(cyclesPerFrame);

                next += frameNanosTarget;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else {
                    next = System.nanoTime();
                }
            }

            n++;
        }

        Platform.runLater(() -> {
            if (stage != null) stage.close();
        });
    }

    @Override
    public void stop() {
        running = false;

        if (fxTimer != null) {
            fxTimer.stop();
            fxTimer = null;
        }

        if (emuThread != null) {
            try { emuThread.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            emuThread = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * BASIC-friendly terminal that uses TX_ORIGIN as a ring-buffer scroll pointer.
     */
    private static final class TerminalHwScroll {
        private final VPU_v4 vpu;

        private int fg;                 // 0..15
        private int bg;                 // 0..15
        private int cursorX;            // 0..79
        private int cursorY;            // 0..24
        private int originCells;        // 0..1999

        TerminalHwScroll(VPU_v4 vpu, int fg, int bg) {
            this.vpu = vpu;
            setColor(fg, bg);
            setOrigin(0);
            setCursor(0, 0);
        }

        int getCursorX() { return cursorX; }
        int getCursorY() { return cursorY; }

        void setColor(int fg, int bg) {
            this.fg = fg & 0x0F;
            this.bg = bg & 0x0F;
        }

        void clear() {
            for (int cell = 0; cell < TOTAL_CELLS; cell++) {
                writeCell(cell, ' ', fg, bg);
            }
            setOrigin(0);
            setCursor(0, 0);
        }

        void print(String s) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '\n' -> newline();
                    case '\r' -> cursorX = 0;
                    default   -> putChar(ch);
                }
            }
        }

        void println(String s) {
            print(s);
            newline();
        }

        private void putChar(char ch) {
            if (cursorX >= TEXT_COLS) newline();

            int cell = screenCellIndex(cursorX, cursorY);
            writeCell(cell, ch, fg, bg);

            cursorX++;
        }

        private void newline() {
            cursorX = 0;
            cursorY++;

            if (cursorY >= TEXT_ROWS) {
                scrollUpOneRow();
                cursorY = TEXT_ROWS - 1;
            }
        }

        private void scrollUpOneRow() {
            setOrigin(originCells + TEXT_COLS);

            int bottomRowStart = screenCellIndex(0, TEXT_ROWS - 1);
            for (int x = 0; x < TEXT_COLS; x++) {
                int cell = bottomRowStart + x;
                if (cell >= TOTAL_CELLS) cell -= TOTAL_CELLS;
                writeCell(cell, ' ', fg, bg);
            }
        }

        private void setCursor(int x, int y) {
            this.cursorX = clamp(x, 0, TEXT_COLS - 1);
            this.cursorY = clamp(y, 0, TEXT_ROWS - 1);
        }

        private void setOrigin(int origin) {
            originCells = mod(origin, TOTAL_CELLS);
            vpu.writeMmio(REG_TX_ORIGIN_L, (byte) (originCells & 0xFF));
            vpu.writeMmio(REG_TX_ORIGIN_H, (byte) ((originCells >>> 8) & 0xFF));
        }

        private int screenCellIndex(int x, int y) {
            int idx = originCells + (y * TEXT_COLS) + x;
            return (idx >= TOTAL_CELLS) ? (idx % TOTAL_CELLS) : idx;
        }

        private void writeCell(int cellIndex, char ch, int fg, int bg) {
            int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
            int o = TEXT_BASE + (cellIndex << 1);

            vpu.writeMmio(o,     (byte) (ch & 0xFF));
            vpu.writeMmio(o + 1, (byte) (attr & 0xFF));
        }

        private static int clamp(int v, int lo, int hi) {
            return (v < lo) ? lo : Math.min(v, hi);
        }

        private static int mod(int v, int m) {
            int r = v % m;
            return (r < 0) ? (r + m) : r;
        }
    }
}
