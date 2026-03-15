package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v2;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8BusCore.EXTERNAL_INTERRUPT_MASK;

/**
 * Demo: MODE 1 (320x200 4bpp -> 640x400) parallax using "bitplane split overlay":
 *
 *   index = bg2 | (fg2 << 2)
 *   bg2 = 0..3   (2-bit background: sky+ground)
 *   fg2 = 0..3   (2-bit foreground overlay: 0=transparent, 1..3 override colors)
 *
 * Palette trick:
 *   - indices 0..3 (BG) are updated each frame (cheap "Amiga-style" cycling)
 *   - indices 4..15 are duplicates so FG overrides BG when fg!=0
 *
 * No copper, no sprites, no blitter required.
 */
public final class VpuDemoParallaxBitplaneSplitMode1Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // output framebuffer
            640, 400,   // canvas
            400,        // vblankStart
            449,        // scanlinesPerFrame
            400         // cyclesPerScanline
    );

    // ---- VPU MMIO offsets ----
    private static final int REG_CTRL       = 0x0000;
    private static final int REG_MODE       = 0x0002;
    private static final int REG_TX_CTRL    = 0x0007;
    private static final int REG_FB_BASE_L  = 0x0005;
    private static final int REG_FB_BASE_H  = 0x0006;

    private static final int PAL_BASE = 0x0100;

    // MODE 1 source resolution
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL   = SRC_W / 8; // 40 bytes/row per plane in bitplane modes

    // Bands
    private static final int SKY_H      = 110;
    private static final int GROUND_Y0  = 140;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    // Fast sine table [-127..127]
    private static final int[] SIN256 = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            SIN256[i] = (int) Math.round(Math.sin(i * (2.0 * Math.PI / 256.0)) * 127.0);
        }
    }
    private static int sin(int i) { return SIN256[i & 255]; }

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        stage.setTitle("VPU Demo: MODE 1 Bitplane-Split Parallax — FPS --");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);
        VPU_v2 vpu = new VPU_v2(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        fxTimer = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        fxTimer.start();

        final int cyclesPerFrame = DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoParallaxBitplaneSplitMode1Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // Mode 1, text off, enable
        vpu.writeMmio(REG_MODE, (byte) 1);
        vpu.writeMmio(REG_TX_CTRL, (byte) 0);
        vpu.writeMmio(REG_CTRL, (byte) 0x01);

        // FB base = 0
        vpu.writeMmio(REG_FB_BASE_L, (byte) 0);
        vpu.writeMmio(REG_FB_BASE_H, (byte) 0);

        // Install FG override palette (4..15) once; BG (0..3) updated per frame
        installFgOverridePalette(vpu);

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int t = 0;

        while (running.get()) {
            // Amiga-ish palette trick: animate ONLY BG entries 0..3
            updateBgPalette(vpu, t);

            // Render the whole 320x200 frame into bitplanes:
            //  - planes 0..1: bg (2-bit)
            //  - planes 2..3: fg (2-bit overlay, 0=transparent)
            renderFrameMode1_BitplaneSplit(vpu, t);

            // Run one frame worth of VPU time
            int chunk = 5_000;
            for (int done = 0; done < cyclesPerFrame; done += chunk) {
                vpu.tick(chunk);
            }

            // FPS title
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU Demo: MODE 1 Bitplane-Split Parallax — FPS %.1f", fps);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            t = (t + 2) & 255;

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Core: render Mode 1 frame using BG(2-bit) + FG(2-bit overlay)
    // ---------------------------------------------------------------------

    private static void renderFrameMode1_BitplaneSplit(VPU_v2 vpu, int t) {
        // Parallax speeds (in "phase units")
        int tSky  = (t >> 2);     // slow
        int tMid  = (t >> 1);     // medium
        int tNear = (t << 1);     // fast

        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * BPL;

            for (int bx = 0; bx < BPL; bx++) {
                int x0 = bx << 3; // 8 pixels

                int p0 = 0, p1 = 0, p2 = 0, p3 = 0;

                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;

                    int bg = bg2(x, y, tSky);              // 0..3
                    int fg = fg2(x, y, tMid, tNear);       // 0..3 (0 transparent)

                    int idx = (bg & 3) | ((fg & 3) << 2);  // 0..15

                    int bit = 7 - i;
                    if ((idx & 0x01) != 0) p0 |= (1 << bit);
                    if ((idx & 0x02) != 0) p1 |= (1 << bit);
                    if ((idx & 0x04) != 0) p2 |= (1 << bit);
                    if ((idx & 0x08) != 0) p3 |= (1 << bit);
                }

                int ofs = rowBase + bx;
                vpu.writeVramPlane(0, ofs, (byte) p0);
                vpu.writeVramPlane(1, ofs, (byte) p1);
                vpu.writeVramPlane(2, ofs, (byte) p2);
                vpu.writeVramPlane(3, ofs, (byte) p3);
            }
        }
    }

    // BG: 2-bit (0..3)
    // Use only 0..1 in sky band, 2..3 in ground band.
    private static int bg2(int x, int y, int tSky) {
        if (y < SKY_H) {
            // Two-tone sky with moving clouds (very cheap)
            int cloud =
                    sin((x * 3) + (tSky * 6)) +
                            sin((y * 9) + (tSky * 11)) +
                            sin(((x + y) * 2) + (tSky * 5));

            // push clouds toward upper sky; near horizon is calmer
            int bias = (SKY_H - y) * 2;
            int v = cloud + bias;

            // sky index 0/1
            int skyShade = (y > (SKY_H - 25)) ? 1 : 0;
            int cloudOn = (v > 180) ? 1 : 0;

            return (cloudOn ^ skyShade) & 1; // 0..1
        }

        // Ground: dither + subtle wave (still 2-bit: 2..3)
        int dy = y - SKY_H;
        int wave = sin((x * 4) + (tSky * 9) + (dy * 2));
        int dither = ((x >> 3) + (y >> 2)) & 1;

        int shade = (wave > 0) ? 1 : 0;
        int g = (dither ^ shade) & 1; // 0..1
        return 2 + g; // 2..3
    }

    // FG: 2-bit (0..3), where 0 is transparent overlay.
    private static int fg2(int x, int y, int tMid, int tNear) {
        // --- Mid layer: mountains (fg=1) ---
        // make a wavy ridge that moves horizontally (medium speed)
        int xm = x + (tMid * 2);
        int ridge =
                78
                        + (sin((xm * 2) + 20) * 18) / 127
                        + (sin((xm * 5) + 90) * 10) / 127;

        if (y >= ridge && y < GROUND_Y0) {
            // add some sparse highlight pixels on ridge (fg=3) for depth
            if (y == ridge && ((x + (tMid << 1)) & 7) == 0) return 3;
            return 1;
        }

        // --- Near layer: ground stripes (fg=2) ---
        if (y >= GROUND_Y0) {
            int xn = x + (tNear);
            int band = ((xn >> 2) + (y >> 1)) & 1;
            if (band == 0) return 2;

            // occasional spark (fg=3) — cheap “glint”
            int gl = (sin((xn * 7) + (y * 3)) + 127) >> 6; // 0..3
            if (gl == 3 && ((x ^ y) & 31) == 0) return 3;

            return 0;
        }

        return 0;
    }

    // ---------------------------------------------------------------------
    // Palette
    // ---------------------------------------------------------------------

    private static void installFgOverridePalette(VPU_v2 vpu) {
        // BG (0..3) is updated every frame, so just initialize once.
        // FG override trick:
        //   fg=1 -> indices 4..7  all same color
        //   fg=2 -> indices 8..11 all same color
        //   fg=3 -> indices 12..15 all same color

        int colMount = 0x3B1B5A; // purple mountain
        int colNear  = 0x0E3A2A; // dark green near layer
        int colHi    = 0xE8D2FF; // bright highlight

        for (int i = 4; i <= 7; i++)  writeRgb888ToPal(vpu, i, colMount);
        for (int i = 8; i <= 11; i++) writeRgb888ToPal(vpu, i, colNear);
        for (int i = 12; i <= 15; i++) writeRgb888ToPal(vpu, i, colHi);

        // initial BG
        updateBgPalette(vpu, 0);
    }

    private static void updateBgPalette(VPU_v2 vpu, int t) {
        // Two sky shades (0..1)
        int b0 = 50 + (sin(t * 2) + 127) / 10;
        int b1 = 90 + (sin(t * 2 + 32) + 127) / 8;

        int sky0 = rgbClamp(
                10 + (sin(t * 3) + 127) / 12,
                25,
                b0
        );
        int sky1 = rgbClamp(
                25 + (sin(t * 3 + 64) + 127) / 10,
                55,
                b1
        );

        // Two ground shades (2..3)
        int g0 = rgbClamp(25, 25, 25 + (sin(t * 2) + 127) / 16);
        int g1 = rgbClamp(55, 55, 55 + (sin(t * 2 + 96) + 127) / 16);

        writeRgb888ToPal(vpu, 0, sky0);
        writeRgb888ToPal(vpu, 1, sky1);
        writeRgb888ToPal(vpu, 2, g0);
        writeRgb888ToPal(vpu, 3, g1);
    }

    private static int rgbClamp(int r, int g, int b) {
        r = (r < 0) ? 0 : Math.min(255, r);
        g = (g < 0) ? 0 : Math.min(255, g);
        b = (b < 0) ? 0 : Math.min(255, b);
        return (r << 16) | (g << 8) | b;
    }

    private static void writeRgb888ToPal(VPU_v2 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;
        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    @Override
    public void stop() {
        running.set(false);

        if (fxTimer != null) {
            fxTimer.stop();
            fxTimer = null;
        }

        if (emuThread != null) {
            try { emuThread.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}