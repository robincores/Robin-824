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
 * Manual VPU demo for MODE 3 showing FB_BASE vertical panning inside a larger virtual world.
 *
 * Notes:
 * - The emulation loop runs on a background thread (DO NOT block the JavaFX Application Thread),
 *   because VPU.requestBlit() uses Platform.runLater().
 * - If you run as a JPMS module (-m ...), ensure your module-info exports/opens io.github.robincores.r8 to javafx.graphics.
 */
public final class VpuDemoMode3ScrollMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VpuDemoMode3ScrollMain");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        // Minimal CPU/sink wiring (VPU uses InterruptSink only for vblank IRQ).
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoMode3ScrollMain-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU vpu, int cyclesPerFrame) {

// Mode 3: 160x200 8bpp Mode-X-ish, scaled to 640x400.
        vpu.writeMmio(0x0002, (byte) 3);

// Overlay title
        vpu.writeMmio(0x0007, (byte) (0x01 | 0x04)); // TX_EN + TRANSPARENT_BG
        writeText(vpu, 0, 0, "MODE 3: Virtual-height vertical scroll using FB_BASE", 0x0F, 0x00);
        writeText(vpu, 0, 1, "World=160x400, View=160x200 (close window to stop)", 0x0E, 0x00);

        final int srcW = 160;
        final int viewH = 200;
        final int worldH = 400;      // fits: bpl=40, 40*400=16000 per plane (< 16384)
        final int bpl = srcW / 4;    // 40 bytes/line/plane

// Fill the "world" once (160x400)
        for (int y = 0; y < worldH; y++) {
            int lineBase = y * bpl;
            for (int byteX = 0; byteX < bpl; byteX++) {
                int x0 = (byteX << 2);

                int idx0 = (x0 + y) & 0xFF;
                int idx1 = (x0 + 1 + (y * 2)) & 0xFF;
                int idx2 = (x0 + 2 + (y * 3)) & 0xFF;
                int idx3 = (x0 + 3 + (y * 5)) & 0xFF;

                int ofs = lineBase + byteX;
                vpu.writeVramPlane(0, ofs, (byte) idx0);
                vpu.writeVramPlane(1, ofs, (byte) idx1);
                vpu.writeVramPlane(2, ofs, (byte) idx2);
                vpu.writeVramPlane(3, ofs, (byte) idx3);
            }
        }

        int frame = 0;
        while (running.get()) {
            // Scroll Y in [0, worldH-viewH] with a triangle wave
            int maxScroll = worldH - viewH; // 200
            int t = frame % (maxScroll * 2);
            int scrollY = (t <= maxScroll) ? t : (maxScroll * 2 - t);

            int base = scrollY * bpl; // FB_BASE is per-plane byte offset
            vpu.writeMmio(0x0005, (byte) (base & 0xFF));
            vpu.writeMmio(0x0006, (byte) ((base >>> 8) & 0xFF));

            // Run one frame
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) vpu.tick(chunk);

            frame++;
            try { Thread.sleep(14); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });

    }




    private static void writeText(VPU vpu, int x, int y, String s, int fg, int bg) {
        // TEXT_BASE = 0x0300, 80x25, 2 bytes per cell: [ch, attr], attr=(bg<<4)|fg
        int attr = ((bg & 0x0F) << 4) | (fg & 0x0F);
        int cols = 80;
        int base = 0x0300 + (y * cols + x) * 2;
        for (int i = 0; i < s.length(); i++) {
            int o = base + (i * 2);
            vpu.writeMmio(o, (byte) (s.charAt(i) & 0xFF));
            vpu.writeMmio(o + 1, (byte) attr);
        }
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
