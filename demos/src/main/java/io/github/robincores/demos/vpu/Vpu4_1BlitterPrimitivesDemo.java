package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4_1;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * VPU v4.1 — Blitter Primitives (Lines + Circles) — FIXED
 *
 * Differences vs the "won't open window" version:
 *  - NO io.github.robincores.r8.device.VPU import/field (avoid type/module collisions).
 *  - NO endFrame() during setup before stage.show() (prevents pre-show deadlock).
 *  - vpu is VPU_v4_1 (no casts).
 */
public class Vpu4_1BlitterPrimitivesDemo extends Application {

    private static final DisplayConfig CFG = new DisplayConfig(640, 400, 640, 400, 400, 449, 400);

    // LORES logical space (renderer doubles to 640x400)
    private static final int LW = 320, LH = 200;
    private static final int BPR = LW / 8; // 40

    // MMIO (core)
    private static final int REG_CTRL     = 0x0000;
    private static final int REG_STATUS   = 0x0001;
    private static final int REG_TX_CTRL  = 0x0006;
    private static final int REG_SPR_CTRL = 0x0014;

    private static final int CTRL_ENABLE  = 0x01;

    // STATUS bits (your VPU_v4_1)
    private static final int STATUS_FRAME = 0x02; // W1C at VBLANK start
    private static final int STATUS_BLT   = 0x10; // W1C on blit done

    // Palette + group table
    private static final int PAL_BASE   = 0x0100;
    private static final int GROUP_BASE = 0x0400;

    // Group flags (your VPU_v4_1)
    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;
    private static final int GF_OPAQUE0 = 0x08;

    // Blitter (RECTFILL only)
    private static final int BLT_CTRL        = 0x0560;
    private static final int BLT_DST_L       = 0x0563;
    private static final int BLT_DST_PITCH_L = 0x056A;
    private static final int BLT_FILL        = 0x056C;
    private static final int BLT_PLANE_MASK  = 0x056D;
    private static final int BLT_ROP         = 0x056E;
    private static final int BLT_FIRST_MASK  = 0x0570;
    private static final int BLT_LAST_MASK   = 0x0571;

    private static final int BLT_X_L   = 0x0572;
    private static final int BLT_Y_L   = 0x0574;
    private static final int BLT_WPX_L = 0x0576;
    private static final int BLT_HPX   = 0x0578;

    private static final int BLT_START    = 0x01;
    private static final int ROP_RECTFILL = 4;

    private static final int PLANES_ALL = 0xFF;

    // State
    private VPU_v4_1 vpu;
    private volatile int frame;

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas();
        vpu = new VPU_v4_1(CFG, bit -> {}, 0, canvas);

        // Setup that cannot block
        setupDemoState();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, CFG.canvasWidth(), CFG.canvasHeight());
        scene.getRoot().setStyle("-fx-background-color: black;");
        stage.setTitle("VPU v4.1 — Blitter Primitives (Lines + Circles)");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        Thread emu = new Thread(() -> {
            final long FRAME_NS = 14_285_714L; // ~70 Hz
            long next = System.nanoTime();
            while (!Thread.interrupted()) {
                long now = System.nanoTime();
                if (now >= next) {
                    runOneFrame();
                    frame++;
                    next += FRAME_NS;
                    if (next < now) next = now;
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "vpu-primitives");
        emu.setDaemon(true);
        emu.start();

        new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        }.start();

        stage.setOnCloseRequest(e -> { emu.interrupt(); Platform.exit(); });
    }

    public static void main(String[] args) { launch(args); }

    // ------------------------------------------------------------
    // Setup (non-blocking)
    // ------------------------------------------------------------
    private void setupDemoState() {
        // Do NOT call endFrame() here (can deadlock before Stage is visible)

        mmio(REG_TX_CTRL, 0x00);
        mmio(REG_SPR_CTRL, 0x00);

        // One group: planes 0..7, palette base 0, LORES, opaque0
        setGroup(0, 0, 8, 0, 0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0, 0);

        // Disable other groups
        setGroup(1, 0, 0, 0, 0, 0, 1, 0, 0);
        setGroup(2, 0, 0, 0, 0, 0, 2, 0, 0);
        setGroup(3, 0, 0, 0, 0, 0, 3, 0, 0);

        setupPalette();

        mmio(REG_CTRL, CTRL_ENABLE);
    }

    private void setupPalette() {
        setPal(0, 0x000000);
        setPal(1, 0x00E8FF);
        setPal(2, 0xFF00C0);
        setPal(3, 0xFFE000);
        setPal(4, 0x80FF40);
        setPal(5, 0xFFFFFF);
        setPal(6, 0xFF6020);
        setPal(7, 0x4060FF);
        setPal(8, 0x30FFB0);
        setPal(9, 0xB000FF);
        for (int i = 10; i <= 31; i++) {
            int t = i - 10;
            int r = clamp(20 + t * 10, 0, 255);
            int g = clamp(10 + t * 6,  0, 255);
            int b = clamp(40 + t * 8,  0, 255);
            setPal(i, (r << 16) | (g << 8) | b);
        }
    }

    // ------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------
    private void runOneFrame() {
        int f = frame;

        rectFill(0, 0, LW, LH, 0);

        // bands
        int bands = 10;
        for (int i = 0; i < bands; i++) {
            int y = i * (LH / bands);
            int h = (LH / bands);
            int col = 10 + ((i * 3 + (f >> 3)) % 22);
            rectFill(0, y, LW, h, col);
        }

        // circles
        for (int i = 0; i < 4; i++) {
            int cx = 160 + (int) (120 * Math.sin((f * 0.03) + i));
            int cy = 100 + (int) (70  * Math.cos((f * 0.025) + i * 1.7));
            int r  = 18 + (int) (14  * (1 + Math.sin((f * 0.04) + i * 0.9)));
            int col = 1 + (i % 9);
            drawCircleFilled(cx, cy, r, col);
            drawCircleOutline(cx, cy, r, 5);
        }

        // lines
        for (int i = 0; i < 14; i++) {
            double a = (f * 0.02) + i * 0.45;
            int x0 = 160 + (int) (140 * Math.cos(a));
            int y0 = 100 + (int) (90  * Math.sin(a * 1.3));
            int x1 = 160 + (int) (140 * Math.cos(a + 1.8));
            int y1 = 100 + (int) (90  * Math.sin(a * 1.3 + 2.0));
            int col = (i % 9) + 1;
            drawLine2x2(x0, y0, x1, y1, col);
        }

        // publish at least one VBLANK per loop
        endFrame();
    }

    // ------------------------------------------------------------
    // Primitives (RECTFILL-only)
    // ------------------------------------------------------------
    private void putPixel2(int x, int y, int color) {
        if (x < 0 || x >= LW || y < 0 || y >= LH) return;
        rectFill(x, y, 2, 2, color);
    }

    private void drawLine2x2(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            putPixel2(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err << 1;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void drawCircleFilled(int cx, int cy, int r, int color) {
        if (r <= 0) return;
        int r2 = r * r;
        for (int dy = -r; dy <= r; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= LH) continue;
            int dx = (int) Math.floor(Math.sqrt(Math.max(0, r2 - dy * dy)));
            int x0 = cx - dx;
            int w = dx * 2 + 1;
            if (x0 < 0) { w -= -x0; x0 = 0; }
            if (x0 + w > LW) w = LW - x0;
            if (w > 0) rectFill(x0, y, w, 1, color);
        }
    }

    private void drawCircleOutline(int cx, int cy, int r, int color) {
        int x = r, y = 0, err = 1 - x;
        while (x >= y) {
            putPixel2(cx + x, cy + y, color);
            putPixel2(cx + y, cy + x, color);
            putPixel2(cx - y, cy + x, color);
            putPixel2(cx - x, cy + y, color);
            putPixel2(cx - x, cy - y, color);
            putPixel2(cx - y, cy - x, color);
            putPixel2(cx + y, cy - x, color);
            putPixel2(cx + x, cy - y, color);
            y++;
            if (err < 0) err += 2 * y + 1;
            else { x--; err += 2 * (y - x) + 1; }
        }
    }

    // ------------------------------------------------------------
    // Blitter RECTFILL
    // ------------------------------------------------------------
    private void rectFill(int x, int y, int w, int h, int colorIndex) {
        if (w <= 0 || h <= 0) return;
        if (x < 0) { w -= -x; x = 0; }
        if (y < 0) { h -= -y; y = 0; }
        if (x + w > LW) w = LW - x;
        if (y + h > LH) h = LH - y;
        if (w <= 0 || h <= 0) return;

        mmio16(BLT_DST_L, 0x0000);
        mmio16(BLT_DST_PITCH_L, BPR);

        mmio16(BLT_X_L, x);
        mmio16(BLT_Y_L, y);
        mmio16(BLT_WPX_L, w);
        mmio(BLT_HPX, h & 0xFF);

        mmio(BLT_FILL, colorIndex & 0xFF);
        mmio(BLT_PLANE_MASK, PLANES_ALL);
        mmio(BLT_ROP, ROP_RECTFILL);
        mmio(BLT_FIRST_MASK, 0xFF);
        mmio(BLT_LAST_MASK,  0xFF);

        mmio(BLT_CTRL, BLT_START);
        waitBlitDone();
    }

    private void waitBlitDone() {
        while (true) {
            int st = vpu.readMmio(REG_STATUS) & 0xFF;
            if ((st & STATUS_BLT) != 0) {
                mmio(REG_STATUS, STATUS_BLT);
                return;
            }
            vpu.tick(128);
        }
    }

    private void endFrame() {
        while (true) {
            int st = vpu.readMmio(REG_STATUS) & 0xFF;
            if ((st & STATUS_FRAME) != 0) {
                mmio(REG_STATUS, STATUS_FRAME);
                return;
            }
            vpu.tick(256);
        }
    }

    // ------------------------------------------------------------
    // Group / palette / mmio helpers
    // ------------------------------------------------------------
    private void setGroup(int gi, int planeStart, int planeCount, int palBase,
                          int scrollX, int scrollY, int priority, int flags, int bplOfs) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 0, planeStart & 0xFF);
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

    private void mmio(int reg, int val) {
        vpu.writeMmio(reg, (byte) (val & 0xFF));
    }

    private void mmio16(int regLo, int val) {
        mmio(regLo, val & 0xFF);
        mmio(regLo + 1, (val >>> 8) & 0xFF);
    }

    private static int toRgb565(int rgb24) {
        int r = (rgb24 >>> 16) & 0xFF;
        int g = (rgb24 >>> 8) & 0xFF;
        int b = rgb24 & 0xFF;
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
}