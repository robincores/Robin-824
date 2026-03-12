package io.github.robincores.r8;

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

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * VPU v4 demo: 4-group compositor + per-group scroll + priorities.
 *
 * This demo uses 4 groups (2 planes each) in LORES (320x200 doubled) to build a simple parallax scene.
 */
public final class Vpu4DemoGroupsParallaxMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private static final double TARGET_HZ = 70.0;

    // --- MMIO ---
    private static final int REG_TX_CTRL = 0x0006;

    private static final int GROUP_BASE = 0x0400;
    private static final int GROUP_STRIDE = 0x0010;

    // group fields
    private static final int G_PLANE_START = 0;
    private static final int G_PLANE_COUNT = 1;
    private static final int G_PAL_BASE    = 2;
    private static final int G_SCROLL_X_L  = 3;
    private static final int G_SCROLL_X_H  = 4;
    private static final int G_SCROLL_Y    = 5;
    private static final int G_PRIORITY    = 6;
    private static final int G_FLAGS       = 7;
    private static final int G_BPL_OFS_L   = 8;
    private static final int G_BPL_OFS_H   = 9;

    // flags
    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;
    private static final int GF_OPAQUE0 = 0x08;

    // VRAM geometry for LORES 320x200
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL = 40; // 320/8

    private volatile boolean running = true;
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setScene(new Scene(new StackPane(canvas), DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Demo — 4 Groups Parallax (LORES)" );
        stage.setResizable(false);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Disable text overlay for a pure graphics demo.
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);

        // Configure 4 groups (2 planes each) with increasing priorities.
        // Group0: background bands (opaque)
        writeGroup(vpu, 0,
                0, 2,
                0,    // pal base
                0, 0,
                10,
                GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0,
                0);

        // Group1: diagonal pattern (transparent on index 0)
        writeGroup(vpu, 1,
                2, 2,
                16,
                0, 0,
                60,
                GF_LORES | GF_WRAP_X | GF_WRAP_Y,
                0);

        // Group2: checkerboard (transparent on index 0)
        writeGroup(vpu, 2,
                4, 2,
                32,
                0, 0,
                120,
                GF_LORES | GF_WRAP_X | GF_WRAP_Y,
                0);

        // Group3: sparse stars (transparent on index 0)
        writeGroup(vpu, 3,
                6, 2,
                48,
                0, 0,
                220,
                GF_LORES | GF_WRAP_X | GF_WRAP_Y,
                0);

        // Fill VRAM patterns for each group's planes.
        fillGroup0Bands(vpu);
        fillGroup1Diagonal(vpu);
        fillGroup2Checker(vpu);
        fillGroup3Stars(vpu);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runLoop(vpu, cyclesPerFrame), "vpu4-groups-parallax-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private void runLoop(VPU_v4 vpu, int cyclesPerFrame) {
        final long frameNanosTarget = (long) (1_000_000_000L / TARGET_HZ);
        long next = System.nanoTime();

        int s0 = 0, s1 = 0, s2 = 0, s3x = 0, s3y = 0;

        while (running) {
            // Update per-group scroll once per frame.
            // (The VPU latches group descriptors at frame start, so this is stable.)
            s0 += 1;
            s1 += 2;
            s2 += 3;
            s3x += 1;
            s3y += 1;

            setGroupScroll(vpu, 0, s0, 0);
            setGroupScroll(vpu, 1, s1, 0);
            setGroupScroll(vpu, 2, s2, 0);
            setGroupScroll(vpu, 3, s3x, s3y);

            vpu.tick(cyclesPerFrame);

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

    private static void setGroupScroll(VPU_v4 vpu, int g, int scrollX, int scrollY) {
        int base = GROUP_BASE + g * GROUP_STRIDE;
        writeMmio16(vpu, base + G_SCROLL_X_L, scrollX);
        vpu.writeMmio(base + G_SCROLL_Y, (byte) (scrollY & 0xFF));
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
        vpu.writeMmio(base + G_PLANE_START, (byte) (planeStart & 7));
        vpu.writeMmio(base + G_PLANE_COUNT, (byte) (planeCount & 0xFF));
        vpu.writeMmio(base + G_PAL_BASE, (byte) (palBase & 0xFF));
        writeMmio16(vpu, base + G_SCROLL_X_L, scrollX);
        vpu.writeMmio(base + G_SCROLL_Y, (byte) (scrollY & 0xFF));
        vpu.writeMmio(base + G_PRIORITY, (byte) (prio & 0xFF));
        vpu.writeMmio(base + G_FLAGS, (byte) (flags & 0xFF));
        writeMmio16(vpu, base + G_BPL_OFS_L, bplOfs & 0x3FFF);
    }

    private static void writeMmio16(VPU_v4 vpu, int addr, int value) {
        vpu.writeMmio(addr & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeMmio((addr + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
    }

    // ---------------------------------------------------------------------
    // VRAM patterns (2-plane = 4 colors per group)
    // ---------------------------------------------------------------------

    private static void fillGroup0Bands(VPU_v4 vpu) {
        // Planes 0..1: horizontal color bands
        for (int y = 0; y < SRC_H; y++) {
            int idx = (y >> 5) & 3; // 0..3
            int b0 = ((idx & 1) != 0) ? 0xFF : 0x00;
            int b1 = ((idx & 2) != 0) ? 0xFF : 0x00;
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                vpu.writeVramPlane(0, row + bx, (byte) b0);
                vpu.writeVramPlane(1, row + bx, (byte) b1);
            }
        }
    }

    private static void fillGroup1Diagonal(VPU_v4 vpu) {
        // Planes 2..3: diagonal stripes based on (x^y)
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int baseX = bx << 3;
                int p2 = 0;
                int p3 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = baseX + i;
                    int idx = ((x >> 4) ^ (y >> 4)) & 3;
                    int bit = 7 - i;
                    if ((idx & 1) != 0) p2 |= (1 << bit);
                    if ((idx & 2) != 0) p3 |= (1 << bit);
                }
                vpu.writeVramPlane(2, row + bx, (byte) p2);
                vpu.writeVramPlane(3, row + bx, (byte) p3);
            }
        }
    }

    private static void fillGroup2Checker(VPU_v4 vpu) {
        // Planes 4..5: coarse checkerboard
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int baseX = bx << 3;
                int p4 = 0;
                int p5 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = baseX + i;
                    int idx = (((x >> 5) + (y >> 5)) & 3);
                    // make lots of transparent (idx=0)
                    if (((x >> 3) & 1) == 0) idx = 0;
                    int bit = 7 - i;
                    if ((idx & 1) != 0) p4 |= (1 << bit);
                    if ((idx & 2) != 0) p5 |= (1 << bit);
                }
                vpu.writeVramPlane(4, row + bx, (byte) p4);
                vpu.writeVramPlane(5, row + bx, (byte) p5);
            }
        }
    }

    private static void fillGroup3Stars(VPU_v4 vpu) {
        // Planes 6..7: sparse stars (mostly transparent)
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                int baseX = bx << 3;
                int p6 = 0;
                int p7 = 0;
                for (int i = 0; i < 8; i++) {
                    int x = baseX + i;
                    // deterministic pseudo-random: a few points become idx=3
                    int h = (x * 1103515245 + y * 12345 + 0xBEEF) >>> 0;
                    boolean star = ((h >>> 28) & 0xF) == 0;
                    int idx = star ? 3 : 0;
                    int bit = 7 - i;
                    if ((idx & 1) != 0) p6 |= (1 << bit);
                    if ((idx & 2) != 0) p7 |= (1 << bit);
                }
                vpu.writeVramPlane(6, row + bx, (byte) p6);
                vpu.writeVramPlane(7, row + bx, (byte) p7);
            }
        }
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
