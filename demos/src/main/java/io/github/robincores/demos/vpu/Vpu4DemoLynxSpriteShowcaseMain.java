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
 * VPU v4 demo: "Lynx-ish" Sprite Showcase (pure VPU graphics, no CPU program).
 *
 * Showcases:
 *  - 8.8 fixed-point scaling (arbitrary ratios)
 *  - per-sprite tilt/skew (TILT_DX)
 *  - CHAIN (multi-sprite objects)
 *  - 8-bit per-sprite priority interleaving with playfield groups
 *  - collision flags (read back, shown in the window title)
 *
 * Notes:
 *  - Background playfields are LORES groups (320x200 doubled to 640x400).
 *  - Sprite X is in output pixels (0..639), sprite Y is in source lines (0..199) like the v4 engine.
 *  - This demo avoids relying on text MMIO register addresses (which changed between iterations).
 */
public final class Vpu4DemoLynxSpriteShowcaseMain extends Application {

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
    // MMIO (stable ones) + tables/sprite banks
    // ---------------------------------------------------------------------

    private static final int REG_CTRL       = 0x0000;
    private static final int REG_TX_CTRL    = 0x0006;  // text off for this demo
    private static final int PAL_BASE       = 0x0100;

    private static final int GROUP_BASE     = 0x0400;
    private static final int GROUP_STRIDE   = 0x0010;

    // Sprite enable register moved around in different drafts; we write both.
    private static final int REG_SPR_CTRL_A = 0x0014;
    private static final int REG_SPR_CTRL_B = 0x0039;

    // Collision flags (128 sprites => 16 bytes) - kept stable across v3/v4
    private static final int COL_BASE       = 0x0300;

    // CTRL bits
    private static final int CTRL_ENABLE    = 0x01;

    // SPR_CTRL bits
    private static final int SPR_EN         = 0x01;

    // Group flags
    private static final int GF_LORES       = 0x01;
    private static final int GF_WRAP_X      = 0x02;
    private static final int GF_WRAP_Y      = 0x04;
    private static final int GF_OPAQUE0     = 0x08;

    // Tables bank (VBANK=9) and sprite bank (VBANK=8)
    private static final int VB_SPR = 8;
    private static final int VB_TBL = 9;

    // Sprite OAM format (32-byte stride, 128 sprites)
    private static final int SPR_COUNT  = 128;
    private static final int SPR_STRIDE = 32;

    // ATTR bits (v4-lite implementation)
    private static final int SA_EN       = 0x01;
    private static final int SA_HFLIP    = 0x02;
    private static final int SA_VFLIP    = 0x04;
    private static final int SA_CHAIN    = 0x08;
    private static final int SA_COLLIDE  = 0x10;
    private static final int SA_TILT_EN  = 0x20;

    // World geometry (LORES groups)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL   = SRC_W / 8; // 40 bytes per row per plane

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    // Sprite pattern offsets in VBANK=8
    private int ofsBall16;     // 16x16
    private int ofsArrow8;     // 8x8
    private int ofsSpark8;     // 8x8
    private int ofsBanner32x8; // 32x8

    private final Random rng = new Random(0xC0FFEE);

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);
        stage.setScene(new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Demo: Lynx-ish Sprite Showcase");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "vpu4-lynx-sprite-showcase");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v4 vpu, int cyclesPerFrame) {
        // Enable VPU + sprites
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00);

        vpu.writeMmio(REG_SPR_CTRL_A, (byte) SPR_EN);
        vpu.writeMmio(REG_SPR_CTRL_B, (byte) SPR_EN);

        installPalette(vpu);
        initGroups(vpu);
        fillBackgroundPlanes(vpu);

        // Sprite patterns in 16K sprite bank
        allocateSpritePatterns(vpu);

        // Clear OAM area in table bank
        for (int i = 0; i < SPR_COUNT * SPR_STRIDE; i++) {
            vpu.writeVramPlane(VB_TBL, i, (byte) 0);
        }

        // --- Sprite indices used by this demo ---
        final int SPR_BALL     = 0;               // 1 sprite
        final int SPR_BANNER   = 1;               // 1 sprite
        final int SPR_CHAIN0   = 8;               // 10 chained sprites
        final int CHAIN_LEN    = 10;
        final int SPR_SPARK0   = 32;              // 48 sparkles
        final int SPARK_LEN    = 48;

        // Banner (a long sprite with tilt) - collide with ball
        writeSpriteFull(vpu, SPR_BANNER,
                outX(22), 30,
                32, 8, ofsBanner32x8,
                (SA_EN | SA_TILT_EN | SA_COLLIDE),
                /*pal*/ 5, /*colId*/ 2, /*prio*/ 210,
                stepFromScale(1.2), stepFromScale(1.2),
                /*tiltDx*/ 0,
                /*link*/ 0);

        // Ball (scales with "height")
        writeSpriteFull(vpu, SPR_BALL,
                outX(140), 40,
                16, 16, ofsBall16,
                (SA_EN | SA_COLLIDE),
                /*pal*/ 3, /*colId*/ 1, /*prio*/ 120,
                stepFromScale(1.0), stepFromScale(1.0),
                /*tiltDx*/ 0,
                /*link*/ 0);

        // Chain segments (a "snake" made of arrow tiles)
        for (int i = 0; i < CHAIN_LEN; i++) {
            int si = SPR_CHAIN0 + i;
            int attr = SA_EN | SA_CHAIN;
            if (i == CHAIN_LEN - 1) attr = SA_EN; // tail terminator
            int link = (i == CHAIN_LEN - 1) ? 0 : (si + 1);

            writeSpriteFull(vpu, si,
                    outX(40 + i * 10), 110,
                    8, 8, ofsArrow8,
                    attr,
                    /*pal*/ 8, /*colId*/ 0, /*prio*/ 70,
                    stepFromScale(1.0), stepFromScale(1.0),
                    /*tiltDx*/ 0,
                    link);
        }

        // Sparkles
        for (int i = 0; i < SPARK_LEN; i++) {
            int si = SPR_SPARK0 + i;

            int wx = rng.nextInt(SRC_W - 8);
            int wy = 30 + rng.nextInt(SRC_H - 60);

            // random scale between 0.5x .. 2.0x
            double s = 0.5 + rng.nextDouble() * 1.5;

            // priority: some behind mid group, some in front
            int pr = (i & 1) == 0 ? 145 : 235;

            writeSpriteFull(vpu, si,
                    outX(wx), wy,
                    8, 8, ofsSpark8,
                    SA_EN,
                    /*pal*/ (1 + (i & 0x0F)), /*colId*/ 0, /*prio*/ pr,
                    stepFromScale(s), stepFromScale(s),
                    /*tiltDx*/ 0,
                    0);
        }

        // Animation loop
        long next = System.nanoTime();
        long fpsT0 = next;
        int fpsFrames = 0;

        double t = 0.0;
        int lastHits = 0;

        while (running.get()) {
            t += 1.0 / 60.0;

            // --- Animate groups (parallax) ---
            groupScrollX(vpu, 0, (int) (t * 30.0));
            groupScrollX(vpu, 1, (int) (-t * 70.0));
            groupScrollX(vpu, 2, (int) (t * 12.0));

            // --- Animate ball (orbit + squash/stretch) ---
            int cx = 160;
            int cy = 90;

            double ox = Math.sin(t * 1.30) * 95.0;
            double oy = Math.cos(t * 1.05) * 55.0;

            int bx = clamp((int) Math.round(cx + ox), 0, SRC_W - 16);
            int by = clamp((int) Math.round(cy + oy), 0, SRC_H - 16);

            double height = 1.0 - (by / 200.0);
            double sBall = 0.75 + height * 1.25;

            int xstepBall = stepFromScale(sBall);
            int ystepBall = stepFromScale(0.85 + height * 1.10);

            int prBall = (by < 80) ? 110 : 170;

            writeSpriteXYStepsPrio(vpu, SPR_BALL, outX(bx), by, xstepBall, ystepBall, prBall);

            // --- Animate banner tilt (skew) ---
            int tilt = (int) Math.round(Math.sin(t * 1.7) * 2.0); // -2..2 pixels per source row
            writeSpriteTilt(vpu, SPR_BANNER, (byte) tilt);

            // --- Animate chain following the ball ---
            int headX = bx - 40;
            int headY = by + 40;

            for (int i = 0; i < CHAIN_LEN; i++) {
                int si = SPR_CHAIN0 + i;
                int tx = headX - i * 10;
                int ty = headY + (int) Math.round(Math.sin(t * 2.1 + i * 0.6) * 6.0);

                double s = 1.4 - i * 0.06;
                if (s < 0.7) s = 0.7;

                int xs = stepFromScale(s);
                int ys = stepFromScale(s);

                int pr = 60 + i * 8;
                writeSpriteXYStepsPrio(vpu, si, outX(tx), clamp(ty, 0, SRC_H - 8), xs, ys, pr);

                // alternate flip to make it lively
                int attr = readOam8(vpu, si, 8);
                if ((i & 1) == 1) attr |= SA_HFLIP;
                else attr &= ~SA_HFLIP;
                writeOam8(vpu, si, 8, attr);
            }

            // --- Sparkles: pulse scale and drift slightly ---
            for (int i = 0; i < SPARK_LEN; i++) {
                int si = SPR_SPARK0 + i;

                int x = readOam16(vpu, si, 0);
                int wob = (int) Math.round(Math.sin(t * 3.0 + i * 0.35) * 2.0);
                writeOam16(vpu, si, 0, x + wob);

                double s = 1.1 + Math.sin(t * 2.3 + i * 0.2) * 0.5;
                if (s < 0.6) s = 0.6;
                if (s > 1.6) s = 1.6;
                int st = stepFromScale(s);
                writeOam16(vpu, si, 12, st);
                writeOam16(vpu, si, 14, st);
            }

            // Run one frame
            tickExactlyOneFrame(vpu, cyclesPerFrame);

            // Collision readback (after rendering this frame)
            lastHits = countHitSprites(vpu);

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                double fps = (fpsFrames * 1_000_000_000.0) / (now - fpsT0);
                final int hitsForTitle = lastHits;
                final String title = String.format("VPU v4 Demo: Lynx-ish Sprite Showcase — FPS %.1f | Hits %d", fps, hitsForTitle);
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
                /*planeStart*/0, /*planeCount*/2,
                /*palBase*/0,
                /*scrollX*/0, /*scrollY*/0,
                /*prio*/40,
                (GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0),
                /*bplOfs*/0);

        // Group1: grid overlay (planes 2..3), transparent on index 0, priority 150
        writeGroup(vpu, 1,
                /*planeStart*/2, /*planeCount*/2,
                /*palBase*/32,
                /*scrollX*/0, /*scrollY*/0,
                /*prio*/150,
                (GF_LORES | GF_WRAP_X | GF_WRAP_Y /* no OPAQUE0 */),
                /*bplOfs*/0);

        // Group2: foreground stripes (plane 4), transparent on 0, priority 230
        writeGroup(vpu, 2,
                /*planeStart*/4, /*planeCount*/1,
                /*palBase*/48,
                /*scrollX*/0, /*scrollY*/0,
                /*prio*/230,
                (GF_LORES | GF_WRAP_X | GF_WRAP_Y /* no OPAQUE0 */),
                /*bplOfs*/0);

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
        // Group0 planes (0..1): 2-bit checker pattern
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int x0 = bx << 3;
                int b0 = 0;
                int b1 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    int idx2 = (((x >> 4) + (y >> 4)) & 3); // 0..3
                    int bit = 1 << (7 - i);
                    if ((idx2 & 1) != 0) b0 |= bit;
                    if ((idx2 & 2) != 0) b1 |= bit;
                }
                vpu.writeVramPlane(0, row + bx, (byte) b0);
                vpu.writeVramPlane(1, row + bx, (byte) b1);
            }
        }

        // Group1 planes (2..3): grid lines (index=1 on lines, else 0)
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

        // Group2 plane (4): diagonal stripes (index=1 on stripe, else 0)
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
        // Playfield colors:
        writeRgb888ToPal(vpu, 0, 0x05080B);
        writeRgb888ToPal(vpu, 1, 0x0A1020);
        writeRgb888ToPal(vpu, 2, 0x122030);
        writeRgb888ToPal(vpu, 3, 0x1E3850);

        // Grid (group1 palBase=32, use index 33)
        writeRgb888ToPal(vpu, 32, 0x000000);
        writeRgb888ToPal(vpu, 33, 0x66CCFF);

        // Foreground stripes (group2 palBase=48, use index 49)
        writeRgb888ToPal(vpu, 48, 0x000000);
        writeRgb888ToPal(vpu, 49, 0xFFE090);

        // Sprite palette banks (bank<<4 | nibble)
        for (int bank = 0; bank < 16; bank++) {
            double hue = (bank / 16.0) * 360.0;
            writeRgb888ToPal(vpu, (bank << 4) | 0, 0x000000);
            for (int n = 1; n < 16; n++) {
                double vv = n / 15.0;
                int rgb = hsvToRgb888(hue, 0.85, 0.25 + 0.75 * vv);
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
    // Sprite patterns
    // ---------------------------------------------------------------------

    private void allocateSpritePatterns(VPU_v4 vpu) {
        int ofs = 0;

        ofsBall16 = ofs;
        ofs = writeBall16x16(vpu, ofsBall16);

        ofsArrow8 = ofs;
        ofs = writeArrow8x8(vpu, ofsArrow8);

        ofsSpark8 = ofs;
        ofs = writeSparkle8x8(vpu, ofsSpark8);

        ofsBanner32x8 = ofs;
        ofs = writeBanner32x8(vpu, ofsBanner32x8);

        // clear remainder
        for (int i = ofs; i < 0x4000; i++) vpu.writeVramPlane(VB_SPR, i, (byte) 0);
    }

    private static int writeBall16x16(VPU_v4 vpu, int ofs) {
        final int w = 16, h = 16;
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = ballShadeNibble(x0, y, w, h);
                int p1 = ballShadeNibble(x0 + 1, y, w, h);
                vpu.writeVramPlane(VB_SPR, (ofs++) & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
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
        if (rr > 1.0) return 0; // transparent

        double nz = Math.sqrt(1.0 - rr);
        double lx = -0.35, ly = -0.35, lz = 0.86;
        double diff = dx * lx + dy * ly + nz * lz;
        if (diff < 0) diff = 0;
        int shade = 1 + (int) Math.round(diff * 14.0);
        if (shade < 1) shade = 1;
        if (shade > 15) shade = 15;

        if (rr > 0.92) shade = 15;
        return shade;
    }

    private static int writeArrow8x8(VPU_v4 vpu, int ofs) {
        final int w = 8, h = 8;
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = arrowNibble(x0, y);
                int p1 = arrowNibble(x0 + 1, y);
                vpu.writeVramPlane(VB_SPR, (ofs++) & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int arrowNibble(int x, int y) {
        boolean shaft = (y >= 3 && y <= 4) && (x <= 5);
        boolean head = (x >= 4) && (Math.abs(y - 3) <= (x - 4));
        if (head && x == 7) return 15;
        if (head) return 11;
        if (shaft) return 7;
        return 0;
    }

    private static int writeSparkle8x8(VPU_v4 vpu, int ofs) {
        final int w = 8, h = 8;
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = sparkleNibble(x0, y);
                int p1 = sparkleNibble(x0 + 1, y);
                vpu.writeVramPlane(VB_SPR, (ofs++) & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int sparkleNibble(int x, int y) {
        boolean on = (x == 3 && y >= 1 && y <= 6)
                || (y == 3 && x >= 1 && x <= 6)
                || (x == 4 && y == 4);
        return on ? 15 : 0;
    }

    private static int writeBanner32x8(VPU_v4 vpu, int ofs) {
        final int w = 32, h = 8;
        for (int y = 0; y < h; y++) {
            for (int xPair = 0; xPair < (w >> 1); xPair++) {
                int x0 = xPair * 2;
                int p0 = bannerNibble(x0, y, w, h);
                int p1 = bannerNibble(x0 + 1, y, w, h);
                vpu.writeVramPlane(VB_SPR, (ofs++) & 0x3FFF, (byte) (((p0 & 0x0F) << 4) | (p1 & 0x0F)));
            }
        }
        return ofs;
    }

    private static int bannerNibble(int x, int y, int w, int h) {
        boolean border = (y == 0 || y == h - 1 || x == 0 || x == w - 1);
        if (border) return 15;
        double t = x / (double) (w - 1);
        int shade = 3 + (int) Math.round(10.0 * (0.5 + 0.5 * Math.sin(t * Math.PI)));
        if (shade < 1) shade = 1;
        if (shade > 14) shade = 14;
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
        vpu.writeVramPlane(VB_TBL, (addr) & 0x3FFF, (byte) (value & 0xFF));
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

    private static int readOam16(VPU_v4 vpu, int si, int fieldOfs) {
        int o = si * SPR_STRIDE + fieldOfs;
        int lo = vpu.readVramPlane(VB_TBL, o) & 0xFF;
        int hi = vpu.readVramPlane(VB_TBL, o + 1) & 0xFF;
        return (short) (lo | (hi << 8)); // treat as signed16 for X/Y
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

    public static void main(String[] args) { launch(args); }
}
