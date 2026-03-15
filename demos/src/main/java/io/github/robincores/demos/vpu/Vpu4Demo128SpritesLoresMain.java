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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v4 demo: 128 sprites enabled simultaneously in "LORES coordinate space" (320x200),
 * mapped onto the fixed 640x400 output.
 *
 * <p>v4 sprites use:</n * <ul>
 *   <li>OAM in VBANK=9 (tables bank), stride=32</li>
 *   <li>sprite pixels in VBANK=8 (16K sprite bank), 4bpp packed, nibble 0 = transparent</li>
 *   <li>signed16 X in OUTPUT pixels (0..639 typical), signed16 Y in SOURCE lines (0..199 typical)</li>
 *   <li>8.8 fixed-point scaling via XSTEP/YSTEP</li>
 * </ul>
 *
 * <p>To emulate v3 LORES sprite coordinates (320 wide):
 * we store X = xSrc*2 and set XSTEP=0x0080 (2x horizontal magnification).
 * Y stays in 0..199 and YSTEP=0x0100 (1:1 in source-line space; output is already 2x in Y).</p>
 */
public final class Vpu4Demo128SpritesLoresMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU v4 MMIO ----
    private static final int REG_CTRL     = 0x0000;
    private static final int REG_TX_CTRL  = 0x0006; // keep text off
    private static final int REG_SPR_CTRL = 0x0014; // bit0 enable sprites

    private static final int PAL_BASE = 0x0100;

    // CTRL bits (v4)
    private static final int CTRL_ENABLE = 0x01;

    // TX_CTRL bits
    private static final int TX_EN = 0x01;

    // Sprite ctrl bits
    private static final int SPR_EN = 0x01;

    // Banked memory
    private static final int VB_SPR = 8;
    private static final int VB_TBL = 9;

    // OAM format (v4)
    private static final int SPR_STRIDE = 32;

    // 4bpp 8x8 tile
    private static final int TILE_BYTES = 32;

    // "LORES" source coordinate space for this demo
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;

    // 128 sprites arranged 16x8 in source space
    private static final int GRID_COLS = 16;
    private static final int GRID_ROWS = 8;
    private static final int SPRITES = GRID_COLS * GRID_ROWS;

    // spacing in SOURCE pixels (8x8 sprites)
    private static final int DX = 20;
    private static final int DY = 23;

    // Fixed-point scaling
    private static final int XSTEP_2X = 0x0080; // 0.5 src px / out px => 2x magnification
    private static final int YSTEP_1X = 0x0100; // 1.0 src row / src line

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

        stage.setTitle("VPU v4 Demo: 128 Sprites (LORES coords) — FPS --");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu4-128sprites-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v4 vpu, int cyclesPerFrame) {
        // Sprites on, text off, VPU enabled.
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00); // text off
        vpu.writeMmio(REG_SPR_CTRL, (byte) SPR_EN);

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

                writeSpriteXYPal(vpu, i, x, y, palBank, true);
            }

            tickExactlyOneFrame(vpu, cyclesPerFrame);

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU v4 Demo: 128 Sprites — FPS %.1f", fps);
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

    private static void tickExactlyOneFrame(VPU_v4 vpu, int cyclesPerFrame) {
        final int chunk = 5_000;
        int done = 0;
        while (done < cyclesPerFrame) {
            int step = Math.min(chunk, cyclesPerFrame - done);
            vpu.tick(step);
            done += step;
        }
    }

    // ---------------------------------------------------------------------
    // Tiles: write into sprite bank (VBANK=8)
    // ---------------------------------------------------------------------

    private static void buildTilesIntoSpriteBank(VPU_v4 vpu) {
        for (int t = 0; t < 16; t++) {
            int nibA = (t == 0) ? 1 : (t & 0x0F);
            int nibB = (nibA ^ 0x0F) & 0x0F;
            writeCheckerTileToSpriteBank(vpu, t, nibA, nibB);
        }
    }

    private static void writeCheckerTileToSpriteBank(VPU_v4 vpu, int tileIndex, int aNibble, int bNibble) {
        int base = tileIndex * TILE_BYTES; // offset inside sprite bank
        for (int y = 0; y < 8; y++) {
            for (int xPair = 0; xPair < 4; xPair++) {
                int x0 = xPair * 2;
                int p0 = (((x0 + y) & 1) == 0) ? aNibble : bNibble;
                int p1 = (((x0 + 1 + y) & 1) == 0) ? aNibble : bNibble;

                if (p0 == 0) p0 = 1;
                if (p1 == 0) p1 = 1;

                int packed = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                vpu.writeVramPlane(VB_SPR, base + (y * 4) + xPair, (byte) packed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Palette: 16 banks × 16 colors (index=(bank<<4)|nibble)
    // ---------------------------------------------------------------------

    private static void installPalette(VPU_v4 vpu) {
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

    private static void writeRgb888ToPal(VPU_v4 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // OAM writes (v4 sprite format in tables bank)
    // ---------------------------------------------------------------------

    private static void writeSpriteFull(
            VPU_v4 vpu,
            int spriteIndex,
            int xSrc,
            int ySrc,
            int w,
            int h,
            int tileIndex,
            int palBank,
            boolean hflip,
            boolean vflip,
            boolean enable
    ) {
        int base = spriteIndex * SPR_STRIDE;

        // Map "LORES" X (0..319) into output pixels.
        int xOut = clamp(xSrc, 0, SRC_W - 1) * 2;
        int yOut = clamp(ySrc, 0, SRC_H - 1);

        int attr = 0;
        if (enable) attr |= 0x01;
        if (hflip)  attr |= 0x02;
        if (vflip)  attr |= 0x04;

        int dataPtr = (tileIndex * TILE_BYTES) & 0x3FFF;

        // v4 OAM layout:
        // 0..1 X (s16), 2..3 Y (s16), 4 W, 5 H, 6..7 DATA, 8 ATTR, 9 PAL,
        // 10 COL_ID, 11 PRIORITY, 12..13 XSTEP, 14..15 YSTEP, 16 TILT, 17 LINK
        writeTbl16(vpu, base + 0, (short) xOut);
        writeTbl16(vpu, base + 2, (short) yOut);
        vpu.writeVramPlane(VB_TBL, base + 4, (byte) (w & 0xFF));
        vpu.writeVramPlane(VB_TBL, base + 5, (byte) (h & 0xFF));
        writeTbl16(vpu, base + 6, dataPtr);
        vpu.writeVramPlane(VB_TBL, base + 8, (byte) (attr & 0xFF));
        vpu.writeVramPlane(VB_TBL, base + 9, (byte) (palBank & 0x0F));
        vpu.writeVramPlane(VB_TBL, base + 10, (byte) 0x00); // COL_ID
        vpu.writeVramPlane(VB_TBL, base + 11, (byte) 0x80); // PRIORITY (mid)
        writeTbl16(vpu, base + 12, XSTEP_2X);
        writeTbl16(vpu, base + 14, YSTEP_1X);
        vpu.writeVramPlane(VB_TBL, base + 16, (byte) 0x00); // TILT_DX
        vpu.writeVramPlane(VB_TBL, base + 17, (byte) 0x00); // LINK

        // Clear the remaining bytes (not required, but keeps the dump clean)
        for (int i = 18; i < SPR_STRIDE; i++) {
            vpu.writeVramPlane(VB_TBL, base + i, (byte) 0x00);
        }
    }

    private static void writeSpriteXYPal(VPU_v4 vpu, int spriteIndex, int xSrc, int ySrc, int palBank, boolean enable) {
        int base = spriteIndex * SPR_STRIDE;

        int xOut = clamp(xSrc, 0, SRC_W - 1) * 2;
        int yOut = clamp(ySrc, 0, SRC_H - 1);

        int attr = enable ? 0x01 : 0x00;

        writeTbl16(vpu, base + 0, (short) xOut);
        writeTbl16(vpu, base + 2, (short) yOut);
        vpu.writeVramPlane(VB_TBL, base + 8, (byte) (attr & 0xFF));
        vpu.writeVramPlane(VB_TBL, base + 9, (byte) (palBank & 0x0F));
    }

    private static void writeTbl16(VPU_v4 vpu, int addr, int value) {
        int a = addr & 0x3FFF;
        vpu.writeVramPlane(VB_TBL, a, (byte) (value & 0xFF));
        vpu.writeVramPlane(VB_TBL, (a + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
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
