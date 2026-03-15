package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v3;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v3 demo: LORES plasma underlay (8-bit via 8 bitplanes) + TEXT overlay split-screen (copper).
 *
 * Fixes vs the “jumpy” version:
 *  1) Smooth scroll using TX_FINE_Y (0..15). When it wraps -> advance TX_ORIGIN by 1 row.
 *  2) Top ring-buffer is constrained to TOP_ROWS (23) so it never overwrites HUD rows (23/24).
 *  3) Tick exactly cyclesPerFrame (no overshoot on the last chunk).
 *  4) Stable pacing with LockSupport (less jitter than Thread.sleep).
 *  5) Palette cycling updated every other frame to reduce CPU load (still looks smooth).
 */
public final class Vpu3DemoTextSplitCopperLoresPlasmaMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU MMIO offsets (v3) ----
    private static final int REG_CTRL        = 0x0000;
    private static final int REG_MODE        = 0x0002;   // bit0: 0=HIRES, 1=LORES
    private static final int REG_BPL_MASK    = 0x000D;   // bits0..7

    private static final int REG_TX_CTRL     = 0x0007;
    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;
    private static final int REG_TX_FINE_Y   = 0x0015;   // 0..15

    private static final int REG_COP_CTRL    = 0x0016;
    private static final int REG_COP_LEN_L   = 0x0017;
    private static final int REG_COP_LEN_H   = 0x0018;

    private static final int PAL_BASE        = 0x0100;
    private static final int COPPER_BASE     = 0x1000;
    private static final int TEXT_BASE       = 0x2000;

    // CTRL bits (v3)
    private static final int CTRL_ENABLE = 0x01;

    // TX_CTRL bits (v3)
    private static final int TX_EN             = 0x01;
    private static final int TX_TRANSPARENT_BG = 0x04;
    private static final int TX_CHAR_BLINK     = 0x10;   // attr bit7 = blink (VGA-ish)

    // Copper bits (v3)
    private static final int COP_CTRL_EN      = 0x01;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END     = 0x80;

    // Underlay source resolution for LORES (320x200 -> output 640x400)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BYTES_PER_ROW = SRC_W / 8;  // 40 bytes per plane per scanline

    // Text geometry
    private static final int COLS = 80;
    private static final int ROWS = 25;

    // Split: last 2 rows are HUD
    private static final int HUD_ROWS = 2;
    private static final int TOP_ROWS = ROWS - HUD_ROWS; // 23
    private static final int SPLIT_SCANLINE = TOP_ROWS * 16; // 368

    // Copper list size:
    //  scan0: ORIGIN(16), FINE_Y(8), TX_CTRL(8), pal1(16), pal14(16)
    //  split: ORIGIN(16), FINE_Y(8), TX_CTRL(8), pal1(16), pal14(16)
    //  END
    // => 11 entries * 8 = 88 bytes
    private static final int COPPER_LEN_BYTES = 88;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // Precomputed sine for plasma
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

        stage.setTitle("VPU v3 Demo: LORES Plasma + Copper Split Text — FPS --");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v3 vpu = new VPU_v3(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "Vpu3DemoTextSplitCopper-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v3 vpu, int cyclesPerFrame) {
        // LORES (320x200 -> 640x400) and enable all 8 planes for 8-bit indices.
        vpu.writeMmio(REG_MODE, (byte) 0x01);        // LORES
        vpu.writeMmio(REG_BPL_MASK, (byte) 0xFF);    // 8 planes => 0..255 indices
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);

        // Text defaults (copper will control it anyway)
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_TRANSPARENT_BG | TX_CHAR_BLINK));

        // Build static plasma indices into VRAM (as 8 bitplanes)
        fillPlasmaFieldLores8bppPlanar(vpu);

        // Base palette + initial plasma palette (32..255)
        installBasePalettes(vpu);

        // Enable copper and set list length
        vpu.writeMmio(REG_COP_CTRL, (byte) COP_CTRL_EN);
        vpu.writeMmio(REG_COP_LEN_L, (byte) (COPPER_LEN_BYTES & 0xFF));
        vpu.writeMmio(REG_COP_LEN_H, (byte) ((COPPER_LEN_BYTES >>> 8) & 0xFF));

        // Generate a log
        final String[] log = new String[300];
        for (int i = 0; i < log.length; i++) {
            log[i] = String.format("R816 VPU LOG %03d  |  copper split-screen  |  VGA-ish blink attr bit7", i);
        }
        int logStart = 0;

        // Smooth scroll state: top region is a ring-buffer over rows 0..22 only (TOP_ROWS).
        int originTopRow = 0; // 0..22
        int fineY = 0;        // 0..15

        // HUD colors (affect indices 1 and 14; plasma uses 32..255)
        final int HUD_BG_RGB = 0x102030;
        final int HUD_FG_RGB = 0xFFE08A;

        // Restore colors for pal[1]/pal[14] at scan 0
        final int PAL1_TOP_RGB  = 0x0000AA;
        final int PAL14_TOP_RGB = 0xFFFF55;

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int phase = 0;
        int tick = 0;

        // Stable pacing target (try 60Hz-ish)
        final long frameNanosTarget = 1_000_000_000L / 60L;
        long nextFrame = System.nanoTime();

        while (running.get()) {
            // Plasma palette update (every other frame to reduce load)
            if ((tick & 1) == 0) {
                updatePlasmaPalette(vpu, phase);
                phase = (phase + 2) & 255;
            }

            // Smooth scroll: advance fineY each frame; when it wraps, advance one text row.
            fineY = (fineY + 1) & 0x0F;
            if (fineY == 0) {
                originTopRow = (originTopRow + 1) % TOP_ROWS; // IMPORTANT: never touch HUD rows
                logStart = (logStart + 1) % log.length;
            }

            // Write TOP region content into buffer rows 0..22 only.
            for (int r = 0; r < TOP_ROWS; r++) {
                int bufferRow = (originTopRow + r) % TOP_ROWS; // IMPORTANT: never wrap into HUD rows
                String line = log[(logStart + r) % log.length];

                // fg=15 bg=0. Every other line sets bit7 => blink (when TX_CHAR_BLINK enabled).
                int attr = ((r & 1) == 0) ? 0x8F : 0x0F;
                writeTextLine(vpu, bufferRow, line, attr);
            }

            // HUD rows: fixed and always in buffer rows 23 and 24
            writeTextLine(vpu, 23,
                    padRight("HUD: copper split + palette bar | top transparent + blink | plasma = palette-cycled", COLS),
                    0x1E);
            writeTextLine(vpu, 24,
                    padRight(String.format("TOP_ORIGIN=%02d  FINE_Y=%02d  PHASE=%03d", originTopRow, fineY, phase), COLS),
                    0x1E);

            // Copper: apply fineY to top region only; bottom region fineY=0 (HUD stable)
            writeCopperSplitList(vpu, originTopRow, fineY, PAL1_TOP_RGB, PAL14_TOP_RGB, HUD_BG_RGB, HUD_FG_RGB);

            // Run exactly one frame worth of VPU time (no overshoot)
            tickExactlyOneFrame(vpu, cyclesPerFrame);

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU v3 Demo: LORES Plasma + Copper Split Text — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            // Pace
            nextFrame += frameNanosTarget;
            long sleep = nextFrame - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                // We fell behind; reset pacing anchor to avoid runaway drift
                nextFrame = System.nanoTime();
            }

            tick++;
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    private static void tickExactlyOneFrame(VPU_v3 vpu, int cyclesPerFrame) {
        final int chunk = 5_000;
        int done = 0;
        while (done < cyclesPerFrame) {
            int step = Math.min(chunk, cyclesPerFrame - done);
            vpu.tick(step);
            done += step;
        }
    }

    // ---------------------------------------------------------------------
    // Copper list (11 entries => 88 bytes)
    // ---------------------------------------------------------------------

    private static void writeCopperSplitList(
            VPU_v3 vpu,
            int originTopRow,
            int fineYTop,
            int pal1TopRgb888,
            int pal14TopRgb888,
            int hudBgRgb888,
            int hudFgRgb888
    ) {
        int pos = 0;

        int originTopCells = originTopRow * COLS;

        // scan 0: ORIGIN = originTopCells
        pos = writeCopperEntry16(vpu, pos, 0, REG_TX_ORIGIN_L, originTopCells);

        // scan 0: FINE_Y = fineYTop
        pos = writeCopperEntry8(vpu, pos, 0, REG_TX_FINE_Y, fineYTop & 0x0F);

        // scan 0: TX_CTRL = transparent + blink
        int topCtrl = TX_EN | TX_TRANSPARENT_BG | TX_CHAR_BLINK;
        pos = writeCopperEntry8(vpu, pos, 0, REG_TX_CTRL, topCtrl);

        // scan 0: restore pal[1], pal[14]
        pos = writeCopperEntry16(vpu, pos, 0, PAL_BASE + (1 << 1), rgb888To565(pal1TopRgb888));
        pos = writeCopperEntry16(vpu, pos, 0, PAL_BASE + (14 << 1), rgb888To565(pal14TopRgb888));

        // split: ORIGIN = 0 (HUD fixed)
        pos = writeCopperEntry16(vpu, pos, SPLIT_SCANLINE, REG_TX_ORIGIN_L, 0);

        // split: FINE_Y = 0 (HUD stable)
        pos = writeCopperEntry8(vpu, pos, SPLIT_SCANLINE, REG_TX_FINE_Y, 0);

        // split: TX_CTRL = opaque + blink
        int botCtrl = TX_EN | TX_CHAR_BLINK;
        pos = writeCopperEntry8(vpu, pos, SPLIT_SCANLINE, REG_TX_CTRL, botCtrl);

        // split: pal[1] HUD background
        pos = writeCopperEntry16(vpu, pos, SPLIT_SCANLINE, PAL_BASE + (1 << 1), rgb888To565(hudBgRgb888));

        // split: pal[14] HUD foreground accent
        pos = writeCopperEntry16(vpu, pos, SPLIT_SCANLINE, PAL_BASE + (14 << 1), rgb888To565(hudFgRgb888));

        // END
        writeCopperEnd(vpu, pos);
    }

    private static int writeCopperEntry16(VPU_v3 vpu, int pos, int scan, int reg, int value16) {
        int vLo = value16 & 0xFF;
        int vHi = (value16 >>> 8) & 0xFF;

        writeMmioU16(vpu, COPPER_BASE + pos + 0, scan);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, reg);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) vLo);
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) vHi);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) COP_FLAG_WRITE16);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
        return pos + 8;
    }

    private static int writeCopperEntry8(VPU_v3 vpu, int pos, int scan, int reg, int value8) {
        writeMmioU16(vpu, COPPER_BASE + pos + 0, scan);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, reg);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) (value8 & 0xFF));
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
        return pos + 8;
    }

    private static void writeCopperEnd(VPU_v3 vpu, int pos) {
        writeMmioU16(vpu, COPPER_BASE + pos + 0, 0);
        writeMmioU16(vpu, COPPER_BASE + pos + 2, 0);
        vpu.writeMmio(COPPER_BASE + pos + 4, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 5, (byte) 0);
        vpu.writeMmio(COPPER_BASE + pos + 6, (byte) COP_FLAG_END);
        vpu.writeMmio(COPPER_BASE + pos + 7, (byte) 0);
    }

    private static void writeMmioU16(VPU_v3 vpu, int o, int v) {
        vpu.writeMmio(o,     (byte) (v & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((v >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // Text RAM writes
    // ---------------------------------------------------------------------

    private static void writeTextLine(VPU_v3 vpu, int row, String s, int attr) {
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
    // LORES plasma field: 8-bit indices encoded as 8 bitplanes (static indices in VRAM)
    // ---------------------------------------------------------------------

    private static void fillPlasmaFieldLores8bppPlanar(VPU_v3 vpu) {
        // Small perf tweak: reuse the array instead of allocating per byte column
        int[] planeByte = new int[8];

        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * BYTES_PER_ROW;

            for (int bx = 0; bx < BYTES_PER_ROW; bx++) {
                int x0 = bx << 3; // 8 pixels per byte
                Arrays.fill(planeByte, 0);

                for (int i = 0; i < 8; i++) {
                    int idx = plasmaIdx(x0 + i, y);   // 32..255
                    int bit = 1 << (7 - i);

                    for (int p = 0; p < 8; p++) {
                        if (((idx >>> p) & 1) != 0) {
                            planeByte[p] |= bit;
                        }
                    }
                }

                int ofs = rowBase + bx;
                for (int p = 0; p < 8; p++) {
                    vpu.writeVramPlane(p, ofs, (byte) planeByte[p]);
                }
            }
        }
    }

    private static int plasmaIdx(int x, int y) {
        int a = SIN256[(x * 3) & 255];
        int b = SIN256[(y * 4) & 255];
        int c = SIN256[((x + y) * 2) & 255];
        int s = a + b + c;        // ~[-381..381]
        int v = (s + 384) >> 2;   // ~[0..192]
        int idx = 32 + ((v * 223) / 192); // map into 32..255
        if (idx < 32) idx = 32;
        if (idx > 255) idx = 255;
        return idx;
    }

    // ---------------------------------------------------------------------
    // Palette: base 0..15 + animated rainbow for 32..255
    // ---------------------------------------------------------------------

    private static void installBasePalettes(VPU_v3 vpu) {
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, rgb16[i]);
        updatePlasmaPalette(vpu, 0);
    }

    private static void updatePlasmaPalette(VPU_v3 vpu, int phase) {
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

    private static void writeRgb888ToPal(VPU_v3 vpu, int idx, int rgb888) {
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