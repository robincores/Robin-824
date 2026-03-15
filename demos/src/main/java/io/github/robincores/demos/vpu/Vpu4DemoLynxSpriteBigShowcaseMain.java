package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v4 demo: BIG "Lynx-ish" Sprite Showcase (easy-to-see sprites and effects).
 *
 * Shows:
 *  - big shaded ball (32x32 source) with 8.8 scaling (pulsing 2x..4x)
 *  - big segmented banner (3x 32x16) with tilt/skew
 *  - big worm chain (12x 16x16 segments) with CHAIN + per-segment scaling
 *  - sparkles (16x16) pulsing size
 *  - per-sprite 8-bit priority interleaving with 3 background groups
 *  - collision flags (ball vs banner)
 *
 * Notes:
 *  - Background playfields are LORES groups (320x200 doubled to 640x400 output).
 *  - Sprite X is in output pixels; Sprite Y is in source lines (0..199).
 *  - This demo only uses VPU MMIO + VPU bank writes (like a real R816 program would).
 */
public final class Vpu4DemoLynxSpriteBigShowcaseMain extends Application {

    // ---------------------------------------------------------------------
    // Display config / timing
    // ---------------------------------------------------------------------

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private static final long FRAME_NANOS_TARGET = 1_000_000_000L / 60L;

    // ---------------------------------------------------------------------
    // MMIO + banks
    // ---------------------------------------------------------------------

    private static final int REG_CTRL  = 0x0000;

    // Text ctrl moved across revisions; write multiple candidates to force text off.
    private static final int REG_TX_CTRL_A = 0x0006;
    private static final int REG_TX_CTRL_B = 0x0007;
    private static final int REG_TX_CTRL_C = 0x0008;

    private static final int PAL_BASE   = 0x0100;

    private static final int GROUP_BASE   = 0x0400;
    private static final int GROUP_STRIDE = 0x0010;

    // Sprite enable register moved in earlier drafts; write both.
    private static final int REG_SPR_CTRL_A = 0x0014;
    private static final int REG_SPR_CTRL_B = 0x0039;

    // Collision flags (128 sprites => 16 bytes)
    private static final int COL_BASE = 0x0300;

    // CTRL bits
    private static final int CTRL_ENABLE = 0x01;

    // SPR_CTRL bits
    private static final int SPR_EN = 0x01;

    // Group flags
    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;
    private static final int GF_OPAQUE0 = 0x08;

    // Banks
    private static final int VB_SPR = 8;
    private static final int VB_TBL = 9;

    // Sprite OAM format (v4-lite)
    private static final int SPR_COUNT  = 128;
    private static final int SPR_STRIDE = 32;

    // ATTR bits (v4-lite implementation)
    private static final int SA_EN      = 0x01;
    private static final int SA_HFLIP   = 0x02;
    private static final int SA_VFLIP   = 0x04;
    private static final int SA_CHAIN   = 0x08;
    private static final int SA_COLLIDE = 0x10;
    private static final int SA_TILT_EN = 0x20;

    // World geometry (LORES groups)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL = SRC_W / 8; // 40 bytes per row per plane

    // Sprite pattern offsets in VBANK=8 (byte offsets)
    private int ofsBall32;
    private int ofsWorm16;
    private int ofsSpark16;
    private int ofsBannerSeg32x16_A;
    private int ofsBannerSeg32x16_B;
    private int ofsBannerSeg32x16_C;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    private final Random rng = new Random(0xB16B00B5);

    // Sparkles state
    private static final int SPARK_COUNT = 20;
    private final int[] sparkX = new int[SPARK_COUNT];
    private final int[] sparkY = new int[SPARK_COUNT];
    private final double[] sparkPhase = new double[SPARK_COUNT];

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);
        stage.setScene(new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Demo: BIG Lynx-ish Sprite Showcase");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu4-lynx-big-sprite-showcase");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v4 vpu, int cyclesPerFrame) {
        // Enable VPU
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);

        // Force text off
        vpu.writeMmio(REG_TX_CTRL_A, (byte) 0x00);
        vpu.writeMmio(REG_TX_CTRL_B, (byte) 0x00);
        vpu.writeMmio(REG_TX_CTRL_C, (byte) 0x00);

        // Enable sprites
        vpu.writeMmio(REG_SPR_CTRL_A, (byte) SPR_EN);
        vpu.writeMmio(REG_SPR_CTRL_B, (byte) SPR_EN);

        installPalette(vpu);
        initGroups(vpu);
        fillBackgroundPlanes(vpu);

        // Sprite patterns
        allocateSpritePatterns(vpu);

        // Clear OAM region in tables bank
        for (int i = 0; i < SPR_COUNT * SPR_STRIDE; i++) {
            vpu.writeVramPlane(VB_TBL, i, (byte) 0);
        }

        // Sprite indices used
        final int SPR_BALL = 0;
        final int SPR_BANNER0 = 4;      // 3 segments
        final int BANNER_SEGS = 3;
        final int SPR_WORM0 = 16;       // 12 segments
        final int WORM_SEGS = 12;
        final int SPR_SPARK0 = 64;      // 20 sparkles

        // Banner: 3 segments chained as one head
        for (int i = 0; i < BANNER_SEGS; i++) {
            int si = SPR_BANNER0 + i;
            int attr = SA_EN | SA_TILT_EN | SA_COLLIDE;
            if (i < BANNER_SEGS - 1) attr |= SA_CHAIN;

            int data;
            if (i == 0) data = ofsBannerSeg32x16_A;
            else if (i == 1) data = ofsBannerSeg32x16_B;
            else data = ofsBannerSeg32x16_C;

            // place segments adjacent in output space
            int x = outX(40 + i * 32);
            int y = 22;

            writeSpriteFull(vpu, si,
                    x, y,
                    32, 16, data,
                    attr,
                    /*pal*/ 5, /*colId*/ 2,
                    /*prio*/ 210,
                    stepFromScale(2.0), stepFromScale(2.0),
                    /*tiltDx*/ 0,
                    /*link*/ (i < BANNER_SEGS - 1) ? (si + 1) : 0);
        }

        // Ball: big and obvious
        writeSpriteFull(vpu, SPR_BALL,
                outX(160), 60,
                32, 32, ofsBall32,
                SA_EN | SA_COLLIDE,
                /*pal*/ 3, /*colId*/ 1,
                /*prio*/ 140,
                stepFromScale(3.0), stepFromScale(3.0),
                /*tiltDx*/ 0,
                0);

        // Worm: 12 segments chained
        for (int i = 0; i < WORM_SEGS; i++) {
            int si = SPR_WORM0 + i;
            int attr = SA_EN | SA_CHAIN;
            if (i == WORM_SEGS - 1) attr = SA_EN;
            if (i == 0) attr |= SA_COLLIDE;

            int link = (i == WORM_SEGS - 1) ? 0 : (si + 1);

            writeSpriteFull(vpu, si,
                    outX(60 + i * 18), 140,
                    16, 16, ofsWorm16,
                    attr,
                    /*pal*/ 10, /*colId*/ 3,
                    /*prio*/ 90,
                    stepFromScale(2.4), stepFromScale(2.4),
                    /*tiltDx*/ 0,
                    link);
        }

        // Sparkles: bigger and fewer, visible
        for (int i = 0; i < SPARK_COUNT; i++) {
            sparkX[i] = 30 + rng.nextInt(SRC_W - 60);
            sparkY[i] = 20 + rng.nextInt(SRC_H - 40);
            sparkPhase[i] = rng.nextDouble() * Math.PI * 2.0;

            int si = SPR_SPARK0 + i;
            int pr = (i % 2 == 0) ? 155 : 235;

            writeSpriteFull(vpu, si,
                    outX(sparkX[i]), sparkY[i],
                    16, 16, ofsSpark16,
                    SA_EN,
                    /*pal*/ (1 + (i & 0x0F)), /*colId*/ 0,
                    pr,
                    stepFromScale(2.0), stepFromScale(2.0),
                    0,
                    0);
        }

        // Animation loop
        long next = System.nanoTime();
        long fpsT0 = next;
        int fpsFrames = 0;

        double t = 0.0;

        while (running.get()) {
            t += 1.0 / 60.0;

            // Parallax
            groupScrollX(vpu, 0, (int) (t * 24.0));
            groupScrollX(vpu, 1, (int) (-t * 55.0));
            groupScrollX(vpu, 2, (int) (t * 10.0));

            // Banner tilt (visible!)
            int tilt = (int) Math.round(Math.sin(t * 1.5) * 4.0); // -4..4 px per source row
            for (int i = 0; i < BANNER_SEGS; i++) {
                writeSpriteTilt(vpu, SPR_BANNER0 + i, (byte) tilt);
            }

            // Ball orbit + big scaling pulse
            int cx = 160;
            int cy = 92;
            double ox = Math.sin(t * 1.10) * 110.0;
            double oy = Math.cos(t * 0.90) * 70.0;

            int bx = clamp((int) Math.round(cx + ox), 0, SRC_W - 32);
            int by = clamp((int) Math.round(cy + oy), 0, SRC_H - 32);

            // Height -> scale (2.0 .. 4.0) and priority (go behind grid sometimes)
            double height = 1.0 - (by / 200.0);
            double sBall = 2.0 + height * 2.0;

            int prBall = (by < 70) ? 120 : 185;

            writeSpriteXYStepsPrio(vpu, SPR_BALL, outX(bx), by,
                    stepFromScale(sBall), stepFromScale(sBall * 0.92), prBall);

            // Worm follows ball; segments shrink, alternate flip
            int headX = bx - 80;
            int headY = by + 70;

            for (int i = 0; i < WORM_SEGS; i++) {
                int si = SPR_WORM0 + i;

                int tx = headX - i * 18;
                int ty = headY + (int) Math.round(Math.sin(t * 2.0 + i * 0.55) * 10.0);

                double s = 2.6 - i * 0.12;
                if (s < 1.1) s = 1.1;

                int pr = 70 + i * 7;

                writeSpriteXYStepsPrio(vpu, si, outX(tx), clamp(ty, 0, SRC_H - 16),
                        stepFromScale(s), stepFromScale(s), pr);

                int attr = readOam8(vpu, si, 8);
                if ((i & 1) == 1) attr |= SA_HFLIP;
                else attr &= ~SA_HFLIP;
                writeOam8(vpu, si, 8, attr);
            }

            // Sparkles pulse and drift slightly
            for (int i = 0; i < SPARK_COUNT; i++) {
                int si = SPR_SPARK0 + i;

                int wobX = (int) Math.round(Math.sin(t * 3.0 + sparkPhase[i]) * 3.0);
                int wobY = (int) Math.round(Math.cos(t * 2.2 + sparkPhase[i]) * 2.0);

                writeOam16(vpu, si, 0, outX(sparkX[i] + wobX));
                writeOam16(vpu, si, 2, clamp(sparkY[i] + wobY, 0, SRC_H - 16));

                double s = 1.6 + Math.sin(t * 2.8 + sparkPhase[i]) * 0.9; // 0.7..2.5-ish
                if (s < 0.7) s = 0.7;
                if (s > 3.0) s = 3.0;
                int st = stepFromScale(s);
                writeOam16(vpu, si, 12, st);
                writeOam16(vpu, si, 14, st);
            }

            // Run one frame
            tickExactlyOneFrame(vpu, cyclesPerFrame);

            // Collisions
            int hits = countHitSprites(vpu);

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                double fps = (fpsFrames * 1_000_000_000.0) / (now - fpsT0);
                final int hitsForTitle = hits;
                final String title = String.format("VPU v4 Demo: BIG Lynx-ish Sprite Showcase — FPS %.1f | Hits %d", fps, hitsForTitle);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            // Pace
            next += FRAME_NANOS_TARGET;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else next = System.nanoTime();
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Groups / background
    // ---------------------------------------------------------------------

    private static void initGroups(VPU_v4 vpu) {
        // Group0: background checker (planes 0..1), opaque, priority 40
        writeGroup(vpu, 0,
                0, 2,
                0,
                0, 0,
                40,
                (GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0),
                0);

        // Group1: grid overlay (planes 2..3), transparent 0, priority 150
        writeGroup(vpu, 1,
                2, 2,
                32,
                0, 0,
                150,
                (GF_LORES | GF_WRAP_X | GF_WRAP_Y),
                0);

        // Group2: diagonal stripes (plane 4), transparent 0, priority 230
        writeGroup(vpu, 2,
                4, 1,
                48,
                0, 0,
                230,
                (GF_LORES | GF_WRAP_X | GF_WRAP_Y),
                0);

        // Disable group3
        vpu.writeMmio(GROUP_BASE + 3 * GROUP_STRIDE + 1, (byte) 0);
    }

    private static void writeGroup(VPU_v4 vpu, int gi,
                                  int planeStart, int planeCount, int palBase,
                                  int scrollX, int scrollY,
                                  int priority, int flags, int bplOfs) {
        int base = GROUP_BASE + gi * GROUP_STRIDE;
        vpu.writeMmio(base + 0, (byte) (planeStart & 0xFF));
        vpu.writeMmio(base + 1, (byte) (planeCount & 0xFF));
        vpu.writeMmio(base + 2, (byte) (palBase & 0xFF));
        writeMmio16(vpu, base + 3, scrollX & 0xFFFF);
        vpu.writeMmio(base + 5, (byte) (scrollY & 0xFF));
        vpu.writeMmio(base + 6, (byte) (priority & 0xFF));
        vpu.writeMmio(base + 7, (byte) (flags & 0xFF));
        writeMmio16(vpu, base + 8, bplOfs & 0x3FFF);
    }

    private static void groupScrollX(VPU_v4 vpu, int gi, int scrollX) {
        int base = GROUP_BASE + gi * GROUP_STRIDE;
        writeMmio16(vpu, base + 3, scrollX & 0xFFFF);
    }

    private static void fillBackgroundPlanes(VPU_v4 vpu) {
        // Planes 0..1: 2-bit checker
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int x0 = bx << 3;
                int b0 = 0;
                int b1 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    int idx2 = (((x >> 4) + (y >> 4)) & 3);
                    int bit = 1 << (7 - i);
                    if ((idx2 & 1) != 0) b0 |= bit;
                    if ((idx2 & 2) != 0) b1 |= bit;
                }
                vpu.writeVramPlane(0, row + bx, (byte) b0);
                vpu.writeVramPlane(1, row + bx, (byte) b1);
            }
        }

        // Planes 2..3: grid lines
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            boolean hLine = (y % 16) == 0;
            for (int bx = 0; bx < BPL; bx++) {
                int x0 = bx << 3;
                int b2 = 0;
                int b3 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    boolean vLine = (x % 16) == 0;
                    int idx = (hLine || vLine) ? 1 : 0;
                    int bit = 1 << (7 - i);
                    if ((idx & 1) != 0) b2 |= bit;
                    if ((idx & 2) != 0) b3 |= bit;
                }
                vpu.writeVramPlane(2, row + bx, (byte) b2);
                vpu.writeVramPlane(3, row + bx, (byte) b3);
            }
        }

        // Plane 4: diagonal stripes
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int x0 = bx << 3;
                int b4 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    boolean stripe = (((x + y) >> 3) & 1) == 0;
                    int bit = 1 << (7 - i);
                    if (stripe) b4 |= bit;
                }
                vpu.writeVramPlane(4, row + bx, (byte) b4);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Palette
    // ---------------------------------------------------------------------

    private static void installPalette(VPU_v4 vpu) {
        // Background
        writeRgb888ToPal(vpu, 0, 0x05080B);
        writeRgb888ToPal(vpu, 1, 0x0A1020);
        writeRgb888ToPal(vpu, 2, 0x122030);
        writeRgb888ToPal(vpu, 3, 0x1E3850);

        // Grid
        writeRgb888ToPal(vpu, 32, 0x000000);
        writeRgb888ToPal(vpu, 33, 0x66CCFF);

        // Stripes
        writeRgb888ToPal(vpu, 48, 0x000000);
        writeRgb888ToPal(vpu, 49, 0xFFE090);

        // Sprite palette banks
        for (int bank = 0; bank < 16; bank++) {
            double hue = (bank / 16.0) * 360.0;
            writeRgb888ToPal(vpu, (bank << 4) | 0, 0x000000);
            for (int n = 1; n < 16; n++) {
                double vv = n / 15.0;
                int rgb = hsvToRgb888(hue, 0.85, 0.22 + 0.78 * vv);
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
    // Sprite patterns (4bpp packed, row-major)
    // ---------------------------------------------------------------------

    private void allocateSpritePatterns(VPU_v4 vpu) {
        int ofs = 0;

        // Big shaded ball 32x32
        ofsBall32 = ofs;
        ofs = writeBall(vpu, ofs, 32, 32);

        // Worm segment 16x16
        ofsWorm16 = ofs;
        ofs = writeWormSegment(vpu, ofs, 16, 16);

        // Sparkle 16x16
        ofsSpark16 = ofs;
        ofs = writeSparkle(vpu, ofs, 16, 16);

        // Banner segments 32x16 (three different gradients)
        ofsBannerSeg32x16_A = ofs;
        ofs = writeBannerSegment(vpu, ofs, 32, 16, 0);
        ofsBannerSeg32x16_B = ofs;
        ofs = writeBannerSegment(vpu, ofs, 32, 16, 1);
        ofsBannerSeg32x16_C = ofs;
        ofs = writeBannerSegment(vpu, ofs, 32, 16, 2);

        // Clear remainder
        for (int i = ofs; i < 0x4000; i++) vpu.writeVramPlane(VB_SPR, i, (byte) 0);
    }

    private static int writeBall(VPU_v4 vpu, int ofs, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = ballShadeNibble(x0, y, w, h);
                int p1 = ballShadeNibble(x0 + 1, y, w, h);
                vpu.writeVramPlane(VB_SPR, ofs++ & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int ballShadeNibble(int x, int y, int w, int h) {
        double cx = (w - 1) * 0.5;
        double cy = (h - 1) * 0.5;
        double dx = (x - cx) / cx;
        double dy = (y - cy) / cy;
        double rr = dx * dx + dy * dy;
        if (rr > 1.0) return 0;

        double nz = Math.sqrt(1.0 - rr);
        double lx = -0.40, ly = -0.30, lz = 0.86;
        double diff = dx * lx + dy * ly + nz * lz;
        if (diff < 0) diff = 0;

        int shade = 1 + (int) Math.round(diff * 13.0);
        if (shade < 1) shade = 1;
        if (shade > 15) shade = 15;

        if (rr > 0.90) shade = 15; // outline
        return shade;
    }

    private static int writeWormSegment(VPU_v4 vpu, int ofs, int w, int h) {
        double cx = (w - 1) * 0.5;
        double cy = (h - 1) * 0.5;
        double r = Math.min(cx, cy);

        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = wormNibble(x0, y, cx, cy, r);
                int p1 = wormNibble(x0 + 1, y, cx, cy, r);
                vpu.writeVramPlane(VB_SPR, ofs++ & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int wormNibble(int x, int y, double cx, double cy, double r) {
        double dx = (x - cx) / r;
        double dy = (y - cy) / r;
        double rr = dx * dx + dy * dy;
        if (rr > 1.0) return 0;

        // simple "bead" shading
        double nz = Math.sqrt(1.0 - rr);
        double lx = -0.20, ly = -0.40, lz = 0.90;
        double diff = dx * lx + dy * ly + nz * lz;
        if (diff < 0) diff = 0;
        int shade = 2 + (int) Math.round(diff * 12.0);
        if (shade > 15) shade = 15;
        if (rr > 0.92) shade = 15;
        return shade;
    }

    private static int writeSparkle(VPU_v4 vpu, int ofs, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = sparkleNibble(x0, y, cx, cy);
                int p1 = sparkleNibble(x0 + 1, y, cx, cy);
                vpu.writeVramPlane(VB_SPR, ofs++ & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int sparkleNibble(int x, int y, int cx, int cy) {
        int dx = Math.abs(x - cx);
        int dy = Math.abs(y - cy);
        boolean arm = (dx == 0 && dy <= 6) || (dy == 0 && dx <= 6);
        boolean diag = (dx == dy && dx <= 4);
        if (arm || diag) return 15;
        return 0;
    }

    private static int writeBannerSegment(VPU_v4 vpu, int ofs, int w, int h, int which) {
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = bannerNibble(x0, y, w, h, which);
                int p1 = bannerNibble(x0 + 1, y, w, h, which);
                vpu.writeVramPlane(VB_SPR, ofs++ & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int bannerNibble(int x, int y, int w, int h, int which) {
        boolean border = (y == 0 || y == h - 1 || x == 0 || x == w - 1);
        if (border) return 15;

        // three different phase-shifted waves so segments don't look identical
        double t = x / (double) (w - 1);
        double ph = which * 0.9;
        double wave = 0.5 + 0.5 * Math.sin((t * Math.PI * 1.25) + ph);
        double wave2 = 0.5 + 0.5 * Math.sin((t * Math.PI * 2.2) - ph * 0.5);

        int shade = 2 + (int) Math.round(12.0 * (0.65 * wave + 0.35 * wave2));
        if (shade < 1) shade = 1;
        if (shade > 14) shade = 14;

        // inner highlight band
        if (y == (h / 2) && (x % 6) < 4) shade = 15;

        return shade;
    }

    // ---------------------------------------------------------------------
    // Sprite OAM helpers (tables bank)
    // ---------------------------------------------------------------------

    private static void writeSpriteFull(VPU_v4 vpu,
                                        int si,
                                        int xOut, int ySrc,
                                        int w, int h, int dataOfs,
                                        int attr, int pal, int colId, int prio,
                                        int xstep, int ystep,
                                        int tiltDx,
                                        int link) {
        int o = si * SPR_STRIDE;

        writeTbl16(vpu, o + 0, xOut);
        writeTbl16(vpu, o + 2, ySrc);
        vpu.writeVramPlane(VB_TBL, o + 4, (byte) (w & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + 5, (byte) (h & 0xFF));
        writeTbl16(vpu, o + 6, dataOfs & 0x3FFF);

        vpu.writeVramPlane(VB_TBL, o + 8, (byte) (attr & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + 9, (byte) (pal & 0x0F));
        vpu.writeVramPlane(VB_TBL, o + 10, (byte) (colId & 0x0F));
        vpu.writeVramPlane(VB_TBL, o + 11, (byte) (prio & 0xFF));

        writeTbl16(vpu, o + 12, xstep & 0xFFFF);
        writeTbl16(vpu, o + 14, ystep & 0xFFFF);

        vpu.writeVramPlane(VB_TBL, o + 16, (byte) (tiltDx & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + 17, (byte) (link & 0x7F));
    }

    private static void writeSpriteXYStepsPrio(VPU_v4 vpu, int si, int xOut, int ySrc, int xstep, int ystep, int prio) {
        int o = si * SPR_STRIDE;
        writeTbl16(vpu, o + 0, xOut);
        writeTbl16(vpu, o + 2, ySrc);
        writeTbl16(vpu, o + 12, xstep & 0xFFFF);
        writeTbl16(vpu, o + 14, ystep & 0xFFFF);
        vpu.writeVramPlane(VB_TBL, o + 11, (byte) (prio & 0xFF));
    }

    private static void writeSpriteTilt(VPU_v4 vpu, int si, byte tiltDx) {
        int o = si * SPR_STRIDE;
        vpu.writeVramPlane(VB_TBL, o + 16, tiltDx);
    }

    private static void writeTbl16(VPU_v4 vpu, int addr, int value) {
        vpu.writeVramPlane(VB_TBL, addr & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeVramPlane(VB_TBL, (addr + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
    }

    private static void writeMmio16(VPU_v4 vpu, int addr, int value) {
        vpu.writeMmio(addr & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeMmio((addr + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
    }

    private static int readOam8(VPU_v4 vpu, int si, int fieldOfs) {
        int o = si * SPR_STRIDE + fieldOfs;
        return vpu.readVramPlane(VB_TBL, o) & 0xFF;
    }

    private static void writeOam8(VPU_v4 vpu, int si, int fieldOfs, int value) {
        int o = si * SPR_STRIDE + fieldOfs;
        vpu.writeVramPlane(VB_TBL, o, (byte) (value & 0xFF));
    }

    private static void writeOam16(VPU_v4 vpu, int si, int fieldOfs, int value) {
        int o = si * SPR_STRIDE + fieldOfs;
        vpu.writeVramPlane(VB_TBL, o, (byte) (value & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + 1, (byte) ((value >>> 8) & 0xFF));
    }

    private static int countHitSprites(VPU_v4 vpu) {
        int hits = 0;
        for (int i = 0; i < 16; i++) {
            int b = vpu.readMmio(COL_BASE + i) & 0xFF;
            hits += Integer.bitCount(b);
        }
        return hits;
    }

    // ---------------------------------------------------------------------
    // Timing
    // ---------------------------------------------------------------------

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
    // Helpers
    // ---------------------------------------------------------------------

    private static int outX(int loresX) { return loresX << 1; }

    /** scale factor s => step = 256 / s. */
    private static int stepFromScale(double scale) {
        if (scale < 0.20) scale = 0.20;
        if (scale > 6.00) scale = 6.00;
        int step = (int) Math.round(256.0 / scale);
        if (step < 1) step = 1;
        if (step > 0xFFFF) step = 0xFFFF;
        return step;
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : Math.min(v, hi);
    }

    @Override
    public void stop() {
        running.set(false);
        if (fxTimer != null) fxTimer.stop();
        if (emuThread != null) {
            try { emuThread.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
