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
 * VPU v4 demo: VGA-style write modes (SET/RESET + BITMASK + ROP).
 *
 * Goal: show how an 8-bit CPU can draw into planar VRAM without writing each plane separately.
 */
public final class Vpu4DemoWriteModesMain extends Application {

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

    // Write modes (v4)
    private static final int REG_WM_CTRL       = 0x0016;
    private static final int REG_WM_PLANE_MASK = 0x0017;
    private static final int REG_WM_SETRESET   = 0x0018;
    private static final int REG_WM_BITMASK    = 0x0019;

    private static final int WM_EN = 0x01;
    private static final int WM_USE_SETRESET = 0x02;

    private static final int WM_ROP_SHIFT = 2;
    private static final int WM_ROP_REPLACE = 0;
    private static final int WM_ROP_OR      = 1;
    private static final int WM_ROP_AND     = 2;
    private static final int WM_ROP_XOR     = 3;

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

    private volatile boolean running = true;
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setScene(new Scene(new StackPane(canvas), DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Demo — Write Modes (SET/RESET + ROP + BITMASK)");
        stage.setResizable(false);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Disable text overlay.
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);

        // One group using planes 0..3 (4bpp indices 0..15)
        writeGroup0(vpu);

        // Clear VRAM planes 0..3
        for (int p = 0; p < 4; p++) {
            for (int i = 0; i < 0x4000; i++) {
                vpu.writeVramPlane(p, i, (byte) 0);
            }
        }

        // Build a colorful "dashboard" using write modes only.
        wmEnable(vpu, true, true, WM_ROP_REPLACE);
        vpu.writeMmio(REG_WM_PLANE_MASK, (byte) 0x0F);

        // Background fill: dark blue (index 1)
        wmFillRect(vpu, 0, 0, SRC_W, SRC_H, 0x01);

        // Some rectangles
        wmFillRect(vpu, 16, 16, 120, 40, 0x0E);
        wmFillRect(vpu, 24, 24, 104, 24, 0x04);

        wmFillRect(vpu, 16, 70, 180, 50, 0x03);
        wmFillRect(vpu, 24, 78, 164, 34, 0x0F);

        wmFillRect(vpu, 210, 16, 94, 104, 0x06);
        wmFillRect(vpu, 218, 24, 78, 88, 0x0A);

        // Turn off write mode after drawing (optional)
        wmEnable(vpu, false, false, WM_ROP_REPLACE);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runLoop(vpu, cyclesPerFrame), "vpu4-write-modes-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private void runLoop(VPU_v4 vpu, int cyclesPerFrame) {
        final long frameNanosTarget = (long) (1_000_000_000L / TARGET_HZ);
        long next = System.nanoTime();

        int x = 0;
        int dir = 1;

        while (running) {
            // Animate a 1-pixel vertical line using XOR + BITMASK (no VRAM reads, no per-plane writes).
            wmEnable(vpu, true, true, WM_ROP_XOR);
            vpu.writeMmio(REG_WM_PLANE_MASK, (byte) 0x0F);
            vpu.writeMmio(REG_WM_SETRESET, (byte) 0x0F); // src=0xFF on all planes

            // erase old line by xoring again (same op)
            drawVLineXor(vpu, x, 0, SRC_H);

            x += dir;
            if (x <= 0 || x >= (SRC_W - 1)) dir = -dir;

            // draw new line
            drawVLineXor(vpu, x, 0, SRC_H);

            wmEnable(vpu, false, false, WM_ROP_REPLACE);

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

    // ---------------------------------------------------------------------
    // Write modes helpers
    // ---------------------------------------------------------------------

    private static void wmEnable(VPU_v4 vpu, boolean enable, boolean useSetReset, int rop) {
        int ctrl = 0;
        if (enable) ctrl |= WM_EN;
        if (useSetReset) ctrl |= WM_USE_SETRESET;
        ctrl |= ((rop & 3) << WM_ROP_SHIFT);
        vpu.writeMmio(REG_WM_CTRL, (byte) ctrl);
    }

    private static void wmSetColor(VPU_v4 vpu, int colorIndex) {
        // bit per plane (planes 0..3)
        vpu.writeMmio(REG_WM_SETRESET, (byte) (colorIndex & 0x0F));
    }

    private static void wmFillRect(VPU_v4 vpu, int x, int y, int w, int h, int colorIndex) {
        wmSetColor(vpu, colorIndex);

        int x0 = clamp(x, 0, SRC_W);
        int y0 = clamp(y, 0, SRC_H);
        int x1 = clamp(x + w, 0, SRC_W);
        int y1 = clamp(y + h, 0, SRC_H);
        if (x1 <= x0 || y1 <= y0) return;

        int startByte = x0 >>> 3;
        int endByte = (x1 - 1) >>> 3;

        for (int yy = y0; yy < y1; yy++) {
            int rowBase = yy * BPL;
            for (int bx = startByte; bx <= endByte; bx++) {
                int mask = 0xFF;
                if (bx == startByte || bx == endByte) {
                    int left = Math.max(x0, bx << 3);
                    int right = Math.min(x1, (bx + 1) << 3);
                    mask = bitMaskForRange(left - (bx << 3), right - (bx << 3));
                }
                vpu.writeMmio(REG_WM_BITMASK, (byte) mask);
                vpu.writeVramPlane(0, rowBase + bx, (byte) 0x00); // data ignored in SET/RESET mode
            }
        }

        vpu.writeMmio(REG_WM_BITMASK, (byte) 0xFF);
    }

    private static void drawVLineXor(VPU_v4 vpu, int x, int y0, int y1) {
        int xx = clamp(x, 0, SRC_W - 1);
        int bx = xx >>> 3;
        int bit = 7 - (xx & 7);
        int mask = 1 << bit;

        vpu.writeMmio(REG_WM_BITMASK, (byte) mask);
        for (int y = y0; y < y1; y++) {
            int addr = y * BPL + bx;
            vpu.writeVramPlane(0, addr, (byte) 0xFF);
        }
        vpu.writeMmio(REG_WM_BITMASK, (byte) 0xFF);
    }

    private static int bitMaskForRange(int leftInclusive, int rightExclusive) {
        // 0..8
        int m = 0;
        for (int i = leftInclusive; i < rightExclusive; i++) {
            int bit = 7 - i;
            m |= (1 << bit);
        }
        return m & 0xFF;
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    // ---------------------------------------------------------------------
    // Group setup
    // ---------------------------------------------------------------------

    private static void writeGroup0(VPU_v4 vpu) {
        int base = GROUP_BASE;
        vpu.writeMmio(base + 0, (byte) 0);      // plane start
        vpu.writeMmio(base + 1, (byte) 4);      // plane count
        vpu.writeMmio(base + 2, (byte) 0);      // pal base
        writeMmio16(vpu, base + 3, 0);          // scrollX
        vpu.writeMmio(base + 5, (byte) 0);      // scrollY
        vpu.writeMmio(base + 6, (byte) 0);      // priority
        vpu.writeMmio(base + 7, (byte) (GF_LORES | GF_WRAP_X | GF_WRAP_Y | GF_OPAQUE0));
        writeMmio16(vpu, base + 8, 0);          // bplOfs

        // Disable other groups
        for (int g = 1; g < 4; g++) {
            vpu.writeMmio(GROUP_BASE + g * GROUP_STRIDE + 1, (byte) 0);
        }
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
