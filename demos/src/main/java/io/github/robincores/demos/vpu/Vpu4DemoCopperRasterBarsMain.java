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
 * VPU v4 demo: scanline copper that writes palette entries to create raster bars.
 * Also showcases COP_OFS (double-buffering the copper list).
 *
 * IMPORTANT: In the current VPU_v4 implementation, TX_CTRL is at 0x0006.
 * If you write 0x0008 you'll actually hit TX_CUR_Y and text will remain enabled,
 * painting the screen black (spaces with bg=0).
 */
public final class Vpu4DemoCopperRasterBarsMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private static final double TARGET_HZ = 70.0;

    // MMIO (correct for VPU_v4)
    private static final int REG_TX_CTRL = 0x0006;

    private static final int REG_COP_CTRL  = 0x0020;
    private static final int REG_COP_LEN_L = 0x0021;
    private static final int REG_COP_LEN_H = 0x0022;
    private static final int REG_COP_OFS_L = 0x0023;
    private static final int REG_COP_OFS_H = 0x0024;

    private static final int COP_EN = 0x01;

    private static final int PAL_BASE = 0x0100;

    // Groups
    private static final int GROUP_BASE = 0x0400;
    private static final int GROUP_STRIDE = 0x0010;

    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;
    private static final int GF_OPAQUE0 = 0x08;

    // VRAM
    private static final int SRC_H = 200;
    private static final int BPL = 40;

    private static final int VB_TBL = 9;

    private static final int COP_STRIDE = 8;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END = 0x80;

    private volatile boolean running = true;
    private Thread emuThread;
    private AnimationTimer fxTimer;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setScene(new Scene(new StackPane(canvas), DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight()));
        stage.setTitle("VPU v4 Demo — Copper Raster Bars (palette writes + COP_OFS)");
        stage.setResizable(false);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v4 vpu = new VPU_v4(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        // Disable text overlay (so graphics are visible)
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);

        // Single group: plane0 only, but we force every pixel to index=1.
        // Copper changes palette entry #1 per scanline -> visible raster bars.
        writeGroup0(vpu);
        fillPlane0AllOnes(vpu);

        // Build two copper lists (A and B) in the tables bank.
        int ofsA = 0x1000;
        int ofsB = 0x1800;
        int lenA = buildCopperPaletteBars(vpu, ofsA, false);
        int lenB = buildCopperPaletteBars(vpu, ofsB, true);

        // Use list A initially.
        setCopper(vpu, ofsA, lenA);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runLoop(vpu, cyclesPerFrame, ofsA, lenA, ofsB, lenB), "vpu4-copper-bars-demo");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running = false);
    }

    private void runLoop(VPU_v4 vpu, int cyclesPerFrame, int ofsA, int lenA, int ofsB, int lenB) {
        final long frameNanosTarget = (long) (1_000_000_000L / TARGET_HZ);
        long next = System.nanoTime();

        int frames = 0;
        boolean useB = false;

        while (running) {
            // Flip copper list every ~2 seconds to demonstrate COP_OFS double-buffer.
            if ((frames % 140) == 0) {
                useB = !useB;
                if (useB) setCopper(vpu, ofsB, lenB);
                else      setCopper(vpu, ofsA, lenA);
            }

            vpu.tick(cyclesPerFrame);

            frames++;
            next += frameNanosTarget;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport.parkNanos(sleep);
            else next = System.nanoTime();
        }

        Platform.runLater(() -> {
            if (stage != null) stage.close();
        });
    }

    // ---------------------------------------------------------------------
    // Group + VRAM
    // ---------------------------------------------------------------------

    private static void writeGroup0(VPU_v4 vpu) {
        int base = GROUP_BASE;

        vpu.writeMmio(base + 0, (byte) 0);      // plane start
        vpu.writeMmio(base + 1, (byte) 1);      // plane count
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

    private static void fillPlane0AllOnes(VPU_v4 vpu) {
        for (int y = 0; y < SRC_H; y++) {
            int row = y * BPL;
            for (int bx = 0; bx < BPL; bx++) {
                vpu.writeVramPlane(0, row + bx, (byte) 0xFF);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Copper
    // ---------------------------------------------------------------------

    private static void setCopper(VPU_v4 vpu, int copOfs, int copLenBytes) {
        vpu.writeMmio(REG_COP_CTRL, (byte) 0);
        writeMmio16(vpu, REG_COP_OFS_L, copOfs & 0x3FFF);
        writeMmio16(vpu, REG_COP_LEN_L, copLenBytes & 0xFFFF);
        vpu.writeMmio(REG_COP_CTRL, (byte) COP_EN);
    }

    private static int buildCopperPaletteBars(VPU_v4 vpu, int copOfs, boolean alternate) {
        // Update palette entry #1 (index=1) at different scanlines.
        final int targetReg = PAL_BASE + 2; // entry 1, little-endian

        int pos = 0;
        for (int y = 0; y < 400; y++) {
            int rgb565 = rainbow565(y, alternate);

            writeTbl16(vpu, copOfs + pos + 0, y);
            writeTbl16(vpu, copOfs + pos + 2, targetReg);

            vpu.writeVramPlane(VB_TBL, (copOfs + pos + 4) & 0x3FFF, (byte) (rgb565 & 0xFF));
            vpu.writeVramPlane(VB_TBL, (copOfs + pos + 5) & 0x3FFF, (byte) ((rgb565 >>> 8) & 0xFF));
            vpu.writeVramPlane(VB_TBL, (copOfs + pos + 6) & 0x3FFF, (byte) COP_FLAG_WRITE16);
            vpu.writeVramPlane(VB_TBL, (copOfs + pos + 7) & 0x3FFF, (byte) 0);

            pos += COP_STRIDE;
        }

        // END entry
        writeTbl16(vpu, copOfs + pos + 0, 0);
        writeTbl16(vpu, copOfs + pos + 2, 0);
        vpu.writeVramPlane(VB_TBL, (copOfs + pos + 4) & 0x3FFF, (byte) 0);
        vpu.writeVramPlane(VB_TBL, (copOfs + pos + 5) & 0x3FFF, (byte) 0);
        vpu.writeVramPlane(VB_TBL, (copOfs + pos + 6) & 0x3FFF, (byte) COP_FLAG_END);
        vpu.writeVramPlane(VB_TBL, (copOfs + pos + 7) & 0x3FFF, (byte) 0);
        pos += COP_STRIDE;

        return pos;
    }

    private static int rainbow565(int y, boolean alternate) {
        int t = alternate ? (399 - y) : y;
        int phase = (t / 57) % 7;
        int u = (t % 57) * 255 / 56;

        int r = 0, g = 0, b = 0;
        switch (phase) {
            case 0 -> { r = 255; g = u;   b = 0;   }
            case 1 -> { r = 255 - u; g = 255; b = 0; }
            case 2 -> { r = 0;   g = 255; b = u;   }
            case 3 -> { r = 0;   g = 255 - u; b = 255; }
            case 4 -> { r = u;   g = 0;   b = 255; }
            case 5 -> { r = 255; g = 0;   b = 255 - u; }
            default -> { r = 255; g = u / 3; b = u / 3; }
        }

        return (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3)) & 0xFFFF;
    }

    private static void writeTbl16(VPU_v4 vpu, int addr, int value) {
        vpu.writeVramPlane(VB_TBL, (addr) & 0x3FFF, (byte) (value & 0xFF));
        vpu.writeVramPlane(VB_TBL, (addr + 1) & 0x3FFF, (byte) ((value >>> 8) & 0xFF));
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
