package io.github.robincores.r8;

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
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;

/**
 * OutRun-ish first prototype (MODE 1, 320x200 4bpp -> 640x400 output).
 *
 * Design goals:
 *  - 16-bit game state (u16/s16 style), fixed-point only, no float.
 *  - Per-scanline road renderer using lookup tables + segment curve table (ports to asm cleanly).
 *  - Java uses int for indexing (unavoidable), but values are kept in 16-bit ranges.
 *
 * Controls:
 *  - LEFT/RIGHT: steer
 *  - UP/DOWN: accelerate/brake
 */
public final class VpuDemoOutrunProtoMode1Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ---- VPU regs (must match your VPU) ----
    private static final int REG_CTRL    = 0x0000;
    private static final int REG_MODE    = 0x0002;
    private static final int REG_TX_CTRL = 0x0007;
    private static final int REG_OVL_MODE = 0x001F;

    private static final int PAL_BASE   = 0x0100;

    // Bits (must match your VPU)
    private static final int CTRL_ENABLE = 0x01;

    // MODE 1 source geometry
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int BPL   = SRC_W / 8; // 40 bytes per row per plane

    // Visual tuning
    private static final int HORIZON_Y = 62;          // 0..199 (source)
    private static final int ROAD_Y0   = HORIZON_Y;   // road starts here
    private static final int ROAD_Y1   = SRC_H - 1;

    // Road look in 16-color palette indices (0..15)
    private static final int COL_BLACK   = 0;
    private static final int COL_SKY0    = 1;
    private static final int COL_SKY1    = 2;
    private static final int COL_MTN0    = 3;
    private static final int COL_MTN1    = 4;
    private static final int COL_GRASS0  = 5;
    private static final int COL_GRASS1  = 6;
    private static final int COL_ROAD0   = 7;
    private static final int COL_ROAD1   = 8;
    private static final int COL_RUMB_R  = 9;
    private static final int COL_RUMB_W  = 10;
    private static final int COL_LANE    = 11;
    private static final int COL_UI      = 14;
    private static final int COL_WHITE   = 15;

    // Segment table (curve) - 256 segments, each segment influences curvature.
    // Curve values are small signed bytes (-8..+8) so it ports to asm trivially.
    private static final int SEG_N = 256;
    private static final int SEG_SHIFT = 6; // segment length = 64 "z units" (power-of-two)
    private static final byte[] SEG_CURVE = new byte[SEG_N];

    // Lookup tables per dy (distance from horizon)
    private static final int DY_N = (SRC_H - ROAD_Y0); // 200 - 62 = 138
    private static final short[] HALF_W = new short[DY_N];  // road half-width in pixels (0..160)
    private static final short[] ZMAP   = new short[DY_N];  // distance sample (0..65535)

    // Simple input mask (FX thread writes, emu thread reads)
    private volatile int keyMask;
    private static final int KM_LEFT  = 1;
    private static final int KM_RIGHT = 2;
    private static final int KM_UP    = 4;
    private static final int KM_DOWN  = 8;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        initSegments();
        initTables();

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());

        scene.setOnKeyPressed(e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.LEFT)  keyMask |= KM_LEFT;
            if (k == KeyCode.RIGHT) keyMask |= KM_RIGHT;
            if (k == KeyCode.UP)    keyMask |= KM_UP;
            if (k == KeyCode.DOWN)  keyMask |= KM_DOWN;
        });
        scene.setOnKeyReleased(e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.LEFT)  keyMask &= ~KM_LEFT;
            if (k == KeyCode.RIGHT) keyMask &= ~KM_RIGHT;
            if (k == KeyCode.UP)    keyMask &= ~KM_UP;
            if (k == KeyCode.DOWN)  keyMask &= ~KM_DOWN;
        });

        stage.setTitle("R816 VPU: OutRun Proto (MODE 1) — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoOutrunProtoMode1Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // MODE 1, text off
        mmio(vpu, REG_MODE, 1);
        mmio(vpu, REG_OVL_MODE, 0);
        mmio(vpu, REG_TX_CTRL, 0);
        mmio(vpu, REG_CTRL, CTRL_ENABLE);

        installPalette16(vpu);

        // --- 16-bit game state (u16/s16 style) ---
        int camZ = 0;          // u16 world "z" (wraps)
        int speed = 220;       // u16-ish (0..1023)  (units per frame)
        int playerX = 0;       // s16-ish (-200..200) lateral offset in pixels (source space)

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int frame = 0;

        while (running.get()) {
            int km = keyMask;

            // --- input (all integer) ---
            if ((km & KM_UP) != 0) speed += 10;
            else speed -= 2;
            if ((km & KM_DOWN) != 0) speed -= 18;

            if (speed < 0) speed = 0;
            if (speed > 900) speed = 900;

            int steer = 0;
            if ((km & KM_LEFT) != 0) steer -= 14;
            if ((km & KM_RIGHT) != 0) steer += 14;

            // steering strength depends on speed
            playerX += (steer * (speed + 200)) >> 8;
            if (playerX < -220) playerX = -220;
            if (playerX >  220) playerX =  220;

            camZ = (camZ + speed) & 0xFFFF;

            // optional: tiny palette “heat shimmer” (Amiga-ish cheap animation)
            if ((frame & 7) == 0) {
                pulseRoadPalette(vpu, (frame >> 3) & 15);
            }

            // --- render full 320x200 source into VRAM (MODE 1 bitplanes) ---
            renderFrameMode1(vpu, camZ, speed, playerX, frame);

            // --- run one frame of VPU time ---
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
                int spd = speed;
                final String title = String.format("R816 VPU: OutRun Proto (MODE 1) — FPS %.1f  speed=%d", fps, spd);
                Platform.runLater(() -> { if (stage != null) stage.setTitle(title); });
                fpsT0 = now;
                fpsFrames = 0;
            }

            frame++;

            try { Thread.sleep(14); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Platform.runLater(() -> { if (stage != null) stage.close(); });
    }

    // ---------------------------------------------------------------------
    // Rendering (MODE 1 bitplanes)
    // ---------------------------------------------------------------------

    private static void renderFrameMode1(VPU_v2 vpu, int camZ_u16, int speed, int playerX_s16, int frame) {
        // Sky + mountains
        for (int y = 0; y < ROAD_Y0; y++) {
            renderSkyMountainsScanline(vpu, y, camZ_u16, frame);
        }

        // Road
        final int mid = SRC_W / 2;

        for (int y = ROAD_Y0; y <= ROAD_Y1; y++) {
            int dy = y - ROAD_Y0; // 0..DY_N-1

            int half = HALF_W[dy] & 0xFFFF;      // pixels
            int z = (camZ_u16 + (ZMAP[dy] & 0xFFFF)) & 0xFFFF;

            // Segment-based curvature (small signed byte)
            int seg = (z >>> SEG_SHIFT) & 0xFF;
            int curve = SEG_CURVE[seg]; // -8..+8

            // Project curve more strongly near bottom:
            // shift = curve * (dy+8) / 4  (all integer)
            int curveShift = (curve * (dy + 8)) >> 2;

            int center = mid + curveShift + (playerX_s16 >> 1);

            int rumble = 2 + (half >> 4);  // grows a bit with distance
            if (rumble > 10) rumble = 10;

            int leftR  = center - half;
            int rightR = center + half;

            int left0  = leftR - rumble;
            int right0 = rightR + rumble;

            // stripes: uses z and dy only
            int stripe = ((z >> 6) + (dy >> 3)) & 1;

            // grass toggle (gives motion)
            int grassStripe = ((z >> 5) + (dy >> 4)) & 1;

            // lane marker phase
            int laneOn = ((z >> 7) & 1);

            int rowBase = y * BPL;

            for (int bx = 0; bx < BPL; bx++) {
                int x0 = bx << 3; // 8 pixels
                int b0 = 0, b1 = 0, b2 = 0, b3 = 0;

                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    int col;

                    if (x < left0 || x > right0) {
                        col = (grassStripe == 0) ? COL_GRASS0 : COL_GRASS1;
                    } else if (x < leftR) {
                        col = (stripe == 0) ? COL_RUMB_R : COL_RUMB_W;
                    } else if (x > rightR) {
                        col = (stripe == 0) ? COL_RUMB_R : COL_RUMB_W;
                    } else {
                        col = (stripe == 0) ? COL_ROAD0 : COL_ROAD1;

                        // lane marker: thin white center line, dotted
                        int dx = x - center;
                        if (dx == 0 || dx == 1) {
                            if (laneOn != 0 && dy > 8) col = COL_LANE;
                        }
                    }

                    int bit = 7 - i;
                    if ((col & 1) != 0) b0 |= (1 << bit);
                    if ((col & 2) != 0) b1 |= (1 << bit);
                    if ((col & 4) != 0) b2 |= (1 << bit);
                    if ((col & 8) != 0) b3 |= (1 << bit);
                }

                int ofs = rowBase + bx;
                vpu.writeVramPlane(0, ofs, (byte) b0);
                vpu.writeVramPlane(1, ofs, (byte) b1);
                vpu.writeVramPlane(2, ofs, (byte) b2);
                vpu.writeVramPlane(3, ofs, (byte) b3);
            }
        }
    }

    private static void renderSkyMountainsScanline(VPU_v2 vpu, int y, int camZ_u16, int frame) {
        int rowBase = y * BPL;

        // simple integer gradient: top is SKY0, near horizon is SKY1
        int t = (y * 255) / Math.max(1, (ROAD_Y0 - 1)); // 0..255
        int sky = (t < 128) ? COL_SKY0 : COL_SKY1;

        // parallax-ish phase from camZ (no float)
        int phase = ((camZ_u16 >> 4) + (frame >> 1)) & 255;

        for (int bx = 0; bx < BPL; bx++) {
            int x0 = bx << 3;
            int b0 = 0, b1 = 0, b2 = 0, b3 = 0;

            for (int i = 0; i < 8; i++) {
                int x = x0 + i;

                // triangular wave mountains (cheap + portable)
                int tri = tri8((x + phase) & 255);        // 0..127
                int h = (tri >> 3) + 10;                 // 10..25
                int mtnTop = ROAD_Y0 - h;                // mountain silhouette top

                int col = (y >= mtnTop) ? ((tri & 16) == 0 ? COL_MTN0 : COL_MTN1) : sky;

                int bit = 7 - i;
                if ((col & 1) != 0) b0 |= (1 << bit);
                if ((col & 2) != 0) b1 |= (1 << bit);
                if ((col & 4) != 0) b2 |= (1 << bit);
                if ((col & 8) != 0) b3 |= (1 << bit);
            }

            int ofs = rowBase + bx;
            vpu.writeVramPlane(0, ofs, (byte) b0);
            vpu.writeVramPlane(1, ofs, (byte) b1);
            vpu.writeVramPlane(2, ofs, (byte) b2);
            vpu.writeVramPlane(3, ofs, (byte) b3);
        }
    }

    // tri wave 0..127 (no float)
    private static int tri8(int x) {
        int v = x & 255;
        if (v > 127) v = 255 - v;
        return v;
    }

    // ---------------------------------------------------------------------
    // Tables (all integer)
    // ---------------------------------------------------------------------

    private static void initTables() {
        // Perspective-ish road width and distance sampling.
        // No floats; use simple integer curve that looks OK and ports easily.
        for (int dy = 0; dy < DY_N; dy++) {
            int d = dy + 1;

            // half width grows non-linearly (quadratic-ish)
            int half = 18 + ((d * d) >> 5); // tune
            if (half > 155) half = 155;
            HALF_W[dy] = (short) half;

            // z map: "far" changes slower, "near" changes faster
            // Use reciprocal-like behavior via integer divide.
            int z = (5200 / d) + (d * 6);
            ZMAP[dy] = (short) (z & 0xFFFF);
        }
    }

    private static void initSegments() {
        // Build a looping curve pattern:
        // straight -> right -> straight -> left -> chicane.
        for (int i = 0; i < SEG_N; i++) SEG_CURVE[i] = 0;

        int p = 0;

        // straight 32
        p = segFill(p, 32, 0);

        // gentle right 48
        p = segRamp(p, 24, 0, 6);
        p = segFill(p, 16, 6);
        p = segRamp(p, 8, 6, 0);

        // straight 24
        p = segFill(p, 24, 0);

        // gentle left 56
        p = segRamp(p, 24, 0, -7);
        p = segFill(p, 20, -7);
        p = segRamp(p, 12, -7, 0);

        // chicane 64
        p = segRamp(p, 16, 0, 7);
        p = segRamp(p, 16, 7, -7);
        p = segRamp(p, 16, -7, 0);
        p = segFill(p, 16, 0);

        // rest straight
        while (p < SEG_N) p = segFill(p, 1, 0);
    }

    private static int segFill(int p, int n, int v) {
        for (int i = 0; i < n && p < SEG_N; i++) SEG_CURVE[p++] = (byte) v;
        return p;
    }

    private static int segRamp(int p, int n, int a, int b) {
        if (n <= 0) return p;
        int dv = b - a;
        for (int i = 0; i < n && p < SEG_N; i++) {
            int v = a + (dv * i) / (n - 1);
            if (v < -8) v = -8;
            if (v > 8)  v = 8;
            SEG_CURVE[p++] = (byte) v;
        }
        return p;
    }

    // ---------------------------------------------------------------------
    // Palette (16 colors only)
    // ---------------------------------------------------------------------

    private static void installPalette16(VPU_v2 vpu) {
        // 16-color “OutRun-ish” palette (RGB888).
        int[] pal = new int[] {
                0x000000, // 0 black
                0x102050, // 1 sky top
                0x2A74C8, // 2 sky near horizon
                0x102018, // 3 mountain dark
                0x1E3A2C, // 4 mountain light
                0x0E6A22, // 5 grass A
                0x14A834, // 6 grass B
                0x303030, // 7 road A
                0x202020, // 8 road B
                0xB00000, // 9 rumble red
                0xE8E8E8, // 10 rumble white
                0xFFFFFF, // 11 lane white
                0xFFAA00, // 12 (free)
                0xFF00FF, // 13 (free)
                0xC0D0FF, // 14 UI
                0xFFFFFF  // 15 white
        };

        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, pal[i]);
    }

    // tiny palette “pulse” on road grays (cheap shimmer; can remove)
    private static void pulseRoadPalette(VPU_v2 vpu, int phase0_15) {
        // Road colors 7/8
        int p = phase0_15 & 15;
        int a = 0x18 + p; // 0x18..0x27
        int b = 0x12 + (p >> 1);

        int c7 = (a << 16) | (a << 8) | a;
        int c8 = (b << 16) | (b << 8) | b;

        writeRgb888ToPal(vpu, 7, c7);
        writeRgb888ToPal(vpu, 8, c8);
    }

    private static void writeRgb888ToPal(VPU_v2 vpu, int idx, int rgb888) {
        int r = (rgb888 >>> 16) & 0xFF;
        int g = (rgb888 >>> 8) & 0xFF;
        int b = (rgb888) & 0xFF;

        int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >>> 3);

        int o = PAL_BASE + (idx << 1);
        vpu.writeMmio(o,     (byte) (rgb565 & 0xFF));
        vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
    }

    private static void mmio(VPU_v2 vpu, int reg, int value8) {
        vpu.writeMmio(reg, (byte) (value8 & 0xFF));
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