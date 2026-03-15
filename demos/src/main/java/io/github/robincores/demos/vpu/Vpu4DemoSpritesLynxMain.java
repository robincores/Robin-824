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

import java.util.concurrent.locks.LockSupport;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v4 demo: Lynx-leaning sprite engine (8.8 scaling + tilt + chaining + per-sprite priority).
 */
public final class Vpu4DemoSpritesLynxMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private static final double TARGET_HZ = 70.0;

    // --- MMIO ---
    private static final int REG_TX_CTRL  = 0x0006;
    private static final int REG_SPR_CTRL = 0x0014;
    private static final int SPR_EN = 0x01;

    // Groups
    private static final int GROUP_BASE   = 0x0400;
    private static final int GROUP_STRIDE = 0x0010;

    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;
    private static final int GF_OPAQUE0 = 0x08;

    // VRAM geometry for LORES 320x200
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL = 40;

    // Banks
    private static final int VB_SPR = 8;
    private static final int VB_TBL = 9;

    // OAM layout (v4): 128 sprites, 32 bytes each, base=0x0000 in VB_TBL
    private static final int SPR_COUNT  = 128;
    private static final int SPR_STRIDE = 32;

    // OAM field offsets
    private static final int O_X_L = 0;
    private static final int O_X_H = 1;
    private static final int O_Y_L = 2;
    private static final int O_Y_H = 3;
    private static final int O_W   = 4;
    private static final int O_H   = 5;
    private static final int O_DATA_L = 6;
    private static final int O_DATA_H = 7;
    private static final int O_ATTR   = 8;
    private static final int O_PAL    = 9;
    private static final int O_COLID  = 10;
    private static final int O_PRIO   = 11;
    private static final int O_XSTEP_L = 12;
    private static final int O_XSTEP_H = 13;
    private static final int O_YSTEP_L = 14;
    private static final int O_YSTEP_H = 15;
    private static final int O_TILT_DX = 16;
    private static final int O_LINK    = 17;

    // ATTR bits (v4)
    private static final int SA_EN      = 0x01;
    private static final int SA_HFLIP   = 0x02;
    private static final int SA_VFLIP   = 0x04;
    private static final int SA_CHAIN   = 0x08;
    private static final int SA_COLLIDE = 0x10;
    private static final int SA_TILT    = 0x20;

    private volatile boolean running = true;
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setScene(new Scene(new StackPane(canvas), DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Demo — Lynx-ish Sprites (8.8 scale + tilt + chain + prio)");
        stage.setResizable(false);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Disable text for clarity.
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);

        // Two groups (background + foreground) to show sprite interleaving.
        writeGroup(vpu, 0, 0, 4, 0, 0, 0, 50,  GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0, 0);
        writeGroup(vpu, 1, 4, 2, 32, 0, 0, 150, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        // Disable group2/group3
        for (int g = 2; g < 4; g++) {
            vpu.writeMmio(GROUP_BASE + g * GROUP_STRIDE + 1, (byte) 0);
        }

        fillBackground(vpu);
        fillForeground(vpu);

        // Sprite patterns in VBANK=8
        int sprOfs0 = 0x0000;
        int sprOfs1 = 0x0100;
        writeSprite16x16(vpu, sprOfs0, 12, 14, 15); // red/yellow/white
        writeSprite16x16(vpu, sprOfs1, 9, 10, 15);  // blue/green/white

        // Clear OAM
        for (int i = 0; i < SPR_COUNT * SPR_STRIDE; i++) {
            vpu.writeVramPlane(VB_TBL, i, (byte) 0);
        }

        // Sprite 0: "head" of a chain (renders 1 + 2 via links)
        writeSpriteOam(vpu, 0,
                80, 60,
                16, 16,
                sprOfs0,
                SA_EN | SA_CHAIN | SA_TILT,
                0,      // PAL group 0 (VGA 0..15)
                1,      // COL_ID
                100,    // PRIO between group0(50) and group1(150)
                0x0100, 0x0100,
                1,      // TILT_DX
                1       // LINK -> sprite 1
        );

        // Sprite 1: chained
        writeSpriteOam(vpu, 1,
                120, 70,
                16, 16,
                sprOfs0,
                SA_EN | SA_CHAIN,
                0,
                2,
                110,
                0x0100, 0x0100,
                0,
                2
        );

        // Sprite 2: chained (terminator)
        writeSpriteOam(vpu, 2,
                160, 80,
                16, 16,
                sprOfs1,
                SA_EN,
                0,
                3,
                180, // above group1
                0x0100, 0x0100,
                0,
                0
        );

        // Sprite 3: behind everything
        writeSpriteOam(vpu, 3,
                220, 120,
                16, 16,
                sprOfs1,
                SA_EN,
                0,
                4,
                10,
                0x0100, 0x0100,
                0,
                0
        );

        vpu.writeMmio(REG_SPR_CTRL, (byte) SPR_EN);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runLoop(vpu, cyclesPerFrame), "vpu4-sprites-lynx-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private void runLoop(VPU_v4 vpu, int cyclesPerFrame) {
        final long frameNanosTarget = (long) (1_000_000_000L / TARGET_HZ);
        long next = System.nanoTime();

        int t = 0;
        while (running) {
            // Animate sprite0 scale and tilt.
            // scale factor f in 8.8 (0x0100 = 1.0). We swing between 0.75 and 2.0.
            int f = 0x00C0 + (int) (0x0140 * (0.5 + 0.5 * Math.sin(t * 0.05)));
            int xstep = invScaleToStep(f);
            int ystep = invScaleToStep(0x0100 + ((f - 0x0100) / 2));

            int x0 = 80 + (int) (60 * Math.sin(t * 0.03));
            int y0 = 60 + (int) (30 * Math.cos(t * 0.04));

            write16Tbl(vpu, 0 * SPR_STRIDE + O_X_L, x0);
            write16Tbl(vpu, 0 * SPR_STRIDE + O_Y_L, y0);
            write16Tbl(vpu, 0 * SPR_STRIDE + O_XSTEP_L, xstep);
            write16Tbl(vpu, 0 * SPR_STRIDE + O_YSTEP_L, ystep);
            vpu.writeVramPlane(VB_TBL, 0 * SPR_STRIDE + O_TILT_DX, (byte) (1 + ((t >> 4) & 3)));

            // Animate sprite3 behind everything.
            int x3 = 220 + (int) (80 * Math.sin(t * 0.02));
            int y3 = 120 + (int) (40 * Math.sin(t * 0.015));
            write16Tbl(vpu, 3 * SPR_STRIDE + O_X_L, x3);
            write16Tbl(vpu, 3 * SPR_STRIDE + O_Y_L, y3);

            // Parallax scroll
            setGroupScroll(vpu, 0, t, 0);
            setGroupScroll(vpu, 1, t * 3, 0);

            vpu.tick(cyclesPerFrame);

            t++;
            next += frameNanosTarget;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                next = System.nanoTime();
            }
        }

        Platform.runLater(() -> {
            if (stage != null) stage.close();
        });
    }

    // step = (1.0 / scale) in 8.8; scale f is 8.8
    private static int invScaleToStep(int scale88) {
        if (scale88 <= 0) return 0x0100;
        int step = (0x0100 * 0x0100) / scale88;
        if (step <= 0) step = 1;
        if (step > 0xFFFF) step = 0xFFFF;
        return step;
    }

    // ---------------------------------------------------------------------
    // Groups + background VRAM
    // ---------------------------------------------------------------------

    private static void setGroupScroll(VPU_v4 vpu, int g, int scrollX, int scrollY) {
        int base = GROUP_BASE + g * GROUP_STRIDE;
        writeMmio16(vpu, base + 3, scrollX);
        vpu.writeMmio(base + 5, (byte) (scrollY & 0xFF));
    }

    private static void writeGroup(VPU_v4 vpu,
                                  int g,
                                  int planeStart,
                                  int planeCount,
                                  int palBase,
                                  int scrollX,
                                  int scrollY,
                                  int prio,
                                  int flags,
                                  int bplOfs) {
        int base = GROUP_BASE + g * GROUP_STRIDE;
        vpu.writeMmio(base + 0, (byte) (planeStart & 7));
        vpu.writeMmio(base + 1, (byte) (planeCount & 0xFF));
        vpu.writeMmio(base + 2, (byte) (palBase & 0xFF));
        writeMmio16(vpu, base + 3, scrollX);
        vpu.writeMmio(base + 5, (byte) (scrollY & 0xFF));
        vpu.writeMmio(base + 6, (byte) (prio & 0xFF));
        vpu.writeMmio(base + 7, (byte) (flags & 0xFF));
        writeMmio16(vpu, base + 8, bplOfs & 0x3FFF);
    }

    private static void fillBackground(VPU_v4 vpu) {
        // Planes 0..3: a simple 4bpp gradient (index = x/20)
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int baseX = bx << 3;
                int[] bytes = new int[4];
                for (int i = 0; i < 8; i++) {
                    int x = baseX + i;
                    int idx = (x / 20) & 0x0F;
                    int bit = 7 - i;
                    for (int p = 0; p < 4; p++) {
                        if (((idx >>> p) & 1) != 0) bytes[p] |= (1 << bit);
                    }
                }
                for (int p = 0; p < 4; p++) {
                    vpu.writeVramPlane(p, row + bx, (byte) bytes[p]);
                }
            }
        }
    }

    private static void fillForeground(VPU_v4 vpu) {
        // Planes 4..5: stripes with transparency (index 0 is transparent)
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int baseX = bx << 3;
                int b4 = 0;
                int b5 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = baseX + i;
                    int idx = ((x >> 4) & 1) == 0 ? 0 : (1 + ((y >> 5) & 3));
                    int bit = 7 - i;
                    if ((idx & 1) != 0) b4 |= (1 << bit);
                    if ((idx & 2) != 0) b5 |= (1 << bit);
                }
                vpu.writeVramPlane(4, row + bx, (byte) b4);
                vpu.writeVramPlane(5, row + bx, (byte) b5);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sprite pattern (16x16, 4bpp packed)
    // ---------------------------------------------------------------------

    private static void writeSprite16x16(VPU_v4 vpu, int dataOfs, int c1, int c2, int c3) {
        final int w = 16;
        final int h = 16;
        final int stride = (w + 1) >> 1; // 8

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x += 2) {
                int p0 = spritePixel(x, y, c1, c2, c3);
                int p1 = spritePixel(x + 1, y, c1, c2, c3);
                int b = ((p0 & 0x0F) << 4) | (p1 & 0x0F);
                int ofs = dataOfs + y * stride + (x >> 1);
                vpu.writeVramPlane(VB_SPR, ofs, (byte) b);
            }
        }
    }

    private static int spritePixel(int x, int y, int c1, int c2, int c3) {
        // Simple circle-ish badge with two-tone fill.
        int cx = x - 7;
        int cy = y - 7;
        int r2 = cx * cx + cy * cy;
        if (r2 > 7 * 7) return 0;           // transparent
        if (r2 > 5 * 5) return c1 & 0x0F;   // outer ring
        if ((x + y) % 2 == 0) return c2 & 0x0F;
        return c3 & 0x0F;
    }

    // ---------------------------------------------------------------------
    // OAM helpers
    // ---------------------------------------------------------------------

    private static void writeSpriteOam(VPU_v4 vpu,
                                       int si,
                                       int x,
                                       int y,
                                       int w,
                                       int h,
                                       int dataOfs,
                                       int attr,
                                       int pal,
                                       int colId,
                                       int prio,
                                       int xstep,
                                       int ystep,
                                       int tiltDx,
                                       int link) {
        int o = si * SPR_STRIDE;
        write16Tbl(vpu, o + O_X_L, x);
        write16Tbl(vpu, o + O_Y_L, y);
        vpu.writeVramPlane(VB_TBL, o + O_W, (byte) (w & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + O_H, (byte) (h & 0xFF));
        write16Tbl(vpu, o + O_DATA_L, dataOfs);
        vpu.writeVramPlane(VB_TBL, o + O_ATTR, (byte) (attr & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + O_PAL, (byte) (pal & 0x0F));
        vpu.writeVramPlane(VB_TBL, o + O_COLID, (byte) (colId & 0x0F));
        vpu.writeVramPlane(VB_TBL, o + O_PRIO, (byte) (prio & 0xFF));
        write16Tbl(vpu, o + O_XSTEP_L, xstep);
        write16Tbl(vpu, o + O_YSTEP_L, ystep);
        vpu.writeVramPlane(VB_TBL, o + O_TILT_DX, (byte) (tiltDx & 0xFF));
        vpu.writeVramPlane(VB_TBL, o + O_LINK, (byte) (link & 0x7F));
    }

    private static void write16Tbl(VPU_v4 vpu, int ofs, int value) {
        vpu.writeVramPlane(VB_TBL, (ofs) & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeVramPlane(VB_TBL, (ofs + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
    }

    private static void writeMmio16(VPU_v4 vpu, int addr, int value) {
        vpu.writeMmio(addr & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeMmio((addr + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
    }

    @Override
    public void stop() {
        running = false;
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
