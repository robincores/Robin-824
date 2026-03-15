package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v2;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo: MODE 2 palette-cycled plasma + TEXT overlay split-screen (copper).
 *
 * - Plasma is "Amiga-style": indices are static in VRAM, animation is palette cycling.
 * - Split-screen is "VGA line-compare style": copper changes TX_ORIGIN + TX_CTRL at a scanline.
 * - Hard split bar: copper also changes palette entries at the split (HUD gets its own colors).
 * - VGA-ish blink attribute: when TX_BLINK_MODE is set, attr bit7 blinks characters.
 *
 * NOTE:
 * This is visually like VGA line-compare. Because we use a ring-buffer origin for scroll,
 * we rewrite the HUD rows after the log rows each tick so the HUD remains stable.
 */
public final class VpuDemoTextSplitCopperMode2Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU MMIO offsets ----
    private static final int REG_CTRL        = 0x0000;
    private static final int REG_MODE        = 0x0002;
    private static final int REG_TX_CTRL     = 0x0007;

    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;

    private static final int REG_COP_CTRL    = 0x0016;
    private static final int REG_COP_LEN_L   = 0x0017;
    private static final int REG_COP_LEN_H   = 0x0018;

    private static final int REG_OVL_MODE    = 0x001F;

    private static final int PAL_BASE        = 0x0100;
    private static final int COPPER_BASE     = 0x1000;

    private static final int TEXT_BASE       = 0x2000;

    // TX_CTRL bits (must match VPU)
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_TRANSPARENT_BG = 0x04;

    // NEW (must match your VPU patch)
    private static final int TX_BLINK_MODE     = 0x10;

    // Copper flags (must match VPU)
    private static final int COP_CTRL_EN      = 0x01;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END     = 0x80;

    // MODE 2 source resolution
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL   = SRC_W / 4; // 80

    // Text geometry
    private static final int COLS = 80;
    private static final int ROWS = 25;

    // Split: last 2 rows are HUD
    private static final int HUD_ROWS = 2;
    private static final int TOP_ROWS = ROWS - HUD_ROWS; // 23
    private static final int SPLIT_SCANLINE = TOP_ROWS * 16; // 368

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // Precomputed sine for palette cycling hue variation
    private static final int[] SIN256 = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            SIN256[i] = (int) Math.round(Math.sin(i * (2.0 * Math.PI / 256.0)) * 127.0);
        }
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);
        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU Demo: MODE 2 Plasma + Copper Split Text — FPS --");
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

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoTextSplitCopperMode2Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // MODE 2 graphics underlay + TEXT personality overlay
        vpu.writeMmio(REG_MODE, (byte) 2);
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x00); // text personality
        vpu.writeMmio(REG_CTRL, (byte) 0x01);     // enable

        // Static VRAM plasma field (indices), animated via palette cycling
        fillPlasmaFieldMode2(vpu);

        // Base palette (0..15) + initial plasma palette (32..255)
        installBasePalettes(vpu);

        // Enable copper
        vpu.writeMmio(REG_COP_CTRL, (byte) COP_CTRL_EN);

        // We write 7 entries (incl END) => 7*8 = 56 bytes
        vpu.writeMmio(REG_COP_LEN_L, (byte) (56 & 0xFF));
        vpu.writeMmio(REG_COP_LEN_H, (byte) ((56 >>> 8) & 0xFF));

        // Generate a log
        final String[] log = new String[300];
        for (int i = 0; i < log.length; i++) {
            log[i] = String.format("R816 VPU LOG %03d  |  copper split-screen  |  VGA-ish blink attr bit7", i);
        }
        int logStart = 0;

        // Top scrolling origin (rows)
        int originTopRow = 0;

        // HUD colors (only affect indices 1 and 14, which plasma does NOT use)
        final int HUD_BG_RGB = 0x102030;   // dark blue-ish
        final int HUD_FG_RGB = 0xFFE08A;   // warm highlight

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int phase = 0;
        int tick = 0;

        while (running.get()) {
            // Animate plasma palette (cheap)
            updatePlasmaPalette(vpu, phase);

            // Scroll every few frames
            if ((tick++ % 6) == 0) {
                originTopRow = (originTopRow + 1) % ROWS;
                logStart = (logStart + 1) % log.length;
            }

            // Write top text content into the buffer rows that will appear in the top region.
            // Alternate lines blink by setting attr bit7 (works when TX_BLINK_MODE is enabled).
            for (int r = 0; r < TOP_ROWS; r++) {
                int bufferRow = (originTopRow + r) % ROWS;
                String line = log[(logStart + r) % log.length];

                // fg=15, bg=0. Every other line uses bit7=1 => blink.
                int attr = ((r & 1) == 0) ? 0x8F : 0x0F;
                writeTextLine(vpu, bufferRow, line, attr);
            }

            // HUD rows are fixed and must be in buffer rows 23 and 24 because bottom origin = 0.
            // We write these last so they're stable even if top region overwrote them.
            writeTextLine(vpu, 23, padRight("HUD: copper split + palette bar | top transparent + blink | plasma = palette-cycled", COLS), 0x1E);
            writeTextLine(vpu, 24, padRight(String.format("ORIGIN_TOP_ROW=%d  PHASE=%d  (pal[1]/pal[14] change at split)", originTopRow, phase), COLS), 0x1E);

            // Program copper list for this frame:
            //  scan 0      : ORIGIN=originTopRow*80, TX_CTRL=transparent+blinkMode
            //  scan split  : ORIGIN=0, TX_CTRL=opaque+blinkMode, and change pal[1]/pal[14] for HUD bar
            writeCopperSplitList(vpu, originTopRow, HUD_BG_RGB, HUD_FG_RGB);

            // Run one frame worth of VPU time
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(chunk);
            }

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU Demo: MODE 2 Plasma + Copper Split Text — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            phase = (phase + 2) & 255;

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Copper list (7 entries => 56 bytes)
    // ---------------------------------------------------------------------

    private static void writeCopperSplitList(VPU_v2 vpu, int originTopRow, int hudBgRgb888, int hudFgRgb888) {
        int pos = 0;

        int originTopCells = originTopRow * COLS; // cells

        // Entry 0: scan=0, ORIGIN=originTopCells
        pos = writeCopperEntry16(vpu, pos, 0, REG_TX_ORIGIN_L, originTopCells, 0);

        // Entry 1: scan=0, TX_CTRL = transparent + blink-mode
        int topCtrl = TX_EN | TX_TRANSPARENT_BG | TX_BLINK_MODE;
        pos = writeCopperEntry8(vpu, pos, 0, REG_TX_CTRL, topCtrl, 0);

        // Entry 2: split, ORIGIN=0 (HUD fixed)
        pos = writeCopperEntry16(vpu, pos, SPLIT_SCANLINE, REG_TX_ORIGIN_L, 0, 0);

        // Entry 3: split, TX_CTRL = opaque + blink-mode
        int botCtrl = TX_EN | TX_BLINK_MODE;
        pos = writeCopperEntry8(vpu, pos, SPLIT_SCANLINE, REG_TX_CTRL, botCtrl, 0);

        // Entry 4: split, pal[1] = HUD background (rgb565)
        int hudBg565 = rgb888To565(hudBgRgb888);
        pos = writeCopperEntry16(vpu, pos, SPLIT_SCANLINE, PAL_BASE + (1 << 1), hudBg565, 0);

        // Entry 5: split, pal[14] = HUD foreground accent (rgb565)
        int hudFg565 = rgb888To565(hudFgRgb888);
        pos = writeCopperEntry16(vpu, pos, SPLIT_SCANLINE, PAL_BASE + (14 << 1), hudFg565, 0);

        // Entry 6: END
        writeCopperEnd(vpu, pos);
    }

    private static int writeCopperEntry16(VPU_v2 vpu, int pos, int scan, int reg, int value16, int flagsExtra) {
        int vLo = value16 & 0xFF;
        int vHi = (value16 >>> 8) & 0xFF;
        int flags = COP_FLAG_WRITE16 | (flagsExtra & 0x7F);

        writeMmioU16(vpu, COPPER_BASE + pos + 0, scan);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, reg);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) vLo);
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) vHi);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) flags);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
        return pos + 8;
    }

    private static int writeCopperEntry8(VPU_v2 vpu, int pos, int scan, int reg, int value8, int flagsExtra) {
        int flags = (flagsExtra & 0x7F);
        writeMmioU16(vpu, COPPER_BASE + pos + 0, scan);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, reg);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) (value8 & 0xFF));
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) flags);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
        return pos + 8;
    }

    private static void writeCopperEnd(VPU_v2 vpu, int pos) {
        writeMmioU16(vpu, COPPER_BASE + pos + 0, 0);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, 0);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) COP_FLAG_END);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
    }

    private static void writeMmioU16(VPU_v2 vpu, int o, int v) {
        vpu.writeMmio(o,     (byte) (v & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((v >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // Text RAM writes
    // ---------------------------------------------------------------------

    private static void writeTextLine(VPU_v2 vpu, int row, String s, int attr) {
        int base = TEXT_BASE + row * COLS * 2;
        int n = Math.min(COLS, s.length());
        for (int i = 0; i < COLS; i++) {
            int ch = (i < n) ? (s.charAt(i) & 0xFF) : 0x20;
            vpu.writeMmio(base + (i * 2),     (byte) ch);
            vpu.writeMmio(base + (i * 2) + 1, (byte) (attr & 0xFF));
        }
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // MODE 2 Plasma field (static indices in VRAM)
    // ---------------------------------------------------------------------

    private static void fillPlasmaFieldMode2(VPU_v2 vpu) {
        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * BPL;
            for (int byteX = 0; byteX < BPL; byteX++) {
                int x0 = byteX << 2;
                int ofs = rowBase + byteX;

                int i0 = plasmaIdx(x0 + 0, y);
                int i1 = plasmaIdx(x0 + 1, y);
                int i2 = plasmaIdx(x0 + 2, y);
                int i3 = plasmaIdx(x0 + 3, y);

                vpu.writeVramPlane(0, ofs, (byte) i0);
                vpu.writeVramPlane(1, ofs, (byte) i1);
                vpu.writeVramPlane(2, ofs, (byte) i2);
                vpu.writeVramPlane(3, ofs, (byte) i3);
            }
        }
    }

    private static int plasmaIdx(int x, int y) {
        int a = SIN256[(x * 3) & 255];
        int b = SIN256[(y * 4) & 255];
        int c = SIN256[((x + y) * 2) & 255];
        int s = a + b + c;        // ~[-381..381]
        int v = (s + 384) >> 2;   // ~[0..192]
        int idx = 32 + ((v * 223) / 192);
        if (idx < 32) idx = 32;
        if (idx > 255) idx = 255;
        return idx;
    }

    // ---------------------------------------------------------------------
    // Palette: VGA-ish base + animated rainbow for 32..255 (palette cycling)
    // ---------------------------------------------------------------------

    private static void installBasePalettes(VPU_v2 vpu) {
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);

        updatePlasmaPalette(vpu, 0);
    }

    private static void updatePlasmaPalette(VPU_v2 vpu, int phase) {
        for (int i = 0; i < 224; i++) {
            double h = (((i * 2) + phase) & 255) / 256.0;
            int rgb = hsvToRgb888(h, 1.0, 0.95);
            writeRgb888ToPal(vpu, 32 + i, rgb);
        }
    }

    private static int hsvToRgb888(double h, double s, double v) {
        double hh = (h % 1.0) * 6.0;
        int sector = (int) Math.floor(hh);
        double f = hh - sector;

        double p = v * (1.0 - s);
        double q = v * (1.0 - s * f);
        double t = v * (1.0 - s * (1.0 - f));

        double r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }

        int R = (int) Math.round(r * 255.0);
        int G = (int) Math.round(g * 255.0);
        int B = (int) Math.round(b * 255.0);
        return (R << 16) | (G << 8) | B;
    }

    private static void writeRgb888ToPal(VPU_v2 vpu, int idx, int rgb888) {
        int rgb565 = rgb888To565(rgb888);
        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    private static int rgb888To565(int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);
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