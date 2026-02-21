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
 * Demo: Text mode with VGA-like hardware scrolling + copper palette gradient.
 *
 * Fixes vs earlier demo:
 *  - The "jump" was caused by issuing TXCMD_SCROLL_UP in the same iteration where TX_FINE_Y=15
 *    (coarse scroll happened one frame too early).
 *  - Writing 80 chars via TX_PORT triggers the VPU's auto-newline, which can scroll the screen
 *    unexpectedly at the bottom row.
 *
 * This version:
 *  - Applies TX_FINE_Y for the frame, ticks one full frame, then advances the fine scroll.
 *  - When fine wraps, it issues TXCMD_SCROLL_UP *between* frames.
 *  - Writes the bottom row by poking TEXT RAM directly (screen-relative), avoiding TX_PORT wrapping.
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
    private static final int REG_MODE        = 0x0002;
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

    // Text RAM mapping
    private static final int TEXT_BASE       = 0x0300;
    private static final int TEXT_COLS       = 80;
    private static final int TEXT_ROWS       = 25;
    private static final int TEXT_CELLS      = TEXT_COLS * TEXT_ROWS; // 2000

    // Copper RAM mapping
    private static final int COPPER_BASE     = 0x2400;
    private static final int COPPER_STRIDE   = 8;

    // Copper flags
    private static final int COP_CTRL_EN      = 0x01;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END     = (byte) 0x80;

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_CURSOR_BLINK   = 0x08;

    // TX_CMD bits
    private static final int TXCMD_CLR_SCREEN = 0x04;
    private static final int TXCMD_SCROLL_UP  = 0x08;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("R816 VPU Demo (Text HW scroll + Copper bars)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Text-only mode
        vpu.writeMmio(REG_MODE, (byte) 4);

        // Text visible; cursor on
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
        writeAt(vpu, 0, 0, "R816 VPU: TX_PORT + TX_CMD + COPPER", true);
        writeAt(vpu, 0, 1, "Smooth scroll: TX_FINE_Y 0..15, then TXCMD_SCROLL_UP between frames.", true);
        writeAt(vpu, 0, 2, "Copper bars: palette[1] changes per scanline (no per-frame CPU writes).", true);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame, attr), "vpu-text-copper-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame, int attr) {
        int lineNo = 0;
        int fine = 0;

        // Seed bottom row
        pokeScreenRow(vpu, 24, String.format("LINE %05d  @mdg", lineNo++), attr);

        while (running.get()) {
            // 1) Apply fine scroll for THIS frame
            vpu.writeMmio(REG_TX_FINE_Y, (byte) (fine & 0x0F));

            // 2) Run exactly one frame worth of VPU time
            vpu.tick(cyclesPerFrame);

            // 3) Advance scroll state BETWEEN frames
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

            // Keep emu thread from pegging a core; not vsync-accurate (demo only).
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Platform.runLater(() -> {
            // no-op
        });
    }

    private static void buildCopperGradient(VPU vpu) {
        // Each instruction is 8 bytes:
        // scan(2), reg(2), valLo, valHi, flags, pad
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

    private static void writeAt(VPU vpu, int x, int y, String s, boolean newline) {
        vpu.writeMmio(REG_TX_CUR_X, (byte) x);
        vpu.writeMmio(REG_TX_CUR_Y, (byte) y);
        for (int i = 0; i < s.length(); i++) {
            vpu.writeMmio(REG_TX_PORT, (byte) (s.charAt(i) & 0xFF));
        }
        if (newline) {
            vpu.writeMmio(REG_TX_PORT, (byte) '\n');
        }
    }

    /**
     * Write a screen-relative row (0..24) into the ring-buffered TEXT RAM.
     * This avoids TX_PORT auto-newline/scroll, so it's ideal for updating the bottom row in a smooth scroller.
     */
    private static void pokeScreenRow(VPU vpu, int row, String s, int attr) {
        if (row < 0 || row >= TEXT_ROWS) return;

        int origin = (vpu.readMmio(REG_TX_ORIGIN_L) & 0xFF) | ((vpu.readMmio(REG_TX_ORIGIN_H) & 0xFF) << 8);
        origin %= TEXT_CELLS;
        if (origin < 0) origin += TEXT_CELLS;

        int cellBase = origin + (row * TEXT_COLS);
        cellBase %= TEXT_CELLS;
        if (cellBase < 0) cellBase += TEXT_CELLS;

        // Write chars + pad spaces
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
