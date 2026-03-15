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
 * VPU v3 demo: 128 sprites enabled simultaneously in LORES coordinate space (320x200 -> 640x400).
 *
 * This version matches the "Lynx-style" VPU_v3 sprite engine:
 *  - OAM stride = 16 bytes
 *  - Each sprite has WIDTH/HEIGHT + 16-bit DATA pointer into the 16K sprite bank
 *  - Palette bank is a dedicated byte (PAL: 0..15), final CLUT index = (PAL<<4)|pixNibble
 *  - Sprite coords are in source space (HIRES: 640x200, LORES: 320x200)
 */
public final class Vpu3Demo128SpritesLoresMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU v3 MMIO ----
    private static final int REG_CTRL      = 0x0000;
    private static final int REG_MODE      = 0x0002;   // bit0: 0=HIRES, 1=LORES
    private static final int REG_TX_CTRL   = 0x0007;   // keep off
    private static final int REG_SPR_CTRL  = 0x0039;   // bit0 enable sprites
    private static final int PAL_BASE      = 0x0100;

    private static final int OAM_BASE    = 0x0300;
    private static final int OAM_STRIDE  = 16;

    // CTRL bits (v3)
    private static final int CTRL_ENABLE  = 0x01;
    private static final int CTRL_GFX_DIS = 0x08;

    // Sprite bank is exposed via writeVramPlane(bank=8, offset)
    private static final int SPR_BANK = 8;
    private static final int TILE_BYTES = 32; // 8x8 4bpp packed

    // LORES source resolution (coordinate space for sprites)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;

    // 128 sprites arranged 16x8 in source space
    private static final int GRID_COLS = 16;
    private static final int GRID_ROWS = 8;
    private static final int SPRITES = GRID_COLS * GRID_ROWS; // 128

    // spacing in SOURCE pixels (8x8 sprites)
    private static final int DX = 20;
    private static final int DY = 23;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // cached base positions (source pixels)
    private final int[] baseX = new int[SPRITES];
    private final int[] baseY = new int[SPRITES];

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU v3 Demo: 128 Sprites (LORES coords) — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu3-128sprites-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v3 vpu, int cyclesPerFrame) {
        // LORES coordinate space + sprites on + background off
        vpu.writeMmio(REG_MODE, (byte) 0x01);                 // LORES (320x200 coords)
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00);              // text off
        vpu.writeMmio(REG_SPR_CTRL, (byte) 0x01);             // sprites on
        vpu.writeMmio(REG_CTRL, (byte) (CTRL_ENABLE | CTRL_GFX_DIS)); // solid pal[0] underlay

        installPalette(vpu);
        buildTilesIntoSpriteBank(vpu);

        initGridBasePositions();

        // Init all sprites once (data pointer/pal/enabled). Per-frame update only X/Y + palette.
        for (int i = 0; i < SPRITES; i++) {
            int tile = i & 0x0F;           // 0..15
            int palBank = (i >> 3) & 0x0F; // 0..15
            writeSpriteFull(vpu, i, baseX[i], baseY[i], 8, 8, tile, palBank, false, false, true);
        }

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        double t = 0.0;

        // stable pacing target ~60Hz
        final long frameNanosTarget = 1_000_000_000L / 60L;
        long nextFrame = System.nanoTime();

        while (running.get()) {
            t += 0.06;

            for (int i = 0; i < SPRITES; i++) {
                int c = i & 15;
                int r = i >> 4;

                int ox = (int) Math.round(Math.sin(t + c * 0.35 + r * 0.20) * 6.0);
                int oy = (int) Math.round(Math.cos(t * 1.1 + c * 0.25 + r * 0.45) * 5.0);

                int x = clamp(baseX[i] + ox, 0, SRC_W - 8);
                int y = clamp(baseY[i] + oy, 0, SRC_H - 8);

                int palBank = (i + (int) (t * 2)) & 0x0F; // slowly cycles 0..15

                // update only x/y + palette bank
                writeSpriteXYPal(vpu, i, x, y, palBank, true);
            }

            tickExactlyOneFrame(vpu, cyclesPerFrame);

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU v3 Demo: 128 Sprites — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            // pace
            nextFrame += frameNanosTarget;
            long sleep = nextFrame - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else nextFrame = System.nanoTime();
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    private void initGridBasePositions() {
        final int spriteW = 8;
        final int spriteH = 8;

        final int gridW = (GRID_COLS - 1) * DX + spriteW;
        final int gridH = (GRID_ROWS - 1) * DY + spriteH;

        final int originX = (SRC_W - gridW) / 2;
        final int originY = (SRC_H - gridH) / 2;

        int idx = 0;
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                baseX[idx] = originX + c * DX;
                baseY[idx] = originY + r * DY;
                idx++;
            }
        }
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
    // Tiles: write into sprite bank (bank=8)
    // ---------------------------------------------------------------------

    private static void buildTilesIntoSpriteBank(VPU_v3 vpu) {
        for (int t = 0; t < 16; t++) {
            int nibA = (t == 0) ? 1 : (t & 0x0F);
            int nibB = (nibA ^ 0x0F) & 0x0F;
            writeCheckerTileToSpriteBank(vpu, t, nibA, nibB);
        }
    }

    private static void writeCheckerTileToSpriteBank(VPU_v3 vpu, int tileIndex, int aNibble, int bNibble) {
        int base = tileIndex * TILE_BYTES; // offset inside sprite bank
        for (int y = 0; y < 8; y++) {
            for (int xPair = 0; xPair < 4; xPair++) {
                int x0 = xPair * 2;
                int p0 = (((x0 + y) & 1) == 0) ? aNibble : bNibble;
                int p1 = (((x0 + 1 + y) & 1) == 0) ? aNibble : bNibble;

                if (p0 == 0) p0 = 1;
                if (p1 == 0) p1 = 1;

                int packed = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                vpu.writeVramPlane(SPR_BANK, base + (y * 4) + xPair, (byte) packed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Palette: 16 banks × 16 colors (index=(bank<<4)|nibble)
    // ---------------------------------------------------------------------

    private static void installPalette(VPU_v3 vpu) {
        int[] vga16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, vga16[i]);

        for (int bank = 1; bank < 16; bank++) {
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
    // OAM writes (v3 Lynx-style sprite format)
    // ---------------------------------------------------------------------

    private static void writeSpriteFull(
            VPU_v3 vpu,
            int spriteIndex,
            int x,
            int y,
            int w,
            int h,
            int tileIndex,
            int palBank,
            boolean hflip,
            boolean vflip,
            boolean enable
    ) {
        int base = OAM_BASE + (spriteIndex * OAM_STRIDE);

        int xClamped = clamp(x, 0, 1023);
        int yClamped = clamp(y, 0, 1023);

        int xLo = xClamped & 0xFF;
        int yLo = yClamped & 0xFF;
        int xyhi = ((xClamped >> 8) & 0x03) | (((yClamped >> 8) & 0x03) << 2);

        // ATTR bits
        int attr = 0;
        if (enable) attr |= 0x01;
        if (hflip)  attr |= 0x02;
        if (vflip)  attr |= 0x04;
        // bit3 PRIO optional; leave 0
        // bit4 SCALE_EN optional; leave 0
        // bit5 COLLIDE optional; leave 0
        // bit6 CHAIN optional; leave 0

        int pal = palBank & 0x0F;

        // SCALE/COL byte: [1:0]=scaleX log2, [3:2]=scaleY log2, [7:4]=COL_ID
        int scaleCol = 0x00; // no extra scaling, COL_ID=0

        int dataPtr = (tileIndex * TILE_BYTES) & 0xFFFF;

        vpu.writeMmio(base + 0, (byte) yLo);
        vpu.writeMmio(base + 1, (byte) xLo);
        vpu.writeMmio(base + 2, (byte) (xyhi & 0xFF));
        vpu.writeMmio(base + 3, (byte) (w & 0xFF));
        vpu.writeMmio(base + 4, (byte) (h & 0xFF));
        vpu.writeMmio(base + 5, (byte) (attr & 0xFF));
        vpu.writeMmio(base + 6, (byte) (pal & 0xFF));
        vpu.writeMmio(base + 7, (byte) (scaleCol & 0xFF));
        vpu.writeMmio(base + 8, (byte) (dataPtr & 0xFF));
        vpu.writeMmio(base + 9, (byte) ((dataPtr >>> 8) & 0xFF));
        vpu.writeMmio(base + 10, (byte) 0x00); // LINK
        vpu.writeMmio(base + 11, (byte) 0x00);
        vpu.writeMmio(base + 12, (byte) 0x00);
        vpu.writeMmio(base + 13, (byte) 0x00);
        vpu.writeMmio(base + 14, (byte) 0x00);
        vpu.writeMmio(base + 15, (byte) 0x00);
    }

    private static void writeSpriteXYPal(VPU_v3 vpu, int spriteIndex, int x, int y, int palBank, boolean enable) {
        int base = OAM_BASE + (spriteIndex * OAM_STRIDE);

        int xClamped = clamp(x, 0, 1023);
        int yClamped = clamp(y, 0, 1023);

        int xLo = xClamped & 0xFF;
        int yLo = yClamped & 0xFF;
        int xyhi = ((xClamped >> 8) & 0x03) | (((yClamped >> 8) & 0x03) << 2);

        int attr = enable ? 0x01 : 0x00;
        int pal = palBank & 0x0F;

        vpu.writeMmio(base + 0, (byte) yLo);
        vpu.writeMmio(base + 1, (byte) xLo);
        vpu.writeMmio(base + 2, (byte) (xyhi & 0xFF));
        vpu.writeMmio(base + 5, (byte) (attr & 0xFF));
        vpu.writeMmio(base + 6, (byte) (pal & 0xFF));
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
