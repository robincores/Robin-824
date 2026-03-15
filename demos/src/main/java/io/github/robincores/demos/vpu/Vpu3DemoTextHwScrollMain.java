package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v3;
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
 * VPU v3 demo: hardware-scrolling text using {@code TX_ORIGIN} as a ring buffer (BASIC-friendly).
 *
 * <p>Conceptually this behaves like many classic terminals:
 * printing a line via {@code println()} advances to the next row, so the bottom row is often
 * the "fresh" empty input line (cursor sits there). That can look like "24 lines of text"
 * on a 25-row display.</p>
 *
 * <p>Notes:
 * <ul>
 *   <li>Text overlay remains in MMIO: TEXT 0x2000.., FONT 0x3000..</li>
 * </ul>
 * </p>
 */
public final class Vpu3DemoTextHwScrollMain extends Application {

    // ---------------------------------------------------------------------
    // Display config / timing
    // ---------------------------------------------------------------------

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // internal render buffer (what VPU produces)
            640, 400,   // canvas size (presentation)
            400,        // vblankStart
            449,        // scanlinesPerFrame (inclusive end is handled by VPU; keep your chosen value)
            400         // active height
    );

    /** Demo cadence: VPU tick rate is driven by cycles; pacing is purely for a stable visual. */
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
    // VPU registers used by this demo (v3)
    // ---------------------------------------------------------------------

    private static final int REG_MODE     = 0x0002; // bit0 RES: 0=HIRES, 1=LORES
    private static final int REG_TX_CTRL  = 0x0007;
    private static final int REG_TX_CUR_X = 0x0008;
    private static final int REG_TX_CUR_Y = 0x0009;

    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;

    // TX_CTRL bits
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
        stage.setTitle("VPU v3 Text HW Scroll Demo — TX_ORIGIN ring buffer");
        stage.setResizable(false);
        stage.show();

        // --- Bus / CPU / VPU ---
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v3 vpu = new VPU_v3(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Present on FX thread (pull-based)
        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Configure VPU: HIRES underlay + text overlay enabled
        vpu.writeMmio(REG_MODE, (byte) 0x00);
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK));

        // Terminal helper (ring-buffer scroll)
        TerminalHwScroll term = new TerminalHwScroll(vpu, 0x07, 0x00);
        seedBanner(term);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        // Emulation/demo loop on background thread
        emuThread = new Thread(() -> runDemoLoop(vpu, term, cyclesPerFrame), "vpu3-text-hwscroll-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private static void seedBanner(TerminalHwScroll term) {
        term.clear();

        term.setColor(0x0F, 0x01);
        term.println("R816 BASIC TERMINAL — HARDWARE SCROLL (ring buffer)");

        term.setColor(0x0E, 0x00);
        term.println("Scroll is TX_ORIGIN += 80 (no memmove).");
        term.println("VPU v3: bitplanes + layers, text is always MMIO overlay.");
        term.println("");
    }

    private void runDemoLoop(VPU_v3 vpu, TerminalHwScroll term, int cyclesPerFrame) {
        int n = 1;

        final long frameNanosTarget = (long) (1_000_000_000L / TARGET_HZ);
        long next = System.nanoTime();

        while (running) {
            // Print a couple of lines (classic REPL feel: last line becomes the "fresh" input line)
            term.setColor(0x0A, 0x00);
            term.print("READY. ");

            term.setColor(0x0F, 0x00);
            term.print("PRINT \"HELLO\"  ");

            term.setColor(0x0E, 0x00);
            term.println("#" + n);

            term.setColor(0x07, 0x00);
            term.println("Wrap 80 cols; scroll at row 25; no copying.");

            // Cursor regs are screen-relative (0..79, 0..24)
            writeCursorRegs(vpu, term);

            // Run a few frames at stable cadence
            for (int f = 0; f < FRAMES_PER_MESSAGE && running; f++) {
                vpu.tick(cyclesPerFrame);

                next += frameNanosTarget;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else {
                    // We fell behind; reset phase so we don't spiral
                    next = System.nanoTime();
                }
            }

            n++;
        }

        Platform.runLater(() -> {
            if (stage != null) stage.close();
        });
    }

    private static void writeCursorRegs(VPU_v3 vpu, TerminalHwScroll term) {
        vpu.writeMmio(REG_TX_CUR_X, (byte) term.getCursorX());
        vpu.writeMmio(REG_TX_CUR_Y, (byte) term.getCursorY());
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
     * BASIC-friendly terminal that uses {@code TX_ORIGIN} as a ring-buffer scroll pointer.
     *
     * <p>Text memory is a circular buffer of {@code 80x25} cells. Scrolling is:
     * <pre>origin = (origin + 80) % 2000</pre>
     * Then the bottom row (relative to the new origin) is cleared.</p>
     */
    private static final class TerminalHwScroll {
        private final VPU_v3 vpu;

        private int fg;                 // 0..15
        private int bg;                 // 0..15
        private int cursorX;            // 0..79
        private int cursorY;            // 0..24
        private int originCells;        // 0..1999

        TerminalHwScroll(VPU_v3 vpu, int fg, int bg) {
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

        /**
         * Hardware scroll: advance origin by one row and clear the new bottom row.
         */
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
            writeOriginRegs();
        }

        private void writeOriginRegs() {
            vpu.writeMmio(REG_TX_ORIGIN_L, (byte) (originCells & 0xFF));
            vpu.writeMmio(REG_TX_ORIGIN_H, (byte) ((originCells >>> 8) & 0xFF));
        }

        /**
         * Convert screen (x,y) to a physical cell index in the ring buffer.
         */
        private int screenCellIndex(int x, int y) {
            int idx = originCells + (y * TEXT_COLS) + x;
            return (idx >= TOTAL_CELLS) ? (idx % TOTAL_CELLS) : idx;
        }

        private void writeCell(int cellIndex, char ch, int fg, int bg) {
            int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
            int o = TEXT_BASE + (cellIndex << 1);

            // v3 text RAM is byte-addressed (char, attr)
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