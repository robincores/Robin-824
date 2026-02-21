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
 * VPU showcase demo: loops through modes 0..4 and highlights "best bits":
 *  - MODE 0: 640x200 bitplanes (16 colors) + smooth xPan (0..7)
 *  - MODE 1: 320x200 bitplanes (16 colors) + smooth xPan (0..7)
 *  - MODE 2: 320x200 Mode-X-ish 8bpp (256 colors) + smooth xPan (0..3) + plasma
 *  - MODE 3: 160x200 Mode-X-ish 8bpp (256 colors) + page flip via FB_BASE
 *  - MODE 4: Text-only 80x25 + TX_FINE_Y + TX_FINE_X + copper bars (palette[1] gradient)
 *
 * Run with optional profiling:
 *   -Dvpu.prof=true
 */
public final class VpuDemoShowcaseMain extends Application {

    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // --- MMIO offsets (must match VPU) ---
    private static final int REG_CTRL        = 0x0000;
    private static final int REG_MODE        = 0x0002;
    private static final int REG_FB_BASE_L   = 0x0005;
    private static final int REG_FB_BASE_H   = 0x0006;
    private static final int REG_TX_CTRL     = 0x0007;
    private static final int REG_TX_CUR_X    = 0x0008;
    private static final int REG_TX_CUR_Y    = 0x0009;
    private static final int REG_XPAN        = 0x000C;

    private static final int REG_TX_ORIGIN_L = 0x0013;
    private static final int REG_TX_ORIGIN_H = 0x0014;
    private static final int REG_TX_FINE_Y   = 0x0015;
    private static final int REG_COP_CTRL    = 0x0016;
    private static final int REG_COP_LEN_L   = 0x0017;
    private static final int REG_COP_LEN_H   = 0x0018;

    private static final int REG_TX_CMD      = 0x0019;
    private static final int REG_TX_ATTR     = 0x001A;
    private static final int REG_TX_PORT     = 0x001B;
    private static final int REG_TX_FINE_X   = 0x001E;

    // Palette RAM
    private static final int PAL_BASE        = 0x0100;

    // Copper RAM
    private static final int COPPER_BASE     = 0x2400;
    private static final int COPPER_STRIDE   = 8;

    // CTRL bits
    private static final int CTRL_ENABLE     = 0x01;

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_TRANSPARENT_BG = 0x04;
    private static final int TX_CURSOR_BLINK   = 0x08;

    // TX_CMD bits
    private static final int TXCMD_CLR_SCREEN  = 0x04;
    private static final int TXCMD_SCROLL_UP   = 0x08;

    // Copper bits
    private static final int COP_CTRL_EN       = 0x01;
    private static final int COP_FLAG_WRITE16  = 0x01;
    private static final int COP_FLAG_END      = 0x80;

    // Simple 0..255 sine table for plasma (period 1024)
    private static final int SIN_N = 1024;
    private static final int[] SIN8 = new int[SIN_N];
    static {
        for (int i = 0; i < SIN_N; i++) {
            double a = (i * 2.0 * Math.PI) / SIN_N;
            SIN8[i] = (int) Math.round(127.5 * (1.0 + Math.sin(a))); // 0..255
        }
    }

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread emuThread;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Canvas canvas = new Canvas(DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        StackPane root = new StackPane(canvas);

        Scene scene = new Scene(root, DISPLAY_CONFIG.canvasWidth(), DISPLAY_CONFIG.canvasHeight());
        stage.setTitle("R816 VPU Showcase (modes 0..4 loop)");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.show();

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        VPU vpu = new VPU(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);

        // Ensure enabled.
        vpu.writeMmio(REG_CTRL, (byte) CTRL_ENABLE);

        final int cyclesPerFrame =
                DISPLAY_CONFIG.cyclesPerScanline() * DISPLAY_CONFIG.scanlinesPerFrame();

        emuThread = new Thread(() -> runShowcase(vpu, cyclesPerFrame), "vpu-showcase");
        emuThread.setDaemon(true);
        emuThread.start();

        stage.setOnCloseRequest(e -> running.set(false));
    }

    private void runShowcase(VPU vpu, int cyclesPerFrame) {
        final int sceneFrames = 240; // ~3.4s at 70Hz
        int scene = 0;

        while (running.get()) {
            try {
                switch (scene) {
                    case 0 -> runMode0(vpu, cyclesPerFrame, sceneFrames);
                    case 1 -> runMode1(vpu, cyclesPerFrame, sceneFrames);
                    case 2 -> runMode2(vpu, cyclesPerFrame, sceneFrames);
                    case 3 -> runMode3(vpu, cyclesPerFrame, sceneFrames);
                    default -> runMode4TextCopper(vpu, cyclesPerFrame, sceneFrames);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                break;
            }
            scene = (scene + 1) % 5;
        }

        Platform.runLater(() -> {
            if (stage != null) stage.close();
        });
    }

    // ---------------------------------------------------------------------
    // Scene helpers
    // ---------------------------------------------------------------------

    private void commonDisableCopperAndResetText(VPU vpu) {
        // Copper off
        vpu.writeMmio(REG_COP_CTRL, (byte) 0);
        vpu.writeMmio(REG_COP_LEN_L, (byte) 0);
        vpu.writeMmio(REG_COP_LEN_H, (byte) 0);

        // Reset text scroll
        vpu.writeMmio(REG_TX_ORIGIN_L, (byte) 0);
        vpu.writeMmio(REG_TX_ORIGIN_H, (byte) 0);
        vpu.writeMmio(REG_TX_FINE_Y, (byte) 0);
        vpu.writeMmio(REG_TX_FINE_X, (byte) 0);

        // Clear screen
        vpu.writeMmio(REG_TX_ATTR, (byte) 0x0F);      // bg=0 fg=15
        vpu.writeMmio(REG_TX_CMD, (byte) TXCMD_CLR_SCREEN);
    }

    private void enableOverlay(VPU vpu, boolean transparentBg) {
        int v = TX_EN;
        if (transparentBg) v |= TX_TRANSPARENT_BG;
        // cursor off in showcase to avoid distracting blink on graphics modes
        vpu.writeMmio(REG_TX_CTRL, (byte) v);
    }

    private void writeLine(VPU vpu, int row, String s, int attr) {
        vpu.writeMmio(REG_TX_ATTR, (byte) (attr & 0xFF));
        vpu.writeMmio(REG_TX_CUR_X, (byte) 0);
        vpu.writeMmio(REG_TX_CUR_Y, (byte) row);

        int len = Math.min(80, s.length());
        for (int i = 0; i < len; i++) {
            vpu.writeMmio(REG_TX_PORT, (byte) (s.charAt(i) & 0xFF));
        }
        // pad to EOL (no newline)
        for (int i = len; i < 80; i++) {
            vpu.writeMmio(REG_TX_PORT, (byte) ' ');
        }
        // restore cursor to top-left (harmless)
        vpu.writeMmio(REG_TX_CUR_X, (byte) 0);
        vpu.writeMmio(REG_TX_CUR_Y, (byte) 0);
    }

    private static void tickOneFrameExact(VPU vpu, int cyclesPerFrame) {
        int done = 0;
        final int chunk = 5_000;
        while (done < cyclesPerFrame) {
            int step = Math.min(chunk, cyclesPerFrame - done);
            vpu.tick(step);
            done += step;
        }
    }

    private void sleepFrame() {
        try {
            Thread.sleep(14);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loadHuePalette(VPU vpu) {
        // Cyclic hue palette (0 == 255 visually), good for modulo effects with no seams.
        for (int i = 0; i < 256; i++) {
            float h = (i / 256.0f); // 0..1
            int rgb = hsvToRgb(h, 1.0f, 1.0f);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = (rgb) & 0xFF;
            int rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >> 3);

            int o = PAL_BASE + (i << 1);
            vpu.writeMmio(o, (byte) (rgb565 & 0xFF));
            vpu.writeMmio(o + 1, (byte) ((rgb565 >>> 8) & 0xFF));
        }
    }

    // ---------------------------------------------------------------------
    // MODE 0: 640x200 bitplanes (16 colors) -> 640x400
    // ---------------------------------------------------------------------
    private void runMode0(VPU vpu, int cyclesPerFrame, int frames) {
        commonDisableCopperAndResetText(vpu);

        vpu.writeMmio(REG_MODE, (byte) 0);
        vpu.writeMmio(REG_FB_BASE_L, (byte) 0);
        vpu.writeMmio(REG_FB_BASE_H, (byte) 0);

        enableOverlay(vpu, true);

        writeLine(vpu, 0, "MODE 0  640x200 4bpp bitplanes  -> 640x400 (double-scan Y)   smooth xPan 0..7", 0x0F);
        writeLine(vpu, 1, "Best for: retro 16-color pixel art, tilemaps, cheap memory bandwidth.", 0x0E);
        writeLine(vpu, 2, "DISPLAY: " + DISPLAY_CONFIG.width() + "x" + DISPLAY_CONFIG.height() +
                "  cycles/scanline=" + DISPLAY_CONFIG.cyclesPerScanline() +
                "  scanlines/frame=" + DISPLAY_CONFIG.scanlinesPerFrame(), 0x07);

        // Fill VRAM with a repeating 0..7 ramp (even rows) and 8..15 ramp (odd rows).
        // Pattern across 8 pixels: [0,1,2,3,4,5,6,7]
        final int p0 = 0x55; // 01010101 (LSB plane)
        final int p1 = 0x33; // 00110011
        final int p2 = 0x0F; // 00001111
        final int bpl = 80;
        for (int y = 0; y < 200; y++) {
            int p3 = (y & 1) == 0 ? 0x00 : 0xFF; // high bit -> 8..15
            int lineBase = y * bpl;
            for (int bx = 0; bx < bpl; bx++) {
                int ofs = lineBase + bx;
                vpu.writeVramPlane(0, ofs, (byte) p0);
                vpu.writeVramPlane(1, ofs, (byte) p1);
                vpu.writeVramPlane(2, ofs, (byte) p2);
                vpu.writeVramPlane(3, ofs, (byte) p3);
            }
        }

        for (int f = 0; f < frames && running.get(); f++) {
            vpu.writeMmio(REG_XPAN, (byte) (f & 7));
            tickOneFrameExact(vpu, cyclesPerFrame);
            sleepFrame();
        }
    }

    // ---------------------------------------------------------------------
    // MODE 1: 320x200 bitplanes (16 colors) -> 640x400
    // ---------------------------------------------------------------------
    private void runMode1(VPU vpu, int cyclesPerFrame, int frames) {
        commonDisableCopperAndResetText(vpu);

        vpu.writeMmio(REG_MODE, (byte) 1);
        vpu.writeMmio(REG_FB_BASE_L, (byte) 0);
        vpu.writeMmio(REG_FB_BASE_H, (byte) 0);

        enableOverlay(vpu, true);

        writeLine(vpu, 0, "MODE 1  320x200 4bpp bitplanes  -> 640x400 (2xX + double-scan)   smooth xPan 0..7", 0x0F);
        writeLine(vpu, 1, "Best for: 16-color games with chunky pixels (planar like VGA, but retro-friendly).", 0x0E);
        writeLine(vpu, 2, "TIP: xPan shows real sub-byte scroll (fine shift carry between bytes).", 0x07);

        final int p0 = 0x55;
        final int p1 = 0x33;
        final int p2 = 0x0F;
        final int bpl = 40;
        for (int y = 0; y < 200; y++) {
            int p3 = (y & 1) == 0 ? 0x00 : 0xFF;
            int lineBase = y * bpl;
            for (int bx = 0; bx < bpl; bx++) {
                int ofs = lineBase + bx;
                vpu.writeVramPlane(0, ofs, (byte) p0);
                vpu.writeVramPlane(1, ofs, (byte) p1);
                vpu.writeVramPlane(2, ofs, (byte) p2);
                vpu.writeVramPlane(3, ofs, (byte) p3);
            }
        }

        for (int f = 0; f < frames && running.get(); f++) {
            vpu.writeMmio(REG_XPAN, (byte) ((f + 2) & 7));
            tickOneFrameExact(vpu, cyclesPerFrame);
            sleepFrame();
        }
    }

    // ---------------------------------------------------------------------
    // MODE 2: 320x200 8bpp Mode-X-ish -> 640x400
    // ---------------------------------------------------------------------
    private void runMode2(VPU vpu, int cyclesPerFrame, int frames) {
        commonDisableCopperAndResetText(vpu);

        vpu.writeMmio(REG_MODE, (byte) 2);
        vpu.writeMmio(REG_FB_BASE_L, (byte) 0);
        vpu.writeMmio(REG_FB_BASE_H, (byte) 0);

        // Hue palette makes modulo-wrapping seamless.
        loadHuePalette(vpu);

        enableOverlay(vpu, true);

        writeLine(vpu, 0, "MODE 2  320x200 8bpp Mode-X-ish  -> 640x400 (2xX + double-scan)   xPan 0..3", 0x0F);
        writeLine(vpu, 1, "Best for: 256-color demos (plasma), smooth gradients, simple chunky sprites.", 0x0E);
        writeLine(vpu, 2, "Plasma updates VRAM once per frame (no tearing): tick is EXACT cyclesPerFrame.", 0x07);

        final int srcW = 320;
        final int srcH = 200;
        final int bpl = srcW / 4; // 80

        for (int f = 0; f < frames && running.get(); f++) {
            int t = f * 6;
            int xOff = (f >> 3) & 3;
            vpu.writeMmio(REG_XPAN, (byte) xOff);

            // Update full frame BEFORE ticking (keeps tear-free output in this demo).
            for (int y = 0; y < srcH; y++) {
                int lineBase = y * bpl;
                int ty = (y * 9 + t) & (SIN_N - 1);

                for (int bx = 0; bx < bpl; bx++) {
                    int x0 = bx << 2;

                    int idx0 = plasmaIndex(x0 + 0, ty, t);
                    int idx1 = plasmaIndex(x0 + 1, ty, t);
                    int idx2 = plasmaIndex(x0 + 2, ty, t);
                    int idx3 = plasmaIndex(x0 + 3, ty, t);

                    int ofs = lineBase + bx;
                    vpu.writeVramPlane(0, ofs, (byte) idx0);
                    vpu.writeVramPlane(1, ofs, (byte) idx1);
                    vpu.writeVramPlane(2, ofs, (byte) idx2);
                    vpu.writeVramPlane(3, ofs, (byte) idx3);
                }
            }

            tickOneFrameExact(vpu, cyclesPerFrame);
            sleepFrame();
        }
    }

    private int plasmaIndex(int x, int ty, int t) {
        // Periodic in both x and y => no seams.
        int a = SIN8[(x * 7 + t) & (SIN_N - 1)];
        int b = SIN8[(ty + (t << 1)) & (SIN_N - 1)];
        int c = SIN8[((x * 3) + ty + (t * 3)) & (SIN_N - 1)];
        return (a + b + c) / 3;
    }

    // ---------------------------------------------------------------------
    // MODE 3: 160x200 8bpp Mode-X-ish -> 640x400 (page flip)
    // ---------------------------------------------------------------------
    private void runMode3(VPU vpu, int cyclesPerFrame, int frames) {
        commonDisableCopperAndResetText(vpu);

        vpu.writeMmio(REG_MODE, (byte) 3);

        // Hue palette again.
        loadHuePalette(vpu);

        enableOverlay(vpu, true);

        writeLine(vpu, 0, "MODE 3  160x200 8bpp Mode-X-ish  -> 640x400 (4xX + double-scan)   page flip via FB_BASE", 0x0F);
        writeLine(vpu, 1, "Best for: page-flipped demos (double-buffer fits: 2 * 8000 bytes/plane < 16K).", 0x0E);
        writeLine(vpu, 2, "FB_BASE toggles 0x0000 <-> 0x1F40. xPan 0..3 also works here.", 0x07);

        final int srcW = 160;
        final int srcH = 200;
        final int bpl = srcW / 4;       // 40
        final int pageSize = bpl * srcH; // 8000 = 0x1F40

        for (int f = 0; f < frames && running.get(); f++) {
            int t = f * 7;
            int pageBase = ((f & 1) == 0) ? 0 : pageSize;

            int xOff = (f >> 3) & 3;
            vpu.writeMmio(REG_XPAN, (byte) xOff);

            // Draw into the back page, then flip FB_BASE to it.
            for (int y = 0; y < srcH; y++) {
                int lineBase = pageBase + y * bpl;
                int ty = (y * 11 + t) & (SIN_N - 1);

                for (int bx = 0; bx < bpl; bx++) {
                    int x0 = bx << 2;

                    int idx0 = plasmaIndex(x0 + 0, ty, t);
                    int idx1 = plasmaIndex(x0 + 1, ty, t);
                    int idx2 = plasmaIndex(x0 + 2, ty, t);
                    int idx3 = plasmaIndex(x0 + 3, ty, t);

                    int ofs = lineBase + bx;
                    vpu.writeVramPlane(0, ofs, (byte) idx0);
                    vpu.writeVramPlane(1, ofs, (byte) idx1);
                    vpu.writeVramPlane(2, ofs, (byte) idx2);
                    vpu.writeVramPlane(3, ofs, (byte) idx3);
                }
            }

            // Flip
            vpu.writeMmio(REG_FB_BASE_L, (byte) (pageBase & 0xFF));
            vpu.writeMmio(REG_FB_BASE_H, (byte) ((pageBase >>> 8) & 0xFF));

            tickOneFrameExact(vpu, cyclesPerFrame);
            sleepFrame();
        }
    }

    // ---------------------------------------------------------------------
    // MODE 4: Text-only + copper bars + smooth TX_FINE_Y + TX_FINE_X
    // ---------------------------------------------------------------------
    private void runMode4TextCopper(VPU vpu, int cyclesPerFrame, int frames) {
        commonDisableCopperAndResetText(vpu);

        vpu.writeMmio(REG_MODE, (byte) 4);

        // Text-only: opaque bg so we can "see" copper palette[1] as the paper behind the text.
        vpu.writeMmio(REG_TX_CTRL, (byte) (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK));

        // Background uses palette[1], foreground palette[15]
        vpu.writeMmio(REG_TX_ATTR, (byte) 0x1F); // bg=1 fg=15
        vpu.writeMmio(REG_TX_CMD, (byte) TXCMD_CLR_SCREEN);

        // Copper bars: palette[1] becomes a vertical gradient every scanline.
        buildCopperGradient(vpu, /*paletteIndex*/1);

        // Enable copper. LEN=0 => scan until END marker.
        vpu.writeMmio(REG_COP_LEN_L, (byte) 0x00);
        vpu.writeMmio(REG_COP_LEN_H, (byte) 0x00);
        vpu.writeMmio(REG_COP_CTRL, (byte) COP_CTRL_EN);

        writeLine(vpu, 0, "MODE 4  TEXT 80x25 (8x16) -> 640x400   TX_FINE_Y + TX_FINE_X + COPPER palette bars", 0x1F);
        writeLine(vpu, 1, "TX_PORT writes, TX_CMD scrolls (ring buffer): no memcopy, very BASIC-friendly.", 0x1E);
        writeLine(vpu, 2, "Copper updates palette[1] per scanline: classic raster bars without CPU cost.", 0x1B);

        int fineY = 0;
        int lineNo = 0;

        for (int f = 0; f < frames && running.get(); f++) {
            // Smooth vertical + horizontal text scroll
            vpu.writeMmio(REG_TX_FINE_Y, (byte) fineY);
            vpu.writeMmio(REG_TX_FINE_X, (byte) ((f >> 2) & 7));
            fineY = (fineY + 1) & 0x0F;

            if (fineY == 0) {
                // Scroll one row up and write a new bottom line (no implicit newline/scroll)
                vpu.writeMmio(REG_TX_CMD, (byte) TXCMD_SCROLL_UP);

                vpu.writeMmio(REG_TX_CUR_X, (byte) 0);
                vpu.writeMmio(REG_TX_CUR_Y, (byte) 24);

                String s = String.format("LINE %05d  TX_PORT-only write  |  Copper bars  |  @mdg", lineNo++);
                int len = Math.min(80, s.length());
                for (int i = 0; i < len; i++) vpu.writeMmio(REG_TX_PORT, (byte) (s.charAt(i) & 0xFF));
                for (int i = len; i < 80; i++) vpu.writeMmio(REG_TX_PORT, (byte) ' ');
            }

            tickOneFrameExact(vpu, cyclesPerFrame);
            sleepFrame();
        }

        // Copper off so next scenes don't inherit it.
        vpu.writeMmio(REG_COP_CTRL, (byte) 0);
    }

    private void buildCopperGradient(VPU vpu, int palIndex) {
        // Writes palette[palIndex] (RGB565) each scanline.
        final int palAddr = PAL_BASE + (palIndex << 1); // low byte of entry
        final int H = DISPLAY_CONFIG.height();

        for (int y = 0; y < H; y++) {
            // Simple smooth-ish gradient in RGB565 (not HSV) for "bars"
            int r5 = (y * 31) / (H - 1);
            int g6 = ((H - 1 - y) * 63) / (H - 1);
            int b5 = ((y * 17) / (H - 1)) & 31;

            int rgb565 = (r5 << 11) | (g6 << 5) | b5;
            int lo = rgb565 & 0xFF;
            int hi = (rgb565 >>> 8) & 0xFF;

            int pos = COPPER_BASE + y * COPPER_STRIDE;

            // scanline
            vpu.writeMmio(pos + 0, (byte) (y & 0xFF));
            vpu.writeMmio(pos + 1, (byte) ((y >>> 8) & 0xFF));

            // reg (palette entry address)
            vpu.writeMmio(pos + 2, (byte) (palAddr & 0xFF));
            vpu.writeMmio(pos + 3, (byte) ((palAddr >>> 8) & 0xFF));

            // value
            vpu.writeMmio(pos + 4, (byte) lo);
            vpu.writeMmio(pos + 5, (byte) hi);

            // flags + pad
            vpu.writeMmio(pos + 6, (byte) COP_FLAG_WRITE16);
            vpu.writeMmio(pos + 7, (byte) 0);
        }

        // END marker
        int endPos = COPPER_BASE + H * COPPER_STRIDE;
        vpu.writeMmio(endPos + 0, (byte) 0xFF);
        vpu.writeMmio(endPos + 1, (byte) 0xFF);
        vpu.writeMmio(endPos + 2, (byte) 0xFF);
        vpu.writeMmio(endPos + 3, (byte) 0xFF);
        vpu.writeMmio(endPos + 4, (byte) 0);
        vpu.writeMmio(endPos + 5, (byte) 0);
        vpu.writeMmio(endPos + 6, (byte) COP_FLAG_END);
        vpu.writeMmio(endPos + 7, (byte) 0);
    }

    private static int hsvToRgb(float h, float s, float v) {
        // returns 0xRRGGBB
        float r, g, b;

        int i = (int) Math.floor(h * 6.0f);
        float f = (h * 6.0f) - i;
        float p = v * (1.0f - s);
        float q = v * (1.0f - f * s);
        float t = v * (1.0f - (1.0f - f) * s);

        switch (i & 5) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }

        int R = (int) (r * 255.0f) & 0xFF;
        int G = (int) (g * 255.0f) & 0xFF;
        int B = (int) (b * 255.0f) & 0xFF;
        return (R << 16) | (G << 8) | B;
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
