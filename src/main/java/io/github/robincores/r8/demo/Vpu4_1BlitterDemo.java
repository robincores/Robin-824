package io.github.robincores.r8.demo;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4_1;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Vpu4_1BlitterDemo extends Application {

    private static final DisplayConfig CFG =
            new DisplayConfig(640, 400, 640, 400, 400, 449, 400);
    private static final int CPF = CFG.cyclesPerFrame();

    // LORES 320x200 (VPU doubles to 640x400)
    private static final int LW = 320, LH = 200;
    private static final int BPR = LW / 8; // 40 bytes/row

    // Core MMIO
    private static final int REG_CTRL     = 0x0000;
    private static final int REG_STATUS   = 0x0001;
    private static final int REG_TX_CTRL  = 0x0006;
    private static final int REG_SPR_CTRL = 0x0014;

    private static final int CTRL_ENABLE  = 0x01;

    // STATUS bits
    private static final int STATUS_BLT   = 0x10;

    // Palette + groups
    private static final int PAL_BASE   = 0x0100;
    private static final int GROUP_BASE = 0x0400;

    private static final int GF_LORES  = 0x01;
    private static final int GF_WRAP_X = 0x02;
    private static final int GF_WRAP_Y = 0x04;

    // Blitter regs (v4.1)
    private static final int BLT_CTRL        = 0x0560;
    private static final int BLT_DST_L       = 0x0563;
    private static final int BLT_DST_PITCH_L = 0x056A;
    private static final int BLT_FILL        = 0x056C;
    private static final int BLT_PLANE_MASK  = 0x056D;
    private static final int BLT_ROP         = 0x056E;
    private static final int BLT_FIRST_MASK  = 0x0570;
    private static final int BLT_LAST_MASK   = 0x0571;
    private static final int BLT_X_L         = 0x0572;
    private static final int BLT_Y_L         = 0x0574;
    private static final int BLT_WPX_L       = 0x0576;
    private static final int BLT_HPX         = 0x0578;

    private static final int BLT_START    = 0x01;
    private static final int ROP_RECTFILL = 4;

    // Planes 0..3 = 4bpp
    private static final int PM_0_3 = 0x0F;

    private VPU_v4_1 vpu;
    private volatile boolean running = true;
    private volatile boolean paused = false;

    private int frame;
    private int frameCycles;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(CFG.canvasWidth(), CFG.canvasHeight());
        vpu = new VPU_v4_1(CFG, bit -> {}, 0, canvas);

        initVpu();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, CFG.canvasWidth(), CFG.canvasHeight());
        scene.getRoot().setStyle("-fx-background-color: black;");
        stage.setTitle("VPU v4.1 — Blitter RECTFILL Demo (planes 0..3)");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) paused = !paused;
            if (e.getCode() == KeyCode.ESCAPE) { running = false; Platform.exit(); }
        });

        Thread emu = new Thread(() -> {
            final long FRAME_NS = 14_285_714L; // ~70Hz
            long next = System.nanoTime();
            while (running && !Thread.interrupted()) {
                long now = System.nanoTime();
                if (now >= next) {
                    runOneFrame();
                    next += FRAME_NS;
                    if (next < now) next = now;
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "vpu-blt-rectfill-demo");
        emu.setDaemon(true);
        emu.start();

        new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        }.start();

        stage.setOnCloseRequest(e -> {
            running = false;
            emu.interrupt();
            Platform.exit();
        });
    }

    private void initVpu() {
        vpu.reset(true);

        // disable text/sprites
        mmio(REG_TX_CTRL, 0x00);
        mmio(REG_SPR_CTRL, 0x00);

        // Palette indices 0..15 (keep it bold)
        setPal(0,  0x000000); // 0 black
        setPal(1,  0x0B1140); // 1 dark blue bg
        setPal(2,  0x2040C0); // 2 blue
        setPal(3,  0x20C020); // 3 green
        setPal(4,  0xC02020); // 4 dark red
        setPal(5,  0xC020C0); // 5 purple
        setPal(6,  0xC0C020); // 6 yellow-ish
        setPal(7,  0x808080); // 7 gray
        setPal(8,  0x00B0FF); // 8 cyan
        setPal(9,  0x00FFD0); // 9 aqua
        setPal(10, 0x80FF00); // 10 lime
        setPal(11, 0xFFFF00); // 11 yellow
        setPal(12, 0xFF0000); // 12 RED box
        setPal(13, 0xFF8840); // 13 orange
        setPal(14, 0xFFFFFF); // 14 WHITE border
        setPal(15, 0xE0E0E0); // 15 light gray

        // Group0 only: planes 0..3, LORES, wrap
        setGroup(0, 0, 4, 0, 0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        // disable others
        setGroup(1, 0, 0, 0, 0, 0, 1, 0, 0);
        setGroup(2, 0, 0, 0, 0, 0, 2, 0, 0);
        setGroup(3, 0, 0, 0, 0, 0, 3, 0, 0);

        // enable core graphics
        mmio(REG_CTRL, CTRL_ENABLE);

        // push one frame so you see something immediately
        runOneFrame();
    }

    private void runOneFrame() {
        frameCycles = 0;

        if (paused) {
            tickBudget(CPF);
            frame++;
            return;
        }

        // Always clear stale completion
        mmio(REG_STATUS, STATUS_BLT);

        // Background fill (index 1) full screen
        bltRectFill(0, 0, LW, LH, /*colorIndex*/1);

        // Border (index 14)
        bltRectFill(0, 0, LW, 2, 14);
        bltRectFill(0, LH - 2, LW, 2, 14);
        bltRectFill(0, 0, 2, LH, 14);
        bltRectFill(LW - 2, 0, 2, LH, 14);

        // Moving red box (index 12)
        int bx = (frame * 2) % (LW - 60);
        int by = 30 + (int)(Math.sin(frame * 0.08) * 35);
        bltRectFill(bx, by, 60, 45, 12);

        // A second small “stamp” so you know multiple ops work
        int sx = (LW - 40) - ((frame * 3) % (LW - 40));
        bltRectFill(sx, 140, 40, 10, 9);

        // Finish frame time so VBLANK publish happens predictably
        int remaining = CPF - frameCycles;
        if (remaining > 0) tickBudget(remaining);

        frame++;
    }

    // ---- BLITTER RECTFILL (planes 0..3) ----
    private void bltRectFill(int x, int y, int w, int h, int colorIndex4) {
        if (w <= 0 || h <= 0) return;

        // clear stale completion
        mmio(REG_STATUS, STATUS_BLT);

        // dst base + pitch
        mmio16(BLT_DST_L, 0x0000);
        mmio16(BLT_DST_PITCH_L, BPR);

        // rect in pixels
        mmio16(BLT_X_L, x);
        mmio16(BLT_Y_L, y);
        mmio16(BLT_WPX_L, w);
        mmio(BLT_HPX, h & 0xFF);

        // color bits map directly to planes 0..3 (4bpp)
        mmio(BLT_FILL, (colorIndex4 & 0x0F));
        mmio(BLT_PLANE_MASK, PM_0_3);
        mmio(BLT_ROP, ROP_RECTFILL);
        mmio(BLT_FIRST_MASK, 0xFF);
        mmio(BLT_LAST_MASK,  0xFF);

        mmio(BLT_CTRL, BLT_START);
        bltWaitDone();
    }

    private void bltWaitDone() {
        // tick until STATUS_BLT appears
        for (int guard = 0; guard < 200_000; guard++) {
            int st = vpu.readMmio(REG_STATUS) & 0xFF;
            if ((st & STATUS_BLT) != 0) {
                mmio(REG_STATUS, STATUS_BLT); // W1C
                return;
            }
            tickBudget(64);
            if (frameCycles >= CPF) return; // safety
        }
    }

    // ---- MMIO / palette / groups / tick ----
    private void mmio(int reg, int val) {
        vpu.writeMmio(reg, (byte)(val & 0xFF));
    }

    private void mmio16(int regLo, int val) {
        mmio(regLo, val & 0xFF);
        mmio(regLo + 1, (val >>> 8) & 0xFF);
    }

    private void tickBudget(int cycles) {
        if (cycles <= 0) return;
        vpu.tick(cycles);
        frameCycles += cycles;
    }

    private void setGroup(int gi, int planeStart, int planeCount, int palBase,
                          int scrollX, int scrollY, int priority, int flags, int bplOfs) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 0, planeStart & 0x07);
        mmio(base + 1, planeCount & 0xFF);
        mmio(base + 2, palBase & 0xFF);
        mmio(base + 3, scrollX & 0xFF);
        mmio(base + 4, (scrollX >>> 8) & 0xFF);
        mmio(base + 5, scrollY & 0xFF);
        mmio(base + 6, priority & 0xFF);
        mmio(base + 7, flags & 0xFF);
        mmio(base + 8, bplOfs & 0xFF);
        mmio(base + 9, (bplOfs >>> 8) & 0xFF);
    }

    private void setPal(int idx, int rgb24) {
        int v = toRgb565(rgb24);
        int ofs = PAL_BASE + idx * 2;
        mmio(ofs, v & 0xFF);
        mmio(ofs + 1, (v >>> 8) & 0xFF);
    }

    private static int toRgb565(int rgb24) {
        int r = (rgb24 >>> 16) & 0xFF;
        int g = (rgb24 >>> 8) & 0xFF;
        int b = rgb24 & 0xFF;
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);
    }

    public static void main(String[] args) { launch(args); }
}