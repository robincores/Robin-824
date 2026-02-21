package io.github.robincores.r8;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo: MODE 2 (320x200 Mode-X 8bpp) scaled to 640x400.
 * Draws a solid blue top-left quadrant (160x100) with NO text overlay.
 */
public final class VpuDemoMode2QuadrantMain extends Application {

    // Same timing/output style you used in the Mode3 demo (640x400 output).
    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // VPU MMIO offsets (match VPU.java)
    private static final int REG_CTRL         = 0x0000;
    private static final int REG_MODE         = 0x0002;
    private static final int REG_FB_BASE_L    = 0x0005;
    private static final int REG_FB_BASE_H    = 0x0006;
    private static final int REG_TX_CTRL      = 0x0007;

    // Graphics helpers (match VPU.java)
    private static final int REG_XPAN         = 0x000C;
    private static final int REG_WR_PLANE_MASK= 0x000D;
    private static final int REG_BIT_MASK     = 0x000E;
    private static final int REG_SR_ENABLE    = 0x0010;
    private static final int REG_ROP          = 0x0011;
    private static final int REG_WR_MODE      = 0x0012;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VPU Demo: MODE 2 (320x200 8bpp Mode-X) — quadrant fill (no text)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoMode2QuadrantMain-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame) {
        // --- Configure graphics ---
        vpu.writeMmio(REG_MODE, (byte) 2);      // MODE 2: 320x200 8bpp Mode-X
        vpu.writeMmio(REG_TX_CTRL, (byte) 0x00); // IMPORTANT: disable text overlay completely

        // Framebuffer base = 0
        vpu.writeMmio(REG_FB_BASE_L, (byte) 0x00);
        vpu.writeMmio(REG_FB_BASE_H, (byte) 0x00);

        // Sanitize write-assist regs (avoid leftovers from previous demos)
        vpu.writeMmio(REG_WR_PLANE_MASK, (byte) 0x00); // legacy selected-plane semantics
        vpu.writeMmio(REG_BIT_MASK,      (byte) 0xFF);
        vpu.writeMmio(REG_SR_ENABLE,     (byte) 0x00);
        vpu.writeMmio(REG_ROP,           (byte) 0x00); // REPLACE
        vpu.writeMmio(REG_WR_MODE,       (byte) 0x00);
        vpu.writeMmio(REG_XPAN,          (byte) 0x00);

        // Ensure enabled (constructor defaults to enabled, but harmless to set)
        vpu.writeMmio(REG_CTRL, (byte) 0x01);

        // --- Draw: top-left quadrant x=[0..159], y=[0..99] ---
        // Mode 2 uses 80 bytes per line per plane (320/4).
        final int bpl = 80;
        final int quadWBytes = 160 / 4; // 40 bytes
        final int quadH = 100;

        final byte BLUE = (byte) 0x01; // default VGA-ish palette index for blue

        for (int y = 0; y < quadH; y++) {
            int lineBase = y * bpl;
            for (int byteX = 0; byteX < quadWBytes; byteX++) {
                int ofs = lineBase + byteX;

                // Write same index into all 4 planes at this offset -> fills 4 pixels (x mod 4)
                vpu.writeVramPlane(0, ofs, BLUE);
                vpu.writeVramPlane(1, ofs, BLUE);
                vpu.writeVramPlane(2, ofs, BLUE);
                vpu.writeVramPlane(3, ofs, BLUE);
            }
        }

        // --- Run frames so the VPU renders continuously ---
        final int chunk = 5_000;
        while (running.get()) {
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(chunk);
            }
            try {
                Thread.sleep(14);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Platform.runLater(() -> {
            if (stage != null) stage.close();
        });
    }

    @Override
    public void stop() {
        running.set(false);
        if (emuThread != null) {
            try {
                emuThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}