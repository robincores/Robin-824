package io.github.robincores.r8;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v2;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo: Text overlay with VGA-like hardware scrolling + copper palette gradient.
 *
 * Updated for NEW VPU:
 * - Mode 4 removed (REG_MODE is 0..3 only)
 * - Text exists ONLY as the overlay personality in MMIO (Text Buffer + Font RAM)
 * - New MMIO memory map:
 *     Copper RAM: 0x1000–0x1FFF
 *     Text RAM:   0x2000–0x2F9F
 *     Font RAM:   0x3000–0x3FFF
 *     Overlay select: REG_OVL_MODE (0x001F) bit0: 0=text, 1=tiles
 *
 * Smoothness improvements:
 * - Time-based fine scroll (pixels/sec) so speed is stable even if FPS jitters.
 * - Coarse scroll (TXCMD_SCROLL_UP) is issued strictly BETWEEN frames (no “jump”).
 * - Bottom-row updates write TEXT RAM directly (screen-relative) to avoid TX_PORT wrapping.
 *
 * Copper:
 * - Copper list updates palette[1] (RGB565) per scanline to create a vertical gradient behind text.
 */
public final class VpuDemoTextCopperMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // --- MMIO register offsets (must match VPU) ---
    private static final int REG_MODE        = 0x0002; // 0..3
    private static final int REG_TX_CTRL     = 0x0007;
    private static final int REG_TX_CUR_X    = 0x0008;
    private static final int REG_TX_CUR_Y    = 0x0009;

    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;
    private static final int REG_TX_FINE_Y   = 0x0015;

    private static final int REG_COP_CTRL    = 0x0016;
    private static final int REG_COP_LEN_L   = 0x0017;
    private static final int REG_COP_LEN_H   = 0x0018;

    private static final int REG_TX_CMD      = 0x0019;
    private static final int REG_TX_ATTR     = 0x001A;
    private static final int REG_TX_PORT     = 0x001B;

    private static final int REG_OVL_MODE    = 0x001F; // bit0: 0=text, 1=tiles

    // Text RAM mapping (new map)
    private static final int TEXT_BASE       = 0x2000;
    private static final int TEXT_COLS       = 80;
    private static final int TEXT_ROWS       = 25;
    private static final int TEXT_CELLS      = TEXT_COLS * TEXT_ROWS; // 2000

    // Copper RAM mapping (new map)
    private static final int COPPER_BASE     = 0x1000;
    private static final int COPPER_STRIDE   = 8;

    // Copper flags
    private static final int COP_CTRL_EN      = 0x01;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END     = 0x80;

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_CURSOR_BLINK   = 0x08;

    // TX_CMD bits
    private static final int TXCMD_CLR_SCREEN = 0x04;
    private static final int TXCMD_SCROLL_UP  = 0x08;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;

    // FX-thread present pulse
    private AnimationTimer fxTimer;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("R816 VPU Demo (Text overlay HW scroll + Copper gradient)");
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

        // Select TEXT overlay personality explicitly.
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00);

        // Use any graphics mode (0..3). We keep mode 0 (blank VRAM) and draw opaque text on top.
        vpu.writeMmio(REG_MODE, (byte) 0);

        // Text visible; cursor on (no transparent bg; background uses palette[1], which copper animates)
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK));

        // Default attribute: bg=1, fg=15 (white on palette[1])
        final int attr = 0x1F;
        vpu.writeMmio(REG_TX_ATTR, (byte) attr);
        vpu.writeMmio(REG_TX_CMD, (byte) TXCMD_CLR_SCREEN);

        // Build copper list once: palette[1] becomes a vertical RGB565 gradient per scanline.
        buildCopperGradient(vpu);

        // Enable copper. LEN=0 means "scan until END marker".
        vpu.writeMmio(REG_COP_LEN_L, (byte) 0x00);
        vpu.writeMmio(REG_COP_LEN_H, (byte) 0x00);
        vpu.writeMmio(REG_COP_CTRL, (byte) COP_CTRL_EN);

        // Header text (TX_PORT is fine here; we are not writing the bottom row).
        writeAt(vpu, 0, 0, "R816 VPU: TEXT OVERLAY + TX_FINE_Y + TXCMD_SCROLL_UP + COPPER", true);
        writeAt(vpu, 0, 1, "Smooth scroll: time-based fine scroll (0..15), coarse scroll between frames.", true);
        writeAt(vpu, 0, 2, "Copper: palette[1] changes per scanline (RGB565 gradient).", true);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame, attr), "vpu-text-copper-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame, int attr) {
        int lineNo = 0;

        // Time-based scroll accumulator (pixels)
        final double pixelsPerSecond = 70.0; // tweak: 40..90 looks nice
        double fineAcc = 0.0;
        int fine = 0;

        // Seed bottom row
        pokeScreenRow(vpu, 24, String.format("LINE %05d  @mdg", lineNo++), attr);

        long last = System.nanoTime();
        // Pace the emu loop (soft 70Hz target)
        final long frameNanosTarget = (long) (1_000_000_000L / 70.0);
        long nextFrame = last;

        while (running.get()) {
            long now = System.nanoTime();
            double dt = (now - last) / 1e9;
            last = now;

            // Accumulate fine scroll steps based on wall time
            fineAcc += dt * pixelsPerSecond;

            // 1) Apply current fine scroll for THIS frame
            vpu.writeMmio(REG_TX_FINE_Y, (byte) (fine & 0x0F));

            // 2) Run exactly one frame worth of VPU time
            vpu.tick(cyclesPerFrame);

            // 3) Advance scroll state BETWEEN frames (consume accumulated pixels)
            while (fineAcc >= 1.0) {
                fineAcc -= 1.0;
                fine++;

                if (fine == 16) {
                    fine = 0;

                    // Coarse scroll up one row in the ring buffer
                    vpu.writeMmio(REG_TX_CMD, (byte) TXCMD_SCROLL_UP);

                    // Write new bottom row content without triggering TX_PORT wrap/newline
                    String s = String.format(
                            "LINE %05d  The quick brown fox jumps over the lazy dog.  @mdg",
                            lineNo++
                    );
                    pokeScreenRow(vpu, 24, s, attr);
                }
            }

            // Soft pacing (keeps CPU sane, reduces jitter)
            nextFrame += frameNanosTarget;
            long sleep = nextFrame - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                // If we're behind, snap nextFrame to now to avoid runaway lag.
                nextFrame = System.nanoTime();
            }
        }
    }

    private static void buildCopperGradient(VPU_v2 vpu) {
        // Each instruction is 8 bytes:
        // scan(2), reg(2), valLo, valHi, flags, pad
        //
        // We WRITE16 to palette index 1 at PAL_BASE + 2*1 = 0x0102
        final int PAL_INDEX_1 = 0x0100 + 2;
        final int H = 400;

        for (int y = 0; y < H; y++) {
            int r5 = (y * 31) / (H - 1);
            int g6 = ((H - 1 - y) * 63) / (H - 1);
            int b5 = (y * 31) / (H - 1);

            int rgb565 = (r5 << 11) | (g6 << 5) | b5;
            int lo = rgb565 & 0xFF;
            int hi = (rgb565 >>> 8) & 0xFF;

            int pos = COPPER_BASE + y * COPPER_STRIDE;

            vpu.writeMmio(pos + 0, (byte) (y & 0xFF));
            vpu.writeMmio(pos + 1, (byte) ((y >>> 8) & 0xFF));

            vpu.writeMmio(pos + 2, (byte) (PAL_INDEX_1 & 0xFF));
            vpu.writeMmio(pos + 3, (byte) ((PAL_INDEX_1 >>> 8) & 0xFF));

            vpu.writeMmio(pos + 4, (byte) lo);
            vpu.writeMmio(pos + 5, (byte) hi);

            vpu.writeMmio(pos + 6, (byte) COP_FLAG_WRITE16);
            vpu.writeMmio(pos + 7, (byte) 0);
        }

        // END marker
        int endPos = COPPER_BASE + H * COPPER_STRIDE;
        vpu.writeMmio(endPos + 0, (byte) 0xFF);
        vpu.writeMmio(endPos + 1, (byte) 0xFF);
        vpu.writeMmio(endPos + 2, (byte) 0xFF);
        vpu.writeMmio(endPos + 3, (byte) 0xFF);
        vpu.writeMmio(endPos + 4, (byte) 0);
        vpu.writeMmio(endPos + 5, (byte) 0);
        vpu.writeMmio(endPos + 6, (byte) COP_FLAG_END);
        vpu.writeMmio(endPos + 7, (byte) 0);
    }

    private static void writeAt(VPU_v2 vpu, int x, int y, String s, boolean newline) {
        vpu.writeMmio(REG_TX_CUR_X, (byte) x);
        vpu.writeMmio(REG_TX_CUR_Y, (byte) y);
        for (int i = 0; i < s.length(); i++) {
            vpu.writeMmio(REG_TX_PORT, (byte) (s.charAt(i) & 0xFF));
        }
        if (newline) vpu.writeMmio(REG_TX_PORT, (byte) '\n');
    }

    /**
     * Write a screen-relative row (0..24) into the ring-buffered TEXT RAM.
     * This avoids TX_PORT auto-newline/scroll, so it's ideal for updating the bottom row in a smooth scroller.
     */
    private static void pokeScreenRow(VPU_v2 vpu, int row, String s, int attr) {
        if (row < 0 || row >= TEXT_ROWS) return;

        int origin = (vpu.readMmio(REG_TX_ORIGIN_L) & 0xFF) | ((vpu.readMmio(REG_TX_ORIGIN_H) & 0xFF) << 8);
        origin %= TEXT_CELLS;
        if (origin < 0) origin += TEXT_CELLS;

        int cellBase = origin + (row * TEXT_COLS);
        cellBase %= TEXT_CELLS;
        if (cellBase < 0) cellBase += TEXT_CELLS;

        int n = Math.min(TEXT_COLS, s.length());
        for (int x = 0; x < TEXT_COLS; x++) {
            int ch = (x < n) ? (s.charAt(x) & 0xFF) : 0x20;
            int cell = cellBase + x;
            if (cell >= TEXT_CELLS) cell -= TEXT_CELLS;

            int mmio = TEXT_BASE + (cell << 1);
            vpu.writeMmio(mmio, (byte) ch);
            vpu.writeMmio(mmio + 1, (byte) (attr & 0xFF));
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