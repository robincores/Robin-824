package io.github.robincores.r8.device;

import io.github.robincores.r8.system.InterruptSink;
import io.github.robincores.r8.system.Tickable;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPU (v1) — VGA-ish planar video with CLUT (RGB565).
 *
 * <p>Physical VRAM is 64K, organized as 4 planes × 16K. The CPU accesses VRAM via
 * a 16K window at {@code 0xC000–0xFFFF} (see {@link VideoWindow}) where:
 * <ul>
 *   <li>{@link SysMMIO#vbank()} selects the currently visible VRAM plane</li>
 *   <li>{@link SysMMIO#winMmio()} switches the window into VPU MMIO view</li>
 * </ul>
 *
 * <p><b>Important:</b> Planes can be interpreted in two ways depending on MODE:
 * <ul>
 *   <li><b>Bitplane modes</b>: each plane contributes 1 bit per pixel (4bpp / 16 colors).</li>
 *   <li><b>Mode-X byte-plane modes</b>: each plane stores bytes for pixels where {@code x mod 4 == plane}
 *       (8bpp / 256 colors, VGA Mode X style).</li>
 * </ul>
 * </p>
 *
 * <h3>Supported video modes (v1)</h3>
 * <ul>
 *   <li>MODE 0: 640×200 @ 4bpp <i>bitplane</i>, displayed as 640×400 (double-scan Y)</li>
 *   <li>MODE 1: 320×200 @ 4bpp <i>bitplane</i>, displayed as 640×400 (2×X + double-scan Y)</li>
 *   <li>MODE 2: 320×200 @ 8bpp <i>Mode X byte-plane</i>, displayed as 640×400 (2×X + double-scan Y)</li>
 *   <li>MODE 3: 160×200 @ 8bpp <i>Mode X byte-plane</i>, displayed as 640×400 (4×X + double-scan Y)
 *       — fits <b>double buffering</b> by changing {@code FB_BASE}.</li>
 * </ul>
 *
 * <h3>Timing model</h3>
 * <p>Modeled after VGA-ish 640×400 @ ~70Hz using 449 total scanlines and vblank
 * starting at line 400. With CPU = 12.5MHz and cyclesPerScanline = 400,
 * refresh ≈ 12.5e6 / (400*449) ≈ 69.6Hz.</p>
 */
public final class VPU implements Tickable {

    // -------------------- Memory layout --------------------
    public static final int VRAM_SIZE = 0x10000;   // 64K total
    public static final int PLANE_SIZE = 0x4000;   // 16K per plane
    public static final int NUM_PLANES = 4;

    // -------------------- MMIO (within the 16K window when WIN_MMIO=1) --------------------
    // Registers
    private static final int REG_CTRL     = 0x0000; // bit0 enable, bit1 vblank IRQ enable
    private static final int REG_STATUS   = 0x0001; // bit0 in-vblank, bit1 frame-ready (W1C)
    private static final int REG_MODE     = 0x0002; // 0..3 (see supported modes)
    private static final int REG_SCAN_L   = 0x0003; // RO
    private static final int REG_SCAN_H   = 0x0004; // RO
    private static final int REG_FB_BASE_L = 0x0005; // base offset within each plane (low)
    private static final int REG_FB_BASE_H = 0x0006; // base offset within each plane (high)

    // Palette RAM (256 × RGB565 little-endian)
    private static final int PAL_BASE     = 0x0100;
    private static final int PAL_SIZE     = 0x0200; // 512 bytes

    // CTRL bits
    private static final int CTRL_ENABLE     = 0x01;
    private static final int CTRL_VBLANK_IRQ = 0x02;

    // STATUS bits
    private static final int STATUS_VBLANK = 0x01;
    private static final int STATUS_FRAME  = 0x02;

    // -------------------- Config / wiring --------------------
    private final DisplayConfig config;
    private final InterruptSink sink;
    private final int irqBit;
    private final Canvas canvas;

    // -------------------- State --------------------
    private final byte[] vram = new byte[VRAM_SIZE];

    private final short[] pal565 = new short[256];
    private final int[] palArgb = new int[256];

    private int ctrl;
    private int status;
    private int mode;
    private int fbBase;
    private int scanline;
    private int cycleAccum;

    // -------------------- Rendering --------------------
    private final WritableImage image;
    private final PixelWriter writer;

    // Double-buffer pixel arrays so FX thread never reads a buffer we are writing.
    private final Object fbLock = new Object();
    private int[] renderBuf;
    private int[] displayBuf;

    private final AtomicBoolean blitPending = new AtomicBoolean(false);

    public VPU(DisplayConfig config, InterruptSink sink, int irqBit, Canvas canvas) {
        this.config = config;
        this.sink = sink;
        this.irqBit = irqBit;
        this.canvas = canvas;

        canvas.setWidth(config.canvasWidth());
        canvas.setHeight(config.canvasHeight());

        this.image = new WritableImage(config.width(), config.height());
        this.writer = image.getPixelWriter();

        this.renderBuf = new int[config.width() * config.height()];
        this.displayBuf = new int[config.width() * config.height()];

        initDefaultPaletteRgb565();

        // Power-on defaults: display enabled, mode 0.
        this.ctrl = CTRL_ENABLE;
        this.mode = 0;
        this.fbBase = 0;
    }

    // =====================================================================
    // VRAM plane access (used by VideoWindow)
    // =====================================================================

    public byte readVramPlane(int plane, int offset) {
        int p = plane & 0x03;
        int ofs = offset & (PLANE_SIZE - 1);
        return vram[(p * PLANE_SIZE) + ofs];
    }

    public void writeVramPlane(int plane, int offset, byte value) {
        int p = plane & 0x03;
        int ofs = offset & (PLANE_SIZE - 1);
        vram[(p * PLANE_SIZE) + ofs] = value;
    }

    // =====================================================================
    // MMIO view (used by VideoWindow)
    // =====================================================================

    public byte readMmio(int offset) {
        int o = offset & 0x3FFF;

        // Palette RAM
        if (o >= PAL_BASE && o < (PAL_BASE + PAL_SIZE)) {
            int idx = (o - PAL_BASE) >> 1;
            boolean hi = ((o - PAL_BASE) & 1) != 0;
            int v = pal565[idx] & 0xFFFF;
            return (byte) (hi ? ((v >> 8) & 0xFF) : (v & 0xFF));
        }

        return (byte) switch (o) {
            case REG_CTRL   -> ctrl;
            case REG_STATUS -> status;
            case REG_MODE   -> mode;
            case REG_SCAN_L -> (scanline & 0xFF);
            case REG_SCAN_H -> ((scanline >> 8) & 0xFF);
            case REG_FB_BASE_L -> (fbBase & 0xFF);
            case REG_FB_BASE_H -> ((fbBase >> 8) & 0xFF);
            default -> 0;
        };
    }

    public void writeMmio(int offset, byte value) {
        int o = offset & 0x3FFF;
        int v = Byte.toUnsignedInt(value);

        // Palette RAM
        if (o >= PAL_BASE && o < (PAL_BASE + PAL_SIZE)) {
            int idx = (o - PAL_BASE) >> 1;
            boolean hi = ((o - PAL_BASE) & 1) != 0;
            int cur = pal565[idx] & 0xFFFF;
            int next = hi ? ((cur & 0x00FF) | (v << 8)) : ((cur & 0xFF00) | v);
            pal565[idx] = (short) next;
            palArgb[idx] = rgb565ToArgb(next);
            return;
        }

        switch (o) {
            case REG_CTRL -> ctrl = v;
            case REG_STATUS -> status &= ~v; // W1C
            case REG_MODE -> mode = (v & 0x03);
            case REG_FB_BASE_L -> fbBase = (fbBase & 0xFF00) | v;
            case REG_FB_BASE_H -> fbBase = (fbBase & 0x00FF) | (v << 8);
            default -> {
                // ignored
            }
        }
    }

    // =====================================================================
    // Tick / timing
    // =====================================================================

    @Override
    public void tick(int cycles) {
        if ((ctrl & CTRL_ENABLE) == 0) return;

        cycleAccum += cycles;

        while (cycleAccum >= config.cyclesPerScanline()) {
            cycleAccum -= config.cyclesPerScanline();
            scanline++;

            if (scanline == config.vblankStart()) {
                // Enter vblank: finish frame
                status |= STATUS_VBLANK | STATUS_FRAME;
                renderFrameIntoBackBuffer();
                requestBlit();

                if ((ctrl & CTRL_VBLANK_IRQ) != 0) {
                    sink.raise(irqBit);
                }
            }

            if (scanline >= config.scanlinesPerFrame()) {
                scanline = 0;
                status &= ~STATUS_VBLANK;
            }
        }
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    private void renderFrameIntoBackBuffer() {
        final int W = 640;
        final int H = 400;
        final int srcH = 200;

        if (mode == 0) {
            // 640x200 -> 640x400 (double-scan Y)
            final int bpl = 80; // bytes per line per plane
            for (int y = 0; y < srcH; y++) {
                int outY0 = y << 1;
                int outY1 = outY0 + 1;
                int row0 = outY0 * W;
                int row1 = outY1 * W;

                int base = y * bpl;

                for (int bx = 0; bx < bpl; bx++) {
                    int ofs = base + bx;

                    int p0 = vram[(0 * PLANE_SIZE) + ofs] & 0xFF;
                    int p1 = vram[(1 * PLANE_SIZE) + ofs] & 0xFF;
                    int p2 = vram[(2 * PLANE_SIZE) + ofs] & 0xFF;
                    int p3 = vram[(3 * PLANE_SIZE) + ofs] & 0xFF;

                    int xBase = bx * 8;
                    for (int bit = 7; bit >= 0; bit--) {
                        int x = xBase + (7 - bit);
                        int idx = ((p0 >> bit) & 1)
                                | (((p1 >> bit) & 1) << 1)
                                | (((p2 >> bit) & 1) << 2)
                                | (((p3 >> bit) & 1) << 3);
                        int argb = palArgb[idx];
                        renderBuf[row0 + x] = argb;
                        renderBuf[row1 + x] = argb;
                    }
                }
            }
        } else if (mode == 1) {
            // 320x200 -> 640x400 (2xX + double-scan Y)
            final int bpl = 40;
            for (int y = 0; y < srcH; y++) {
                int outY0 = y << 1;
                int outY1 = outY0 + 1;
                int row0 = outY0 * W;
                int row1 = outY1 * W;

                int base = y * bpl;

                for (int bx = 0; bx < bpl; bx++) {
                    int ofs = base + bx;

                    int p0 = vram[(0 * PLANE_SIZE) + ofs] & 0xFF;
                    int p1 = vram[(1 * PLANE_SIZE) + ofs] & 0xFF;
                    int p2 = vram[(2 * PLANE_SIZE) + ofs] & 0xFF;
                    int p3 = vram[(3 * PLANE_SIZE) + ofs] & 0xFF;

                    int srcXBase = bx * 8;
                    for (int bit = 7; bit >= 0; bit--) {
                        int srcX = srcXBase + (7 - bit);
                        int outX0 = srcX << 1;
                        int outX1 = outX0 + 1;

                        int idx = ((p0 >> bit) & 1)
                                | (((p1 >> bit) & 1) << 1)
                                | (((p2 >> bit) & 1) << 2)
                                | (((p3 >> bit) & 1) << 3);
                        int argb = palArgb[idx];

                        renderBuf[row0 + outX0] = argb;
                        renderBuf[row0 + outX1] = argb;
                        renderBuf[row1 + outX0] = argb;
                        renderBuf[row1 + outX1] = argb;
                    }
                }
            }
        } else if (mode == 2) {
            // MODE X style: 320x200, 8bpp across 4 byte-planes (x mod 4 selects plane)
            // Displayed as 640x400 (2xX + double-scan Y)
            final int srcW = 320;
            final int bpl = srcW / 4; // 80 bytes per line per plane
            final int base = fbBase & 0x3FFF;

            for (int y = 0; y < srcH; y++) {
                int outY0 = y << 1;
                int outY1 = outY0 + 1;
                int row0 = outY0 * W;
                int row1 = outY1 * W;

                int lineBase = base + (y * bpl);

                for (int x = 0; x < srcW; x++) {
                    int plane = x & 3;
                    int ofs = lineBase + (x >> 2);
                    int idx = vram[(plane * PLANE_SIZE) + (ofs & 0x3FFF)] & 0xFF;
                    int argb = palArgb[idx];

                    int outX0 = x << 1;
                    int outX1 = outX0 + 1;
                    renderBuf[row0 + outX0] = argb;
                    renderBuf[row0 + outX1] = argb;
                    renderBuf[row1 + outX0] = argb;
                    renderBuf[row1 + outX1] = argb;
                }
            }
        } else {
            // MODE 3: Mode X style 160x200, 8bpp across 4 byte-planes.
            // Displayed as 640x400 (4xX + double-scan Y). Fits double buffering via FB_BASE.
            final int srcW = 160;
            final int bpl = srcW / 4; // 40 bytes per line per plane
            final int base = fbBase & 0x3FFF;

            for (int y = 0; y < srcH; y++) {
                int outY0 = y << 1;
                int outY1 = outY0 + 1;
                int row0 = outY0 * W;
                int row1 = outY1 * W;

                int lineBase = base + (y * bpl);

                for (int x = 0; x < srcW; x++) {
                    int plane = x & 3;
                    int ofs = lineBase + (x >> 2);
                    int idx = vram[(plane * PLANE_SIZE) + (ofs & 0x3FFF)] & 0xFF;
                    int argb = palArgb[idx];

                    int outX = x << 2; // *4
                    // Write a 4x2 block
                    renderBuf[row0 + outX] = argb;
                    renderBuf[row0 + outX + 1] = argb;
                    renderBuf[row0 + outX + 2] = argb;
                    renderBuf[row0 + outX + 3] = argb;
                    renderBuf[row1 + outX] = argb;
                    renderBuf[row1 + outX + 1] = argb;
                    renderBuf[row1 + outX + 2] = argb;
                    renderBuf[row1 + outX + 3] = argb;
                }
            }
        }

        // Swap render/display buffers
        synchronized (fbLock) {
            if (!blitPending.get()) {
                int[] tmp = displayBuf;
                displayBuf = renderBuf;
                renderBuf = tmp;
            }
        }
    }

    private void requestBlit() {
        if (!blitPending.compareAndSet(false, true)) return;

        Platform.runLater(() -> {
            try {
                int[] pixels;
                synchronized (fbLock) {
                    pixels = displayBuf;
                }

                writer.setPixels(
                        0, 0,
                        config.width(), config.height(),
                        PixelFormat.getIntArgbInstance(),
                        pixels, 0, config.width()
                );

                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.setImageSmoothing(false);
                gc.drawImage(image, 0, 0, config.canvasWidth(), config.canvasHeight());
            } finally {
                blitPending.set(false);
            }
        });
    }

    // =====================================================================
    // Palette helpers
    // =====================================================================

    private void initDefaultPaletteRgb565() {
        // 0..15 VGA-ish
        int[] rgb16 = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) {
            int c = rgb16[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = (c) & 0xFF;
            int v = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >> 3);
            pal565[i] = (short) v;
            palArgb[i] = rgb565ToArgb(v);
        }

        // 16..255 RGB332 cube mapped into RGB565 (good for Mode X)
        for (int i = 16; i < 256; i++) {
            int r3 = (i >> 5) & 0x07;
            int g3 = (i >> 2) & 0x07;
            int b2 = (i) & 0x03;

            int r = (r3 * 255) / 7;
            int g = (g3 * 255) / 7;
            int b = (b2 * 255) / 3;

            int v = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >> 3);
            pal565[i] = (short) v;
            palArgb[i] = rgb565ToArgb(v);
        }
    }

    private static int rgb565ToArgb(int rgb565) {
        int r5 = (rgb565 >> 11) & 0x1F;
        int g6 = (rgb565 >> 5) & 0x3F;
        int b5 = (rgb565) & 0x1F;

        // Expand to 8-bit per channel
        int r = (r5 << 3) | (r5 >> 2);
        int g = (g6 << 2) | (g6 >> 4);
        int b = (b5 << 3) | (b5 >> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
