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
 * Demo: MODE 1 (320x200 4bpp) — blitter COPY scroll + blitter FILL XOR highlight + palette cycling.
 *
 * What it shows:
 *  - Fine scroll: XPAN (1 pixel per frame)
 *  - Coarse scroll: every 8 pixels, BLT COPY shifts framebuffer left by 1 byte (8 px)
 *  - New right column injected after each coarse shift (procedural pattern)
 *  - BLT FILL with ROP=XOR draws/erases a moving "highlight window" without redrawing background
 *  - Palette cycling (Amiga-ish trick): rotate colors 1..15 while VRAM indices stay the same
 *
 * Notes:
 *  - No copper used.
 *  - No raster IRQ needed (batch render at vblank).
 */
public final class VpuDemoBlitterScrollXorMode1Main extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,   // output framebuffer
            640, 400,   // canvas
            400,        // vblankStart
            449,        // scanlinesPerFrame
            400         // cyclesPerScanline
    );

    // ---- VPU regs (must match your VPU) ----
    private static final int REG_CTRL          = 0x0000;
    private static final int REG_MODE          = 0x0002;
    private static final int REG_TX_CTRL       = 0x0007;
    private static final int REG_XPAN          = 0x000C;

    private static final int REG_WR_PLANE_MASK = 0x000D;
    private static final int REG_BIT_MASK      = 0x000E;
    private static final int REG_SR_COLOR      = 0x000F;
    private static final int REG_SR_ENABLE     = 0x0010;
    private static final int REG_ROP           = 0x0011;

    private static final int REG_OVL_MODE      = 0x001F;

    private static final int REG_BLT_CTRL        = 0x0020;
    private static final int REG_BLT_SRC_L       = 0x0021; // +1 = SRC_H
    private static final int REG_BLT_DST_L       = 0x0023; // +1 = DST_H
    private static final int REG_BLT_W_L         = 0x0025; // +1 = W_H
    private static final int REG_BLT_H           = 0x0027;
    private static final int REG_BLT_SRC_PITCH_L = 0x0028; // +1 = SRC_PITCH_H
    private static final int REG_BLT_DST_PITCH_L = 0x002A; // +1 = DST_PITCH_H
    private static final int REG_BLT_FILL        = 0x002C;
    private static final int REG_BLT_PLANE_MASK  = 0x002D;

    private static final int PAL_BASE          = 0x0100;

    // Bits (must match your VPU)
    private static final int CTRL_ENABLE = 0x01;

    private static final int BLT_START = 0x01;
    private static final int BLT_FILL  = 0x02;

    private static final int ROP_REPLACE = 0;
    private static final int ROP_XOR     = 1;

    // MODE 1 geometry (source)
    private static final int SRC_W = 320;
    private static final int SRC_H = 200;
    private static final int ROW_BYTES = SRC_W / 8; // 40 bytes/row per plane

    // Sine table for nice motion
    private static final int[] SIN256 = new int[256]; // -127..127
    static {
        for (int i = 0; i < 256; i++) {
            SIN256[i] = (int) Math.round(Math.sin(i * (2.0 * Math.PI / 256.0)) * 127.0);
        }
    }

    // Demo state
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;
    private AnimationTimer fxTimer;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("VPU Demo: Blitter Scroll + XOR Fill (MODE 1) — FPS --");
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
        emuThread = new Thread(() -> runDemo(vpu, cyclesPerFrame), "VpuDemoBlitterScrollXorMode1Main-emu");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runDemo(VPU_v2 vpu, int cyclesPerFrame) {
        // MODE 1 underlay, text off, overlay text personality (irrelevant since TX disabled)
        mmio(vpu, REG_MODE, 1);
        mmio(vpu, REG_OVL_MODE, 0x00);
        mmio(vpu, REG_TX_CTRL, 0x00);
        mmio(vpu, REG_CTRL, CTRL_ENABLE);

        // Write-assist defaults
        mmio(vpu, REG_WR_PLANE_MASK, 0x0F);
        mmio(vpu, REG_BIT_MASK, 0xFF);
        mmio(vpu, REG_ROP, ROP_REPLACE);
        mmio(vpu, REG_SR_ENABLE, 0x00);
        mmio(vpu, REG_SR_COLOR, 0x00);

        // Palette + background indices
        int[] pal16 = makeBasePalette16();
        installPal16(vpu, pal16);
        fillInitialBackground(vpu);

        // Scroll state
        int xPan = 0;
        int worldByte = 0; // how many coarse bytes we've shifted in "world space"

        // XOR highlight window state
        final int rectWBytes = 8;  // 8 bytes = 64 source pixels
        final int rectHRows  = 32;
        int oldRectXB = 0;
        int oldRectY  = 0;

        long fpsT0 = System.nanoTime();
        int fpsFrames = 0;

        int frame = 0;

        while (running.get()) {
            int remaining = cyclesPerFrame;

            // --- Amiga-ish palette trick: rotate colors 1..15 (VRAM stays same indices) ---
            if ((frame & 1) == 0) {
                rotatePal16(pal16);
                // write only 1..15 (keep black stable)
                for (int i = 1; i < 16; i++) writeRgb888ToPal(vpu, i, pal16[i]);
            }

            // --- XOR-erase previous highlight window ---
            remaining -= blitXorRect(vpu, oldRectXB, oldRectY, rectWBytes, rectHRows);

            // --- Fine scroll (1 px/frame). When it wraps, do coarse scroll via blitter copy. ---
            xPan = (xPan + 1) & 7;
            mmio(vpu, REG_XPAN, xPan);

            if (xPan == 0) {
                // Coarse shift left by 1 byte (8 px):
                // copy width=39 bytes: src=1..39 -> dst=0..38 for each row
                remaining -= blitCopyShiftLeftOneByte(vpu);

                // After copy completes, inject new right column content (byte 39).
                // This must happen AFTER the blit, otherwise the blit would read modified src[39].
                worldByte++;
                writeNewRightColumn(vpu, worldByte, frame);
            }

            // --- Compute new highlight position (aligned to bytes to keep it simple) ---
            int a = (frame * 3) & 255;
            int b = (frame * 2 + 64) & 255;

            int maxXB = ROW_BYTES - rectWBytes;        // 40 - 8 = 32
            int maxY  = SRC_H - rectHRows;             // 200 - 32 = 168

            int newXB = ((SIN256[a] + 127) * maxXB) / 254;
            int newY  = ((SIN256[b] + 127) * maxY) / 254;

            // --- XOR-draw new highlight window ---
            remaining -= blitXorRect(vpu, newXB, newY, rectWBytes, rectHRows);

            oldRectXB = newXB;
            oldRectY  = newY;

            // --- Run the rest of the frame ---
            if (remaining > 0) vpu.tick(remaining);

            // --- FPS in title ---
            fpsFrames++;
            long now = System.nanoTime();
            if (now - fpsT0 >= 1_000_000_000L) {
                long dt = now - fpsT0;
                double fps = (fpsFrames * 1_000_000_000.0) / dt;
                final String title = String.format("VPU Demo: Blitter Scroll + XOR Fill (MODE 1) — FPS %.1f", fps);
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
    // BLITTER OPS
    // ---------------------------------------------------------------------

    /**
     * BLT COPY: shift whole screen left by 1 byte (8 px).
     * Mode1 row stride is 40 bytes per plane, height is 200 rows.
     */
    private static int blitCopyShiftLeftOneByte(VPU_v2 vpu) {
        // Ensure REPLACE
        mmio(vpu, REG_ROP, ROP_REPLACE);
        mmio(vpu, REG_BIT_MASK, 0xFF);
        mmio(vpu, REG_SR_ENABLE, 0x00);

        // src=1, dst=0
        writeU16(vpu, REG_BLT_SRC_L, 1);
        writeU16(vpu, REG_BLT_DST_L, 0);

        // width=39 bytes, height=200 rows
        writeU16(vpu, REG_BLT_W_L, 39);
        mmio(vpu, REG_BLT_H, 200);

        // pitch = rowStride - W = 40 - 39 = 1
        writeU16(vpu, REG_BLT_SRC_PITCH_L, 1);
        writeU16(vpu, REG_BLT_DST_PITCH_L, 1);

        // all planes
        mmio(vpu, REG_BLT_PLANE_MASK, 0x0F);

        // start (copy)
        mmio(vpu, REG_BLT_CTRL, BLT_START);

        // cycles needed: ops * 2 (BLT_CYCLES_PER_BYTE=2 in your VPU)
        int ops = 39 * 200;        // 7800
        int cycles = ops * 2;      // 15600
        vpu.tick(cycles);
        return cycles;
    }

    /**
     * BLT FILL with ROP=XOR and SR_ENABLE=all, SR_COLOR=0x0F
     * => XOR 0xFF into all planes => invert 4bpp color bits (nice "window" effect).
     */
    private static int blitXorRect(VPU_v2 vpu, int xByte, int yRow, int wBytes, int hRows) {
        // XOR mode
        mmio(vpu, REG_ROP, ROP_XOR);
        mmio(vpu, REG_BIT_MASK, 0xFF);

        // For bitplane mode: SR_ENABLE per plane makes src be 0xFF or 0x00 depending on srColor bit.
        // srColor=0x0F => all planes src=0xFF => XOR inverts bits.
        mmio(vpu, REG_SR_ENABLE, 0x0F);
        mmio(vpu, REG_SR_COLOR, 0x0F);

        int dst = (yRow * ROW_BYTES) + xByte;

        // dst only
        writeU16(vpu, REG_BLT_DST_L, dst);
        writeU16(vpu, REG_BLT_SRC_L, 0);

        writeU16(vpu, REG_BLT_W_L, wBytes);
        mmio(vpu, REG_BLT_H, hRows);

        // dst pitch = rowStride - W
        writeU16(vpu, REG_BLT_DST_PITCH_L, ROW_BYTES - wBytes);
        writeU16(vpu, REG_BLT_SRC_PITCH_L, 0);

        mmio(vpu, REG_BLT_PLANE_MASK, 0x0F);
        mmio(vpu, REG_BLT_FILL, 0x00);

        mmio(vpu, REG_BLT_CTRL, (BLT_START | BLT_FILL));

        int ops = wBytes * hRows;
        int cycles = ops * 2;
        vpu.tick(cycles);
        return cycles;
    }

    // ---------------------------------------------------------------------
    // BACKGROUND + COLUMN INJECTION
    // ---------------------------------------------------------------------

    /** One-time init: fill VRAM with a tile-ish pattern (indices 1..15). */
    private static void fillInitialBackground(VPU_v2 vpu) {
        for (int y = 0; y < SRC_H; y++) {
            int rowBase = y * ROW_BYTES;
            for (int bx = 0; bx < ROW_BYTES; bx++) {
                int x0 = bx << 3; // 8 pixels per byte
                int ofs = rowBase + bx;

                int[] idx = new int[8];
                for (int i = 0; i < 8; i++) {
                    int x = x0 + i;
                    idx[i] = bgIndex(x, y, 0, 0);
                }

                byte[] planes = packBitplanes8(idx);
                for (int p = 0; p < 4; p++) {
                    vpu.writeVramPlane(p, ofs, planes[p]);
                }
            }
        }
    }

    /** After coarse shift, write new rightmost column (byte 39) for all rows. */
    private static void writeNewRightColumn(VPU_v2 vpu, int worldByte, int frame) {
        int bx = ROW_BYTES - 1; // 39
        int x0World = (worldByte + ROW_BYTES - 1) << 3; // the new column world X in pixels
        for (int y = 0; y < SRC_H; y++) {
            int ofs = y * ROW_BYTES + bx;

            int[] idx = new int[8];
            for (int i = 0; i < 8; i++) {
                int xWorld = x0World + i;
                idx[i] = bgIndex(xWorld, y, worldByte, frame);
            }

            byte[] planes = packBitplanes8(idx);
            for (int p = 0; p < 4; p++) {
                vpu.writeVramPlane(p, ofs, planes[p]);
            }
        }
    }

    /** Background color index function (1..15 mostly), with rare "stars" as white. */
    private static int bgIndex(int x, int y, int worldByte, int frame) {
        // rare stars
        int star = (x * 17 + y * 31 + frame * 7) & 1023;
        if (star == 0) return 15;

        // tile-ish pattern
        int tx = (x >> 4);     // 16px tiles
        int ty = (y >> 3);     // 8px tiles
        int v  = (tx + ty + (y >> 5)) % 15;
        return 1 + v;          // 1..15
    }

    /** Convert 8x 4bpp indices -> 4 plane bytes (bit7=leftmost pixel). */
    private static byte[] packBitplanes8(int[] idx8) {
        int b0 = 0, b1 = 0, b2 = 0, b3 = 0;
        for (int i = 0; i < 8; i++) {
            int c = idx8[i] & 0x0F;
            int bit = 7 - i;
            if ((c & 0x01) != 0) b0 |= (1 << bit);
            if ((c & 0x02) != 0) b1 |= (1 << bit);
            if ((c & 0x04) != 0) b2 |= (1 << bit);
            if ((c & 0x08) != 0) b3 |= (1 << bit);
        }
        return new byte[] { (byte) b0, (byte) b1, (byte) b2, (byte) b3 };
    }

    // ---------------------------------------------------------------------
    // PALETTE (16 colors) + AMIGA-ISH CYCLE
    // ---------------------------------------------------------------------

    private static int[] makeBasePalette16() {
        // Start from VGA-ish base but with slightly more "demo" flavor.
        return new int[] {
                0x000000, // 0 black
                0x0B1020, // 1 deep blue
                0x142B7A, // 2 blue
                0x1F5FA8, // 3 cyan-ish
                0x2AAE9A, // 4 green-cyan
                0x2EDB57, // 5 green
                0xB7E21B, // 6 yellow-green
                0xFFE84A, // 7 yellow
                0xFFB84A, // 8 orange
                0xFF6A3A, // 9 orange-red
                0xFF2A2A, // 10 red
                0xC81B7B, // 11 magenta
                0x7A2AD6, // 12 purple
                0xB0B0B0, // 13 light gray
                0xE0E0E0, // 14 near white
                0xFFFFFF  // 15 white
        };
    }

    private static void rotatePal16(int[] pal16) {
        // Rotate indices 1..15 (leave 0 black)
        int tmp = pal16[15];
        for (int i = 15; i >= 2; i--) pal16[i] = pal16[i - 1];
        pal16[1] = tmp;
    }

    private static void installPal16(VPU_v2 vpu, int[] pal16) {
        for (int i = 0; i < 16; i++) writeRgb888ToPal(vpu, i, pal16[i]);
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

    // ---------------------------------------------------------------------
    // MMIO HELPERS
    // ---------------------------------------------------------------------

    private static void mmio(VPU_v2 vpu, int reg, int value8) {
        vpu.writeMmio(reg, (byte) (value8 & 0xFF));
    }

    private static void writeU16(VPU_v2 vpu, int regLo, int value16) {
        vpu.writeMmio(regLo,     (byte) (value16 & 0xFF));
        vpu.writeMmio(regLo + 1, (byte) ((value16 >>> 8) & 0xFF));
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