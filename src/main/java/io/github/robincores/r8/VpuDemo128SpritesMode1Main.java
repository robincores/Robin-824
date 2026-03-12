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

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo: MODE 1 (320x200 4bpp bitplanes -> 640x400) with 128 sprites enabled simultaneously.
 *
 * Notes:
 *  - Sprite X/Y are in SOURCE pixels for the active mode.
 *    For MODE 1 source is 320x200 (output scales X2 and double-scans Y).
 *  - Uses sprite/tile overlay personality (OVL_MODE=1).
 *  - Background graphics are disabled (CTRL_GFX_DIS) so sprites draw over solid color 0.
 */
public final class VpuDemo128SpritesMode1Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // output pixel buffer (ARGB)
            640, 400,   // canvas size
            400,        // vblankStart
            449,        // scanlinesPerFrame-1 (so total 450)
            400         // visibleHeight (used in your config)
    );

    // ---- VPU MMIO offsets ----
    private static final int REG_CTRL     = 0x0000;
    private static final int REG_MODE     = 0x0002;
    private static final int REG_TX_CTRL  = 0x0007;
    private static final int REG_OVL_MODE = 0x001F;

    private static final int PAL_BASE     = 0x0100;

    private static final int OAM_BASE     = 0x0300;
    private static final int OAM_STRIDE   = 8;

    private static final int OVERLAY_BASE = 0x2000;

    // CTRL bits (mirror of VPU)
    private static final int CTRL_ENABLE  = 0x01;
    private static final int CTRL_GFX_DIS = 0x08;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // MODE 1 source resolution
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;

    // 128 sprites arranged 16x8 in source space
    private static final int COLS = 16;
    private static final int ROWS = 8;
    private static final int SPRITES = COLS * ROWS; // 128

    // spacing in SOURCE pixels (8x8 sprites)
    private static final int DX = 20;
    private static final int DY = 23;

    // cached base positions
    private final int[] baseX = new int[SPRITES];
    private final int[] baseY = new int[SPRITES];

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU Demo: 128 Sprites (MODE 1) — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemo128SpritesMode1Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // MODE 1 + sprite overlay
        vpu.writeMmio(REG_MODE, (byte) 1);
        vpu.writeMmio(REG_OVL_MODE, (byte) 0x01); // sprite personality
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00);  // text off
        vpu.writeMmio(REG_CTRL, (byte) (CTRL_ENABLE | CTRL_GFX_DIS)); // background solid color 0

        installPalette(vpu);
        buildTiles(vpu);

        // base positions: 16x8 grid in source pixels, centered in 320x200
        // each sprite is 8x8 in source space
        final int spriteW = 8;
        final int spriteH = 8;

        final int gridW = (COLS - 1) * DX + spriteW;
        final int gridH = (ROWS - 1) * DY + spriteH;

        final int originX = (SRC_W - gridW) / 2;
        final int originY = (SRC_H - gridH) / 2;

        int idx = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                baseX[idx] = originX + c * DX;
                baseY[idx] = originY + r * DY;
                idx++;
            }
        }

        // Enable all 128 sprites once (we will update x/y each frame)
        for (int i = 0; i < SPRITES; i++) {
            int tile = i & 0x0F;        // 0..15
            int palBank = (i >> 3) & 0x0F; // 0..15 (slowly varies across grid)
            writeSprite(vpu, i, baseX[i], baseY[i], tile, palBank, false, false, true);
        }

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        double t = 0.0;

        while (running.get()) {
            // Animate: gentle wave in source space, all 128 sprites remain enabled.
            t += 0.06;

            for (int i = 0; i < SPRITES; i++) {
                int c = i & 15;
                int r = i >> 4;

                int ox = (int) Math.round(Math.sin(t + c * 0.35 + r * 0.20) * 6.0);
                int oy = (int) Math.round(Math.cos(t * 1.1 + c * 0.25 + r * 0.45) * 5.0);

                int x = clamp(baseX[i] + ox, 0, SRC_W - 8);
                int y = clamp(baseY[i] + oy, 0, SRC_H - 8);

                int tile = i & 0x0F;
                int palBank = (i + (int) (t * 2)) & 0x0F; // slowly cycles 0..15

                writeSprite(vpu, i, x, y, tile, palBank, false, false, true);
            }

            // Run one frame worth of VPU time
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(chunk);
            }

            // FPS in title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU Demo: 128 Sprites (MODE 1) — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Tiles (sprite personality): 8x8, 4bpp packed, 32 bytes per tile
    // ---------------------------------------------------------------------

    private static void buildTiles(VPU_v2 vpu) {
        // 16 tiles (0..15). We avoid nibble 0 (transparent) so sprites are always visible.
        for (int t = 0; t < 16; t++) {
            int nib = (t == 0) ? 1 : (t & 0x0F);
            writeCheckerTile(vpu, t, nib, (nib ^ 0x0F) & 0x0F);
        }
    }

    private static void writeCheckerTile(VPU_v2 vpu, int tileIndex, int aNibble, int bNibble) {
        // each row is 4 bytes, each byte = 2 pixels (hi nibble left, lo nibble right)
        int base = OVERLAY_BASE + (tileIndex * 32);
        for (int y = 0; y < 8; y++) {
            for (int xPair = 0; xPair < 4; xPair++) {
                int x0 = xPair * 2;
                int p0 = (((x0 + y) & 1) == 0) ? aNibble : bNibble;
                int p1 = (((x0 + 1 + y) & 1) == 0) ? aNibble : bNibble;
                // never write 0 unless you want transparency
                if (p0 == 0) p0 = 1;
                if (p1 == 0) p1 = 1;

                int packed = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                vpu.writeMmio(base + (y * 4) + xPair, (byte) packed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Palette: build 16 banks x 16 colors (index = (bank<<4)|nibble)
    // Keep index 0 black; the rest are bright.
    // ---------------------------------------------------------------------

    private static void installPalette(VPU_v2 vpu) {
        // bank 0: VGA-ish basic 16 (nice for debugging)
        int[] vga16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, vga16[i]);

        // banks 1..15: hue ramps
        for (int bank = 1; bank < 16; bank++) {
            double hue = (bank / 16.0) * 360.0;
            // nibble 0 = black (transparent color is handled by tile nibble==0 anyway)
            writeRgb888ToPal(vpu, (bank << 4) | 0, 0x000000);

            for (int n = 1; n < 16; n++) {
                double v = n / 15.0;          // 0..1
                int rgb = hsvToRgb888(hue, 0.85, 0.20 + 0.80 * v);
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

        int r = (int) Math.round((r1 + m) * 255.0);
        int g = (int) Math.round((g1 + m) * 255.0);
        int b = (int) Math.round((b1 + m) * 255.0);

        r = clamp(r, 0, 255);
        g = clamp(g, 0, 255);
        b = clamp(b, 0, 255);

        return (r << 16) | (g << 8) | b;
    }

    private static void writeRgb888ToPal(VPU_v2 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // OAM write
    // ---------------------------------------------------------------------

    private static void writeSprite(
            VPU_v2 vpu, int spriteIndex,
            int x, int y, int tile, int palBank,
            boolean hflip, boolean vflip, boolean enable
    ) {
        int base = OAM_BASE + (spriteIndex * OAM_STRIDE);

        int xClamped = clamp(x, 0, 1023);
        int yClamped = clamp(y, 0, 1023);

        int xLo = xClamped & 0xFF;
        int yLo = yClamped & 0xFF;
        int xyhi = ((xClamped >> 8) & 0x03) | (((yClamped >> 8) & 0x03) << 2);

        int attr = (palBank & 0x0F) << 4;
        if (enable) attr |= 0x01;
        if (hflip)  attr |= 0x02;
        if (vflip)  attr |= 0x04;

        vpu.writeMmio(base + 0, (byte) yLo);
        vpu.writeMmio(base + 1, (byte) xLo);
        vpu.writeMmio(base + 2, (byte) (tile & 0xFF));
        vpu.writeMmio(base + 3, (byte) (attr & 0xFF));
        vpu.writeMmio(base + 4, (byte) (xyhi & 0xFF));
        vpu.writeMmio(base + 5, (byte) 0);
        vpu.writeMmio(base + 6, (byte) 0);
        vpu.writeMmio(base + 7, (byte) 0);
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