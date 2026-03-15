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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v3 demo: Dual playfield (PF0+PF1) + Sprites.
 *
 * LORES (320x200 -> 640x400).
 * PF0: planes 0..3, palette base 0
 * PF1: planes 4..7, palette base 16, index0 is transparent (so PF0 shows through)
 *
 * Sprites (new v3 "Lynx-ish" format, 16 bytes/entry):
 *  - variable size, data pointer into sprite bank (vbank=8)
 *  - we keep it simple here: 8x8, no scaling, no collision, no chaining
 *  - PRIO (ATTR bit3): 0 = back group (between PF0 and PF1), 1 = front group (on top)
 */
public final class Vpu3DemoDualPlayfieldSpritesMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- v3 MMIO ----
    private static final int REG_CTRL            = 0x0000;
    private static final int REG_MODE            = 0x0002;  // bit0: 0=HIRES, 1=LORES
    private static final int REG_BPL_MASK        = 0x000D;  // enable planes
    private static final int REG_FB_BASE_L       = 0x0005;
    private static final int REG_FB_BASE_H       = 0x0006;

    private static final int REG_PF0_SCROLL_X_L  = 0x0030;
    private static final int REG_PF0_SCROLL_X_H  = 0x0031;
    private static final int REG_PF0_SCROLL_Y    = 0x0032;
    private static final int REG_PF1_SCROLL_X_L  = 0x0033;
    private static final int REG_PF1_SCROLL_X_H  = 0x0034;
    private static final int REG_PF1_SCROLL_Y    = 0x0035;

    private static final int REG_PF_SPLIT        = 0x0036;  // 0=single, else split index (1..7)
    private static final int REG_PF0_PAL_BASE    = 0x0037;
    private static final int REG_PF1_PAL_BASE    = 0x0038;

    private static final int REG_SPR_CTRL        = 0x0039;  // bit0 enable
    private static final int REG_TX_CTRL         = 0x0007;  // keep off

    private static final int PAL_BASE            = 0x0100;
    private static final int OAM_BASE            = 0x0300;

    // New sprite stride (16 bytes)
    private static final int OAM_STRIDE          = 16;

    // CTRL bits
    private static final int CTRL_ENABLE         = 0x01;

    // Sprite bank is vbank=8
    private static final int SPR_BANK            = 8;
    private static final int TILE_BYTES          = 32; // 8x8 4bpp packed

    // LORES source geometry (for pattern generation + sprite coords)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BYTES_PER_ROW = SRC_W / 8; // 40 bytes per plane per scanline

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

        stage.setTitle("VPU v3 Demo: PF0+PF1 + Sprites — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu3-dualpf-sprites-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v3 vpu, int cyclesPerFrame) {
        // LORES, enable all planes, dual playfield split=4 => PF0 planes 0..3, PF1 planes 4..7
        vpu.writeMmio(REG_MODE, (byte) 0x01);
        vpu.writeMmio(REG_BPL_MASK, (byte) 0xFF);
        vpu.writeMmio(REG_PF_SPLIT, (byte) 0x04);
        vpu.writeMmio(REG_PF0_PAL_BASE, (byte) 0);     // PF0 palette block 0..15
        vpu.writeMmio(REG_PF1_PAL_BASE, (byte) 16);    // PF1 palette block 16..31 (idx1==0 is transparent)
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00);
        vpu.writeMmio(REG_SPR_CTRL, (byte) 0x01);
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);

        // fbBase = 0
        vpu.writeMmio(REG_FB_BASE_L, (byte) 0);
        vpu.writeMmio(REG_FB_BASE_H, (byte) 0);

        // Palettes
        installPalettes(vpu);

        // Build playfield contents into planes:
        // PF0: checker/tile background in planes 0..3
        // PF1: transparent except for a "stripe layer" in planes 4..7
        buildDualPlayfieldLores(vpu);

        // Sprite tiles in sprite bank (vbank=8)
        buildSpriteTiles(vpu);

        // Init sprites (8x8 each)
        final int spriteCount = 64;
        for (int i = 0; i < spriteCount; i++) {
            int x = 20 + (i % 16) * 18;
            int y = 20 + (i / 16) * 18;

            int tile = i & 0x0F;
            int palBank = 2 + ((i >> 2) % 14); // 2..15 (avoid clobbering PF0/PF1 banks)

            boolean backGroup = (i & 1) == 0; // PRIO=0 => between PF0 and PF1
            writeSprite8x8(vpu, i, x, y, tile, palBank, backGroup, true);
        }

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int pf0x = 0, pf0y = 0;
        int pf1x = 0, pf1y = 0;
        double t = 0.0;

        final long frameNanos = 1_000_000_000L / 60L;
        long next = System.nanoTime();

        while (running.get()) {
            // Parallax scroll (different speeds)
            pf0x = (pf0x + 1) % SRC_W;
            pf1x = (pf1x + 2) % SRC_W;
            pf0y = (pf0y + 0) % SRC_H;
            pf1y = (pf1y + 1) % SRC_H;

            writeU16(vpu, REG_PF0_SCROLL_X_L, pf0x);
            vpu.writeMmio(REG_PF0_SCROLL_Y, (byte) (pf0y & 0xFF));
            writeU16(vpu, REG_PF1_SCROLL_X_L, pf1x);
            vpu.writeMmio(REG_PF1_SCROLL_Y, (byte) (pf1y & 0xFF));

            // Animate sprite positions slightly
            t += 0.06;
            for (int i = 0; i < spriteCount; i++) {
                int baseX = 20 + (i % 16) * 18;
                int baseY = 20 + (i / 16) * 18;
                int ox = (int) Math.round(Math.sin(t + i * 0.12) * 6.0);
                int oy = (int) Math.round(Math.cos(t * 1.1 + i * 0.10) * 5.0);
                int x = clamp(baseX + ox, 0, SRC_W - 8);
                int y = clamp(baseY + oy, 0, SRC_H - 8);

                int palBank = 2 + ((i + (int) (t * 2)) % 14); // 2..15
                boolean backGroup = (i & 1) == 0;

                writeSpriteXYPal(vpu, i, x, y, palBank, backGroup, true);
            }

            tickExactlyOneFrame(vpu, cyclesPerFrame);

            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                double fps = (fpsFrames * 1_000_000_000.0) / (now - fpsT0);
                final String title = String.format("VPU v3 Demo: PF0+PF1 + Sprites — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            next += frameNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else next = System.nanoTime();
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Dual playfield builder (LORES)
    // ---------------------------------------------------------------------

    private static void buildDualPlayfieldLores(VPU_v3 vpu) {
        int[] p0 = new int[4];
        int[] p1 = new int[4];

        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * BYTES_PER_ROW;

            for (int bx = 0; bx < BYTES_PER_ROW; bx++) {
                int x0 = bx << 3; // 8 pixels

                p0[0]=p0[1]=p0[2]=p0[3]=0;
                p1[0]=p1[1]=p1[2]=p1[3]=0;

                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    int bit = 1 << (7 - i);

                    // PF0 index 1..15 (checker-ish). (0 is fine too, just darker)
                    int idx0 = (((x >> 4) + (y >> 4)) & 0x0F);
                    if (idx0 == 0) idx0 = 1;

                    // PF1 index 0..15 where 0 means transparent (PF0 shows through)
                    int idx1 = 0;
                    if (((x + y) & 31) < 6) idx1 = 2 + ((x >> 5) & 0x0F);
                    idx1 &= 0x0F;

                    // PF0 planes 0..3
                    for (int p = 0; p < 4; p++) {
                        if (((idx0 >>> p) & 1) != 0) p0[p] |= bit;
                    }

                    // PF1 planes 4..7 (encoded as 4-bit index)
                    for (int p = 0; p < 4; p++) {
                        if (((idx1 >>> p) & 1) != 0) p1[p] |= bit;
                    }
                }

                int ofs = rowBase + bx;

                for (int p = 0; p < 4; p++) vpu.writeVramPlane(p, ofs, (byte) p0[p]);
                for (int p = 0; p < 4; p++) vpu.writeVramPlane(4 + p, ofs, (byte) p1[p]);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sprite tiles (sprite bank vbank=8): 16 tiles, 8x8, 4bpp packed
    // ---------------------------------------------------------------------

    private static void buildSpriteTiles(VPU_v3 vpu) {
        for (int t = 0; t < 16; t++) {
            int a = (t == 0) ? 1 : (t & 0x0F);
            int b = (a ^ 0x0F) & 0x0F;
            writeCheckerTileToSpriteBank(vpu, t, a, b);
        }
    }

    private static void writeCheckerTileToSpriteBank(VPU_v3 vpu, int tileIndex, int aNib, int bNib) {
        int base = tileIndex * TILE_BYTES;
        for (int y = 0; y < 8; y++) {
            for (int xPair = 0; xPair < 4; xPair++) {
                int x0 = xPair * 2;
                int p0 = (((x0 + y) & 1) == 0) ? aNib : bNib;
                int p1 = (((x0 + 1 + y) & 1) == 0) ? aNib : bNib;

                if (p0 == 0) p0 = 1;
                if (p1 == 0) p1 = 1;

                int packed = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                vpu.writeVramPlane(SPR_BANK, base + (y * 4) + xPair, (byte) packed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Palette: PF0 uses 0..15, PF1 uses 16..31, sprites use banks 2..15
    // ---------------------------------------------------------------------

    private static void installPalettes(VPU_v3 vpu) {
        // PF0 0..15: VGA-ish
        int[] vga16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, vga16[i]);

        // PF1 16..31: distinct pastel-ish
        for (int i = 0; i < 16; i++) {
            int rgb = hsvToRgb888((i / 16.0) * 360.0, 0.55, 0.95);
            writeRgb888ToPal(vpu, 16 + i, rgb);
        }

        // Sprite banks: 2..15 (keep PF0/PF1 intact)
        for (int bank = 2; bank < 16; bank++) {
            double hue = (bank / 16.0) * 360.0;
            writeRgb888ToPal(vpu, (bank << 4) | 0, 0x000000);
            for (int n = 1; n < 16; n++) {
                double vv = n / 15.0;
                int rgb = hsvToRgb888(hue, 0.85, 0.20 + 0.80 * vv);
                writeRgb888ToPal(vpu, (bank << 4) | n, rgb);
            }
        }
    }

    private static int hsvToRgb888(double hDeg, double s, double v) {
        double h = (hDeg % 360.0) / 60.0;
        double c = v * s;
        double x = c * (1.0 - Math.abs((h % 2.0) - 1.0));
        double m = v - c;

        double r1, g1, b1;
        if (h < 1)      { r1 = c; g1 = x; b1 = 0; }
        else if (h < 2) { r1 = x; g1 = c; b1 = 0; }
        else if (h < 3) { r1 = 0; g1 = c; b1 = x; }
        else if (h < 4) { r1 = 0; g1 = x; b1 = c; }
        else if (h < 5) { r1 = x; g1 = 0; b1 = c; }
        else            { r1 = c; g1 = 0; b1 = x; }

        int r = clamp((int) Math.round((r1 + m) * 255.0), 0, 255);
        int g = clamp((int) Math.round((g1 + m) * 255.0), 0, 255);
        int b = clamp((int) Math.round((b1 + m) * 255.0), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    private static void writeRgb888ToPal(VPU_v3 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // OAM (new v3 sprite format, 16 bytes/entry)
    //
    // [0]  Y low
    // [1]  X low
    // [2]  XYHI: bits0-1 X[9:8], bits2-3 Y[9:8]
    // [3]  WIDTH (pixels)  (8)
    // [4]  HEIGHT (pixels) (8)
    // [5]  ATTR: bit0 EN, bit1 HFLIP, bit2 VFLIP, bit3 PRIO(0 back,1 front)
    // [6]  PAL: 0..15  (final CLUT = PAL<<4 | pixNibble)
    // [7]  SCALE/COL: 0 (scale=1x, colId=0)
    // [8]  DATA_L (sprite bank pointer)
    // [9]  DATA_H
    // [10] LINK_L (unused here)
    // [11..15] reserved
    // ---------------------------------------------------------------------

    private static void writeSprite8x8(VPU_v3 vpu, int i, int x, int y, int tile, int palBank, boolean backGroup, boolean enable) {
        int base = OAM_BASE + i * OAM_STRIDE;

        int xClamped = clamp(x, 0, 1023);
        int yClamped = clamp(y, 0, 1023);

        int xLo = xClamped & 0xFF;
        int yLo = yClamped & 0xFF;
        int xyhi = ((xClamped >> 8) & 0x03) | (((yClamped >> 8) & 0x03) << 2);

        int attr = 0;
        if (enable) attr |= 0x01;
        if (!backGroup) attr |= 0x08; // front group

        int dataPtr = (tile * TILE_BYTES) & 0x3FFF;

        vpu.writeMmio(base + 0, (byte) yLo);
        vpu.writeMmio(base + 1, (byte) xLo);
        vpu.writeMmio(base + 2, (byte) (xyhi & 0xFF));
        vpu.writeMmio(base + 3, (byte) 8);   // width
        vpu.writeMmio(base + 4, (byte) 8);   // height
        vpu.writeMmio(base + 5, (byte) (attr & 0xFF));
        vpu.writeMmio(base + 6, (byte) (palBank & 0x0F));
        vpu.writeMmio(base + 7, (byte) 0x00); // scale/colId
        vpu.writeMmio(base + 8, (byte) (dataPtr & 0xFF));
        vpu.writeMmio(base + 9, (byte) ((dataPtr >>> 8) & 0xFF));

        // Clear the rest (good hygiene / deterministic)
        for (int k = 10; k < 16; k++) vpu.writeMmio(base + k, (byte) 0);
    }

    private static void writeSpriteXYPal(VPU_v3 vpu, int i, int x, int y, int palBank, boolean backGroup, boolean enable) {
        int base = OAM_BASE + i * OAM_STRIDE;

        int xClamped = clamp(x, 0, 1023);
        int yClamped = clamp(y, 0, 1023);

        int xLo = xClamped & 0xFF;
        int yLo = yClamped & 0xFF;
        int xyhi = ((xClamped >> 8) & 0x03) | (((yClamped >> 8) & 0x03) << 2);

        int attr = 0;
        if (enable) attr |= 0x01;
        if (!backGroup) attr |= 0x08; // front group

        vpu.writeMmio(base + 0, (byte) yLo);
        vpu.writeMmio(base + 1, (byte) xLo);
        vpu.writeMmio(base + 2, (byte) (xyhi & 0xFF));
        vpu.writeMmio(base + 5, (byte) (attr & 0xFF));
        vpu.writeMmio(base + 6, (byte) (palBank & 0x0F));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void tickExactlyOneFrame(VPU_v3 vpu, int cyclesPerFrame) {
        final int chunk = 5_000;
        int done = 0;
        while (done < cyclesPerFrame) {
            int step = Math.min(chunk, cyclesPerFrame - done);
            vpu.tick(step);
            done += step;
        }
    }

    private static void writeU16(VPU_v3 vpu, int loReg, int value) {
        vpu.writeMmio(loReg, (byte) (value & 0xFF));
        vpu.writeMmio(loReg + 1, (byte) ((value >>> 8) & 0xFF));
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : Math.min(v, hi);
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
