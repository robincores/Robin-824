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
 * Demo: MODE 2 plasma + TEXT OVERLAY + VGA-style hardware scroll (TX_ORIGIN ring buffer).
 *
 * Updated for:
 * - Mode 4 removed (REG_MODE is 0..3 only)
 * - Text exists ONLY via MMIO overlay personality (Text Buffer + Font RAM)
 * - New MMIO memory map:
 *     Copper: 0x1000–0x1FFF
 *     Text:   0x2000–0x2F9F
 *     Font:   0x3000–0x3FFF
 *     Overlay select: REG_OVL_MODE (0x001F) bit0: 0=text, 1=tiles
 *
 * This keeps EXACTLY the same scroll behavior as the old HW scroll demo:
 * - origin += 80 (mod 2000)
 * - clear bottom row
 * - no memmove
 *
 * Difference vs old MODE 4 usage:
 * - graphics are MODE 2 (320x200 8bpp Mode-X-ish) scaled to 640x400
 * - text is overlay with transparent background (requires overlay personality = TEXT)
 */
public final class VpuDemoMode2OverlayHwScrollMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU regs ----
    private static final int REG_MODE        = 0x0002;
    private static final int REG_TX_CTRL     = 0x0007;
    private static final int REG_TX_CUR_X    = 0x0008;
    private static final int REG_TX_CUR_Y    = 0x0009;

    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;

    private static final int REG_OVL_MODE    = 0x001F; // bit0: 0=text, 1=tile/sprite

    // palette + text RAM (new map)
    private static final int PAL_BASE  = 0x0100;
    private static final int TEXT_BASE = 0x2000;

    private static final int TEXT_COLS = 80;
    private static final int TEXT_ROWS = 25;
    private static final int TOTAL_CELLS = TEXT_COLS * TEXT_ROWS;

    // TX_CTRL bits
    private static final int TX_EN              = 0x01;
    private static final int TX_CURSOR_EN       = 0x02;
    private static final int TX_TRANSPARENT_BG  = 0x04;
    private static final int TX_CURSOR_BLINK    = 0x08;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // ---- LUTs for plasma ----
    private static final int[] SIN8 = new int[256];   // 0..255
    private static final int[] MAP240 = new int[256]; // 0..239

    static {
        for (int i = 0; i < 256; i++) {
            double a = (i * 2.0 * Math.PI) / 256.0;
            SIN8[i] = (int) Math.round(127.5 + 127.5 * Math.sin(a));
        }
        for (int i = 0; i < 256; i++) {
            MAP240[i] = (i * 239 + 127) / 255;
        }
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("R816 VPU Demo: MODE 2 plasma + text overlay + TX_ORIGIN scroll (new MMIO map)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v2 vpu = new VPU_v2(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                vpu.fxPulse();
            }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu-mode2-overlay-hwscroll-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // --- select TEXT overlay personality (required for text+font access) ---
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00);

        // --- MODE 2 graphics ---
        vpu.writeMmio(REG_MODE, (byte) 2);

        // --- text overlay on top (transparent bg) ---
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_TRANSPARENT_BG | TX_CURSOR_EN | TX_CURSOR_BLINK));

        // Palette: keep 0..15 VGA-ish for text, 16..255 smooth for plasma
        installPalette(vpu);

        TerminalHwScroll term = new TerminalHwScroll(vpu, 0x0F, 0x00); // white on black (bg ignored because transparent)
        term.clear();

        term.setColor(0x0F, 0x00);
        term.println("MODE 2 PLASMA + TEXT OVERLAY");
        term.setColor(0x0E, 0x00);
        term.println("Scroll: TX_ORIGIN += 80 (ring buffer), no memmove.");
        term.println("");

        final int srcW = 320, srcH = 200;
        final int bpl = srcW / 4; // 80 bytes/line/plane

        final int[] x3 = new int[srcW];
        final int[] x5 = new int[srcW];
        for (int x = 0; x < srcW; x++) {
            x3[x] = (x * 3) & 0xFF;
            x5[x] = (x * 5) & 0xFF;
        }
        final int[] y2 = new int[srcH];
        final int[] y4 = new int[srcH];
        for (int y = 0; y < srcH; y++) {
            y2[y] = (y * 2) & 0xFF;
            y4[y] = (y * 4) & 0xFF;
        }

        final int[] sxA = new int[srcW];
        final int[] sxB = new int[srcW];
        final int[] syA = new int[srcH];
        final int[] syB = new int[srcH];

        int frame = 0;
        int lineNo = 1;

        while (running.get()) {
            // ---- plasma frame ----
            int t1 = frame & 0xFF;
            int t2 = (frame * 2) & 0xFF;
            int t3 = (frame * 3) & 0xFF;

            for (int x = 0; x < srcW; x++) {
                sxA[x] = SIN8[(x3[x] + t1) & 0xFF];
                sxB[x] = SIN8[(x5[x] + t3) & 0xFF];
            }
            for (int y = 0; y < srcH; y++) {
                syA[y] = SIN8[(y2[y] + t2) & 0xFF];
                syB[y] = SIN8[(y4[y] + t1) & 0xFF];
            }

            for (int y = 0; y < srcH; y++) {
                int lineBase = y * bpl;
                int ya = syA[y];
                int yb = syB[y];

                for (int byteX = 0; byteX < bpl; byteX++) {
                    int x0 = (byteX << 2);
                    int ofs = lineBase + byteX;

                    int v0 = (sxA[x0]     + sxB[x0]     + ya + yb) >> 2;
                    int v1 = (sxA[x0 + 1] + sxB[x0 + 1] + ya + yb) >> 2;
                    int v2 = (sxA[x0 + 2] + sxB[x0 + 2] + ya + yb) >> 2;
                    int v3 = (sxA[x0 + 3] + sxB[x0 + 3] + ya + yb) >> 2;

                    int idx0 = 16 + MAP240[v0];
                    int idx1 = 16 + MAP240[v1];
                    int idx2 = 16 + MAP240[v2];
                    int idx3 = 16 + MAP240[v3];

                    vpu.writeVramPlane(0, ofs, (byte) idx0);
                    vpu.writeVramPlane(1, ofs, (byte) idx1);
                    vpu.writeVramPlane(2, ofs, (byte) idx2);
                    vpu.writeVramPlane(3, ofs, (byte) idx3);
                }
            }

            // ---- print a line occasionally (this will scroll using TX_ORIGIN ring) ----
            if ((frame % 18) == 0) {
                term.setColor(0x0A, 0x00);
                term.print("READY. ");
                term.setColor(0x0F, 0x00);
                term.print("PRINT \"HELLO\"  ");
                term.setColor(0x0E, 0x00);
                term.println("#" + (lineNo++));

                // keep VPU cursor in sync
                vpu.writeMmio(REG_TX_CUR_X, (byte) term.curX);
                vpu.writeMmio(REG_TX_CUR_Y, (byte) term.curY);
            }

            // ---- run one frame worth of VPU time ----
            vpu.tick(cyclesPerFrame);

            frame++;

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    private static void installPalette(VPU_v2 vpu) {
        // 0..15 VGA-ish
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);

        // 16..255 smooth cycle
        for (int i = 16; i < 256; i++) {
            double a = ((i - 16) * 2.0 * Math.PI) / 239.0;
            int r = (int) Math.round(127.5 + 127.5 * Math.sin(a));
            int g = (int) Math.round(127.5 + 127.5 * Math.sin(a + 2.0 * Math.PI / 3.0));
            int b = (int) Math.round(127.5 + 127.5 * Math.sin(a + 4.0 * Math.PI / 3.0));
            int rgb = (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            writeRgb888ToPal(vpu, i, rgb);
        }
    }

    private static int clamp8(int v) { return (v < 0) ? 0 : Math.min(v, 255); }

    private static void writeRgb888ToPal(VPU_v2 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o,     (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    @Override
    public void stop() {
        running.set(false);

        if (fxTimer != null) { fxTimer.stop(); fxTimer = null; }

        if (emuThread != null) {
            try { emuThread.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * EXACT same scroll behavior as the old demo:
     * - origin += 80 (mod 2000)
     * - clear bottom row
     * - no memmove
     *
     * IMPORTANT: Uses TEXT_BASE=0x2000 (new map).
     */
    private static final class TerminalHwScroll {
        private final VPU_v2 vpu;

        private int fg;
        private int bg;

        private int curX = 0;
        private int curY = 0;

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
        void println() { newline(); }

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
            vpu.writeMmio(o,     (byte) (ch & 0xFF));
            vpu.writeMmio(o + 1, (byte) attr);
        }
    }
}