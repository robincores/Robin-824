package io.github.robincores.r8.device;

import io.github.robincores.r8.system.InterruptSink;
import io.github.robincores.r8.system.Tickable;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPU (v1+) - VGA-ish planar video with CLUT (RGB565) + optional VGA-like text overlay.
 *
 * <p>Physical VRAM is 64K, organized as 4 planes x 16K. The CPU accesses VRAM via
 * a 16K window at {@code 0xC000-0xFFFF} (see VideoWindow).</p>
 *
 * <p><b>Graphics modes</b>:
 * <ul>
 *   <li>MODE 0: 640x200, 4bpp bitplanes (16 colors) -> displayed 640x400 (double-scan Y)</li>
 *   <li>MODE 1: 320x200, 4bpp bitplanes (16 colors) -> displayed 640x400 (2xX + double-scan Y)</li>
 *   <li>MODE 2: 320x200, 8bpp Mode-X byte-planes (256 colors) -> displayed 640x400 (2xX + double-scan Y)</li>
 *   <li>MODE 3: 160x200, 8bpp Mode-X byte-planes (256 colors) -> displayed 640x400 (4xX + double-scan Y)</li>
 * </ul></p>
 *
 * <p><b>Text overlay</b>: 80x25 @ 8x16 (640x400). Text RAM + Font RAM live in the 16K MMIO space.
 * The overlay can be enabled via {@code TX_CTRL} and can be hardware-scrolled with:
 * <ul>
 *   <li>{@code TX_ORIGIN} : coarse row scroll (cell ring-buffer origin)</li>
 *   <li>{@code TX_FINE_Y} : fine vertical scroll (0..15 scanlines)</li>
 *   <li>{@code TX_FINE_X} : fine horizontal scroll (0..7 pixels) with cell-to-cell carry</li>
 * </ul></p>
 *
 * <p><b>Copper</b>: optional scanline list that writes MMIO registers mid-frame for raster effects.</p>
 *
 * <p><b>Raster IRQ</b>: classic line-compare interrupt (raster splits without copper RAM).</p>
 *
 * <p><b>Blitter</b>: async VRAM-to-VRAM copy/fill engine built on the VGA-like latch pipeline
 * (single read loads latches for all planes; writes can target a plane mask with ROP/masks).</p>
 */
public final class VPU implements Tickable {

    // -------------------- Memory layout --------------------
    public static final int VRAM_SIZE   = 0x10000; // 64K total
    public static final int PLANE_SIZE  = 0x4000;  // 16K per plane
    public static final int NUM_PLANES  = 4;

    // -------------------- MMIO (within the 16K window when WIN_MMIO=1) --------------------
    // Core registers
    private static final int REG_CTRL       = 0x0000; // bit0 enable, bit1 vblank IRQ enable, bit2 raster IRQ enable
    private static final int REG_STATUS     = 0x0001; // bit0 in-vblank, bit1 frame-ready (W1C), bit2 raster-hit (W1C), bit3 blit-done (W1C)
    private static final int REG_MODE       = 0x0002; // 0..3 graphics (no text-only mode)
    private static final int REG_SCAN_L     = 0x0003; // RO (current scanline within full frame, not just visible)
    private static final int REG_SCAN_H     = 0x0004; // RO
    private static final int REG_FB_BASE_L  = 0x0005; // base offset within each plane (low)
    private static final int REG_FB_BASE_H  = 0x0006; // base offset within each plane (high)

    // Text control regs
    private static final int REG_TX_CTRL      = 0x0007;
    private static final int REG_TX_CUR_X     = 0x0008;
    private static final int REG_TX_CUR_Y     = 0x0009;
    private static final int REG_TX_CUR_START = 0x000A;
    private static final int REG_TX_CUR_END   = 0x000B;

    // Fine horizontal pan (graphics)
    private static final int REG_XPAN       = 0x000C; // 0..7 fine horizontal pan (pixels); Mode X uses 0..3

    // VGA-ish write assist regs (apply to writes through the VideoWindow VRAM aperture)
    private static final int REG_WR_PLANE_MASK = 0x000D; // bits0-3 planes to write (0=legacy selected-plane)
    private static final int REG_BIT_MASK      = 0x000E; // 8-bit mask of pixels within a byte (bit7=leftmost)
    private static final int REG_SR_COLOR      = 0x000F; // set/reset color: bitplane modes use low 4 bits; Mode X uses full 8-bit index
    private static final int REG_SR_ENABLE     = 0x0010; // bits0-3 enable set/reset per plane
    private static final int REG_ROP           = 0x0011; // 0=REPLACE, 1=XOR, 2=AND, 3=OR
    private static final int REG_WR_MODE       = 0x0012; // 0=normal, 1=write-from-latches (VRAM->VRAM copy) for CPU aperture

    // VGA-style text hardware scrolling (CRTC start + preset row scan)
    private static final int REG_TX_ORIGIN_L  = 0x0013;
    private static final int REG_TX_ORIGIN_H  = 0x0014;
    private static final int REG_TX_FINE_Y    = 0x0015;

    // Copper / scanline list
    private static final int REG_COP_CTRL    = 0x0016; // bit0 enable
    private static final int REG_COP_LEN_L   = 0x0017; // bytes used (0 => scan until END), low
    private static final int REG_COP_LEN_H   = 0x0018; // high

    // Text programming helpers (assembly-friendly)
    private static final int REG_TX_CMD       = 0x0019;
    private static final int REG_TX_ATTR      = 0x001A;
    private static final int REG_TX_PORT      = 0x001B;

    // Raster / line-compare IRQ (classic raster split)
    private static final int REG_RASTER_CMP_L = 0x001C; // compare against REG_SCAN (full frame scanlines)
    private static final int REG_RASTER_CMP_H = 0x001D;

    // Smooth text horizontal scroll (0..7)
    private static final int REG_TX_FINE_X    = 0x001E;
    // Overlay personality select: 0=text buffer+font, 1=tile RAM (sprites)
    private static final int REG_OVL_MODE     = 0x001F; // bit0

    // Blitter (VRAM-to-VRAM)
    private static final int REG_BLT_CTRL        = 0x0020; // bit0 START (W1), bit1 FILL, bit2 IRQ_EN, bit7 BUSY(RO)
    private static final int REG_BLT_SRC_L       = 0x0021;
    private static final int REG_BLT_SRC_H       = 0x0022;
    private static final int REG_BLT_DST_L       = 0x0023;
    private static final int REG_BLT_DST_H       = 0x0024;
    private static final int REG_BLT_W_L         = 0x0025; // bytes per row
    private static final int REG_BLT_W_H         = 0x0026;
    private static final int REG_BLT_H           = 0x0027; // rows (0 => 1)
    private static final int REG_BLT_SRC_PITCH_L = 0x0028; // added after each row (in addition to W)
    private static final int REG_BLT_SRC_PITCH_H = 0x0029;
    private static final int REG_BLT_DST_PITCH_L = 0x002A;
    private static final int REG_BLT_DST_PITCH_H = 0x002B;
    private static final int REG_BLT_FILL        = 0x002C; // fill byte used when FILL=1
    private static final int REG_BLT_PLANE_MASK  = 0x002D; // planes to affect (default 0x0F)

    // Palette RAM (256 x RGB565 little-endian)
    private static final int PAL_BASE  = 0x0100;
    private static final int PAL_SIZE  = 0x0200; // 512 bytes

    // Overlay region (personality-dependent)
    private static final int OVERLAY_BASE = 0x2000;
    private static final int OVERLAY_SIZE = 0x2000; // 8K

    // OAM / SAT (sprite attribute table)
    private static final int OAM_BASE  = 0x0300;
    private static final int OAM_SIZE  = 0x0400; // 1024 bytes
    private static final int SPRITE_COUNT  = 128;
    private static final int SPRITE_STRIDE = 8;   // bytes per sprite in OAM

    // Tile format for sprite personality: 8x8, 4bpp packed (2 pixels per byte), 32 bytes per tile.
    private static final int TILE_BYTES = 32;
    private static final int TILE_STRIDE_ROW = 4; // 8 pixels / 2 per byte

    // Reserved / scratch
    private static final int SCRATCH_BASE = 0x0700;
    private static final int SCRATCH_SIZE = 0x0900; // 2304 bytes

    // Text RAM
    private static final int TEXT_COLS   = 80;
    private static final int TEXT_ROWS   = 25;
    private static final int TEXT_CELL_B = 2;
    private static final int TEXT_BASE   = 0x2000;
    private static final int TEXT_SIZE   = (TEXT_COLS * TEXT_ROWS * TEXT_CELL_B); // 4000 bytes
    private static final int TEXT_CELLS  = (TEXT_COLS * TEXT_ROWS); // 2000
    private static final int TEXT_SCANLINES = (TEXT_ROWS * 16); // 400

    // Font RAM
    private static final int FONT_BASE   = 0x3000;
    private static final int FONT_SIZE   = 0x1000; // 4096 bytes

    // Copper list RAM
    private static final int COPPER_BASE   = 0x1000;
    private static final int COPPER_SIZE   = 0x1000; // 4096 bytes
    private static final int COPPER_STRIDE = 8;

    // CTRL bits
    private static final int CTRL_ENABLE      = 0x01;
    private static final int CTRL_VBLANK_IRQ  = 0x02;
    private static final int CTRL_RASTER_IRQ  = 0x04;
    private static final int CTRL_GFX_DIS     = 0x08; // disable graphics underlay (overlay can still draw)

    // STATUS bits
    private static final int STATUS_VBLANK = 0x01;
    private static final int STATUS_FRAME  = 0x02;
    private static final int STATUS_RASTER = 0x04;
    private static final int STATUS_BLT    = 0x08;

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_TRANSPARENT_BG = 0x04;
    private static final int TX_CURSOR_BLINK   = 0x08;

    // Copper flags
    private static final int COP_CTRL_EN       = 0x01;
    private static final int COP_FLAG_WRITE16  = 0x01;
    private static final int COP_FLAG_END      = 0x80;

    // TX_CMD bits (write-only)
    private static final int TXCMD_CLR_EOL     = 0x01;
    private static final int TXCMD_CLR_LINE    = 0x02;
    private static final int TXCMD_CLR_SCREEN  = 0x04;
    private static final int TXCMD_SCROLL_UP   = 0x08;
    private static final int TXCMD_HOME        = 0x10;

    // Blitter ctrl bits
    private static final int BLT_START   = 0x01;
    private static final int BLT_FILL    = 0x02;
    private static final int BLT_IRQ_EN  = 0x04;
    private static final int BLT_BUSY_RO = 0x80;

    // Blitter throughput (FPGA-ish). We advance 1 byte-op per this many CPU cycles passed to tick().
    // Tune this if you want the blitter slower/faster relative to the CPU.
    private static final int BLT_CYCLES_PER_BYTE = 2;

    // -------------------- Config / wiring --------------------
    private final DisplayConfig config;
    private final InterruptSink sink;
    private final int irqBit;
    private final Canvas canvas;

    // -------------------- State --------------------
    private final byte[] vram = new byte[VRAM_SIZE];

    private final short[] pal565 = new short[256];
    private final int[] palArgb  = new int[256];

    private int ctrl;
    private int status;
    private int mode;
    private int fbBase;
    private int xPan;

    // Raster compare
    private int rasterCmp;

    // Write-assist state (VGA-ish)
    private int wrPlaneMask;
    private int bitMask;
    private int srColor;
    private int srEnable;
    private int rop;
    private int wrMode;

    // VGA-style read latches (loaded on ANY VRAM read)
    private int latch0, latch1, latch2, latch3;

    // Timing
    private int scanline;     // 0..scanlinesPerFrame-1 (full frame)
    private int cycleAccum;
    private int frameCounter;

    // Text state
    private int txCtrl;
    private int txCurX;
    private int txCurY;
    private int txCurStart;
    private int txCurEnd;

    private int txOrigin; // cell offset 0..TEXT_CELLS-1
    private int txFineY;  // 0..15
    private int txFineX;  // 0..7
    private int txAttr;   // default attribute used by TX_PORT / TX_CMD

    // Overlay personality (0=text, 1=sprites/tile RAM)
    private int ovlMode;

    // OAM (sprite attributes) + scratch + tile RAM (overlay sprite personality)
    private final byte[] oamRam     = new byte[OAM_SIZE];
    private final byte[] scratchRam = new byte[SCRATCH_SIZE];
    private final byte[] tileRam    = new byte[OVERLAY_SIZE];

    private final byte[] textRam  = new byte[TEXT_SIZE];
    private final byte[] textResv = new byte[0x60];     // 0x2FA0–0x2FFF (96 bytes) reserved in text personality
    private final byte[] font8x16 = new byte[FONT_SIZE];

    // Copper state
    private int copCtrl;
    private int copLen;
    private final byte[] copperRam = new byte[COPPER_SIZE];

    private static final int COP_MAX = (COPPER_SIZE / COPPER_STRIDE); // 512
    private final int[] copScanTmp  = new int[COP_MAX];
    private final int[] copRegTmp   = new int[COP_MAX];
    private final int[] copValLoTmp = new int[COP_MAX];
    private final int[] copValHiTmp = new int[COP_MAX];
    private final int[] copFlagsTmp = new int[COP_MAX];

    private final int[] copScan  = new int[COP_MAX];
    private final int[] copReg   = new int[COP_MAX];
    private final int[] copValLo = new int[COP_MAX];
    private final int[] copValHi = new int[COP_MAX];
    private final int[] copFlags = new int[COP_MAX];

    private int copCount;
    private int copIdx;
    private final int[] copCounts;

    // Copper decode caching (static lists shouldn't re-decode every frame)
    private boolean copDirty = true;
    private boolean copCompiled = false;

    // Optional coarse profiling (enable with -Dvpu.prof=true)
    private static final boolean PROF = Boolean.getBoolean("vpu.prof");
    private long profFrames;
    private long profFramesDropped;
    private long profBatchRenderNanos;
    private long profLiveScanlineNanos;
    private long profCopperDecodeNanos;
    private long profFxBlitNanos;

    // Blitter state (async)
    private int bltCtrl;
    private int bltSrc, bltDst;
    private int bltW, bltH;
    private int bltSrcPitch, bltDstPitch;
    private int bltFill;
    private int bltPlaneMask;


    // Blitter runtime (latched at START so MMIO regs don't get mutated by clamping)
    private int bltWRun;
    private int bltHRun;
    private int bltSrcPitchRun;
    private int bltDstPitchRun;
    private int bltPlaneMaskRun;
    private boolean bltBusy;
    private int bltX, bltY;
    private int bltSrcCur, bltDstCur;
    private int bltCyclesAcc;

    // -------------------- Rendering --------------------
    private final WritableImage image;
    private final PixelWriter writer;


    private final GraphicsContext gc;
    private static final PixelFormat<java.nio.IntBuffer> ARGB_FORMAT = PixelFormat.getIntArgbInstance();
    // Triple buffer (render -> queued -> display)
    private final Object fbLock = new Object();
    private int[] renderBuf;
    private int[] displayBuf;
    private int[] freeBuf;
    private int[] queuedBuf;
    // Emulation thread sets this when a new frame is ready; FX thread clears it on present.
    private final AtomicBoolean fxDirty = new AtomicBoolean(false);

    // Mode 0/1 decode LUT helpers (used only for the non-copper fast path)
    // BITPACK[b] packs the 8 bits of b into 8 nibbles (LSB nibble = leftmost pixel), each nibble is 0 or 1.
    private static final int[] BITPACK = new int[256];
    static {
        for (int b = 0; b < 256; b++) {
            int p = 0;
            // leftmost pixel corresponds to bit7
            for (int i = 0; i < 8; i++) {
                int bit = (b >>> (7 - i)) & 1;
                p |= (bit << (i * 4));
            }
            BITPACK[b] = p;
        }
    }


    // Text glyph-row expansion LUT (fast text for the common case: no copper, fineX=0, opaque bg).
    // TEXT_PAIR4[(glyphByte<<8)|attr] -> packed 4 bytes, each byte is (leftIdx | (rightIdx<<4)).
    // Those bytes index pairArgb[] to emit 2 ARGB pixels at once.
    private static final int[] TEXT_PAIR4 = new int[256 * 256];
    static {
        for (int glyph = 0; glyph < 256; glyph++) {
            int pb = BITPACK[glyph]; // 8 nibbles (0/1), left->right
            for (int attr = 0; attr < 256; attr++) {
                int fg = attr & 0x0F;
                int bg = (attr >>> 4) & 0x0F;

                int packedPairs = 0;
                for (int k = 0; k < 4; k++) {
                    int n0 = (pb >>> ((2 * k) * 4)) & 1;
                    int n1 = (pb >>> ((2 * k + 1) * 4)) & 1;

                    // Branchless select: bit ? fg : bg
                    int left  = bg ^ (-(n0) & (bg ^ fg));
                    int right = bg ^ (-(n1) & (bg ^ fg));

                    int pair = (left & 0x0F) | ((right & 0x0F) << 4);
                    packedPairs |= (pair << (k * 8));
                }
                TEXT_PAIR4[(glyph << 8) | attr] = packedPairs;
            }
        }
    }
    // Pair LUT maps two 4-bit indices (packed as lowNibble=left, highNibble=right) to two ARGB ints in a long.
    private final long[] pairArgb = new long[256];
    private boolean pairDirty = true;

    public VPU(DisplayConfig config, InterruptSink sink, int irqBit, Canvas canvas) {
        this.config = config;
        this.sink = sink;
        this.irqBit = irqBit;
        this.canvas = canvas;

        canvas.setWidth(config.canvasWidth());
        canvas.setHeight(config.canvasHeight());

        this.image = new WritableImage(config.width(), config.height());
        this.writer = image.getPixelWriter();

        this.gc = canvas.getGraphicsContext2D();
        this.gc.setImageSmoothing(false);

        int npx = config.width() * config.height();
        this.renderBuf  = new int[npx];
        this.displayBuf = new int[npx];
        this.freeBuf    = new int[npx];
        this.queuedBuf  = null;

        initDefaultPaletteRgb565();
        initDefaultTextRam();
        initDefaultFont8x16();

        // Power-on defaults
        this.ctrl = CTRL_ENABLE;
        this.mode = 0;
        this.fbBase = 0;
        this.xPan = 0;

        this.rasterCmp = 0;

        this.wrPlaneMask = 0; // legacy
        this.bitMask = 0xFF;
        this.srColor = 0;
        this.srEnable = 0;
        this.rop = 0;
        this.wrMode = 0;

        this.txCtrl = (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK);
        this.txCurX = 0;
        this.txCurY = 0;
        this.txCurStart = 14;
        this.txCurEnd = 15;

        this.txOrigin = 0;
        this.txFineY = 0;
        this.txFineX = 0;
        this.txAttr = 0x07;

        this.ovlMode = 0; // default to text personality

        this.copCtrl = 0;
        this.copLen = 0;
        Arrays.fill(this.copperRam, (byte) 0);
        this.copDirty = true;
        this.copCompiled = false;
        this.copCounts = new int[Math.max(1, config.height())];

        this.bltCtrl = 0;
        this.bltSrc = this.bltDst = 0;
        this.bltW = 0;
        this.bltH = 1;
        this.bltSrcPitch = this.bltDstPitch = 0;
        this.bltFill = 0;
        this.bltPlaneMask = 0x0F;
        this.bltBusy = false;

        this.scanline = 0;
        this.cycleAccum = 0;
        this.frameCounter = 0;
    }

    // =====================================================================
    // Reset
    // =====================================================================

    public void reset() { reset(true); }

    public void reset(boolean hard) {
        ctrl = CTRL_ENABLE;
        status = 0;
        mode = 0;
        fbBase = 0;
        xPan = 0;
        rasterCmp = 0;

        wrPlaneMask = 0;
        bitMask = 0xFF;
        srColor = 0;
        srEnable = 0;
        rop = 0;
        wrMode = 0;
        latch0 = latch1 = latch2 = latch3 = 0;

        scanline = 0;
        cycleAccum = 0;
        frameCounter = 0;

        txCtrl = (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK);
        txCurX = 0;
        txCurY = 0;
        txCurStart = 14;
        txCurEnd = 15;
        txOrigin = 0;
        txFineY = 0;
        txFineX = 0;
        txAttr = 0x07;

        ovlMode = 0;

        copCtrl = 0;
        copLen = 0;
        copCount = 0;
        copIdx = 0;
        copDirty = true;
        copCompiled = false;

        bltCtrl = 0;
        bltBusy = false;
        bltX = bltY = 0;
        bltCyclesAcc = 0;
        bltPlaneMask = 0x0F;
        bltWRun = 0;
        bltHRun = 0;
        bltSrcPitchRun = 0;
        bltDstPitchRun = 0;
        bltPlaneMaskRun = 0x0F;

        pairDirty = true;

        if (hard) {
            Arrays.fill(vram, (byte) 0);
            Arrays.fill(copperRam, (byte) 0);
            Arrays.fill(oamRam, (byte) 0);
            Arrays.fill(scratchRam, (byte) 0);
            Arrays.fill(tileRam, (byte) 0);
            Arrays.fill(textResv, (byte) 0);
            initDefaultPaletteRgb565();
            initDefaultTextRam();
            initDefaultFont8x16();
        }
    }

    private void normalizeTxOrigin() {
        txOrigin %= TEXT_CELLS;
        if (txOrigin < 0) txOrigin += TEXT_CELLS;
    }

    // =====================================================================
    // VRAM plane access (used by VideoWindow)
    // =====================================================================

    public byte readVramPlane(int plane, int offset) {
        int ofs = offset & (PLANE_SIZE - 1);

        // Any VRAM read loads all four plane latches at this address (VGA behavior).
        int base = ofs;
        latch0 = vram[(0 * PLANE_SIZE) + base] & 0xFF;
        latch1 = vram[(1 * PLANE_SIZE) + base] & 0xFF;
        latch2 = vram[(2 * PLANE_SIZE) + base] & 0xFF;
        latch3 = vram[(3 * PLANE_SIZE) + base] & 0xFF;

        int p = plane & 0x03;
        return vram[(p * PLANE_SIZE) + base];
    }

    public void writeVramPlane(int plane, int offset, byte value) {
        // CPU aperture write uses the MMIO WR_MODE.
        writeVramPlaneInternal(plane, offset, (value & 0xFF), ((wrMode & 0x01) != 0), 0);
    }

    // planeMaskOverride: 0 => use wrPlaneMask legacy semantics, else use that mask.
    private void writeVramPlaneInternal(int plane, int offset, int data, boolean forceLatchSrc, int planeMaskOverride) {
        int pSel = plane & 0x03;
        int ofs = offset & (PLANE_SIZE - 1);

        int pm = planeMaskOverride != 0 ? (planeMaskOverride & 0x0F) : (wrPlaneMask & 0x0F);
        if (pm == 0) pm = 1 << pSel;

        int bm = bitMask & 0xFF;

        final boolean bitplaneMode = (mode <= 1);
        final int sr = bitplaneMode ? (srColor & 0x0F) : (srColor & 0xFF);

        for (int p = 0; p < 4; p++) {
            if ((pm & (1 << p)) == 0) continue;

            int idx = (p * PLANE_SIZE) + ofs;
            int cur = vram[idx] & 0xFF;

            int src;
            if (forceLatchSrc) {
                src = switch (p) {
                    case 0 -> latch0;
                    case 1 -> latch1;
                    case 2 -> latch2;
                    default -> latch3;
                };
            } else if ((srEnable & (1 << p)) != 0) {
                if (bitplaneMode) {
                    src = ((sr >>> p) & 1) != 0 ? 0xFF : 0x00;
                } else {
                    src = sr;
                }
            } else {
                src = data;
            }

            int out = switch (rop & 0x03) {
                case 0 -> (cur & ~bm) | (src & bm);               // REPLACE
                case 1 -> cur ^ (src & bm);                      // XOR
                case 2 -> (cur & ~bm) | ((cur & src) & bm);      // AND
                default -> (cur & ~bm) | ((cur | src) & bm);     // OR
            };

            vram[idx] = (byte) out;
        }
    }

    // =====================================================================
    // MMIO view (used by VideoWindow)
    // =====================================================================

    public byte readMmio(int offset) {
        int o = offset & 0x3FFF;

        // Palette RAM (256 x RGB565 little-endian)
        if (o >= PAL_BASE && o < (PAL_BASE + PAL_SIZE)) {
            int idx = (o - PAL_BASE) >> 1;
            boolean hi = ((o - PAL_BASE) & 1) != 0;
            int v = pal565[idx] & 0xFFFF;
            return (byte) (hi ? ((v >> 8) & 0xFF) : (v & 0xFF));
        }

        // OAM / SAT (sprite attributes)
        if (o >= OAM_BASE && o < (OAM_BASE + OAM_SIZE)) {
            return oamRam[o - OAM_BASE];
        }

        // Reserved / scratch
        if (o >= SCRATCH_BASE && o < (SCRATCH_BASE + SCRATCH_SIZE)) {
            return scratchRam[o - SCRATCH_BASE];
        }

        // Copper RAM
        if (o >= COPPER_BASE && o < (COPPER_BASE + COPPER_SIZE)) {
            return copperRam[o - COPPER_BASE];
        }

        // Overlay region (8K): either Text+Font or Tile RAM depending on overlay personality
        if (o >= OVERLAY_BASE && o < (OVERLAY_BASE + OVERLAY_SIZE)) {
            int rel = o - OVERLAY_BASE;

            // Sprite personality: 0x2000–0x3FFF is tile RAM (text/font disabled)
            if ((ovlMode & 0x01) != 0) {
                return tileRam[rel];
            }

            // Text personality: 0x2000–0x2F9F text buffer, 0x2FA0–0x2FFF reserved, 0x3000–0x3FFF font
            if (o >= TEXT_BASE && o < (TEXT_BASE + TEXT_SIZE)) {
                return textRam[o - TEXT_BASE];
            }
            if (o >= (TEXT_BASE + TEXT_SIZE) && o < FONT_BASE) {
                return textResv[o - (TEXT_BASE + TEXT_SIZE)];
            }
            if (o >= FONT_BASE && o < (FONT_BASE + FONT_SIZE)) {
                return font8x16[o - FONT_BASE];
            }
            return 0;
        }

        return (byte) switch (o) {
            case REG_CTRL -> ctrl;
            case REG_STATUS -> status;
            case REG_MODE -> mode;
            case REG_SCAN_L -> (scanline & 0xFF);
            case REG_SCAN_H -> ((scanline >> 8) & 0xFF);
            case REG_FB_BASE_L -> (fbBase & 0xFF);
            case REG_FB_BASE_H -> ((fbBase >> 8) & 0xFF);

            case REG_XPAN -> xPan;

            case REG_WR_PLANE_MASK -> wrPlaneMask;
            case REG_BIT_MASK -> bitMask;
            case REG_SR_COLOR -> srColor;
            case REG_SR_ENABLE -> srEnable;
            case REG_ROP -> rop;
            case REG_WR_MODE -> wrMode;

            case REG_TX_ORIGIN_L -> (txOrigin & 0xFF);
            case REG_TX_ORIGIN_H -> ((txOrigin >>> 8) & 0xFF);
            case REG_TX_FINE_Y -> (txFineY & 0x0F);
            case REG_TX_FINE_X -> (txFineX & 0x07);
            case REG_OVL_MODE -> (ovlMode & 0x01);

            case REG_TX_CMD -> 0;
            case REG_TX_ATTR -> txAttr;
            case REG_TX_PORT -> 0;

            case REG_TX_CTRL -> txCtrl;
            case REG_TX_CUR_X -> txCurX;
            case REG_TX_CUR_Y -> txCurY;
            case REG_TX_CUR_START -> txCurStart;
            case REG_TX_CUR_END -> txCurEnd;

            case REG_RASTER_CMP_L -> (rasterCmp & 0xFF);
            case REG_RASTER_CMP_H -> ((rasterCmp >>> 8) & 0xFF);

            case REG_COP_CTRL -> copCtrl;
            case REG_COP_LEN_L -> (copLen & 0xFF);
            case REG_COP_LEN_H -> ((copLen >>> 8) & 0xFF);

            case REG_BLT_CTRL -> (bltBusy ? (bltCtrl | BLT_BUSY_RO) : (bltCtrl & ~BLT_BUSY_RO));
            case REG_BLT_SRC_L -> (bltSrc & 0xFF);
            case REG_BLT_SRC_H -> ((bltSrc >>> 8) & 0xFF);
            case REG_BLT_DST_L -> (bltDst & 0xFF);
            case REG_BLT_DST_H -> ((bltDst >>> 8) & 0xFF);
            case REG_BLT_W_L -> (bltW & 0xFF);
            case REG_BLT_W_H -> ((bltW >>> 8) & 0xFF);
            case REG_BLT_H -> (bltH & 0xFF);
            case REG_BLT_SRC_PITCH_L -> (bltSrcPitch & 0xFF);
            case REG_BLT_SRC_PITCH_H -> ((bltSrcPitch >>> 8) & 0xFF);
            case REG_BLT_DST_PITCH_L -> (bltDstPitch & 0xFF);
            case REG_BLT_DST_PITCH_H -> ((bltDstPitch >>> 8) & 0xFF);
            case REG_BLT_FILL -> (bltFill & 0xFF);
            case REG_BLT_PLANE_MASK -> (bltPlaneMask & 0x0F);

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
            pairDirty = true;
            return;
        }

        // OAM / SAT
        if (o >= OAM_BASE && o < (OAM_BASE + OAM_SIZE)) {
            oamRam[o - OAM_BASE] = (byte) v;
            return;
        }

        // Scratch
        if (o >= SCRATCH_BASE && o < (SCRATCH_BASE + SCRATCH_SIZE)) {
            scratchRam[o - SCRATCH_BASE] = (byte) v;
            return;
        }

        // Copper RAM
        if (o >= COPPER_BASE && o < (COPPER_BASE + COPPER_SIZE)) {
            copperRam[o - COPPER_BASE] = (byte) v;
            copDirty = true;
            return;
        }

        // Overlay region: either Text+Font or Tile RAM depending on overlay personality
        if (o >= OVERLAY_BASE && o < (OVERLAY_BASE + OVERLAY_SIZE)) {
            int rel = o - OVERLAY_BASE;

            if ((ovlMode & 0x01) != 0) {
                // Sprite personality: 0x2000–0x3FFF maps to tile RAM
                tileRam[rel] = (byte) v;
                return;
            }

            // Text personality
            if (o >= TEXT_BASE && o < (TEXT_BASE + TEXT_SIZE)) {
                textRam[o - TEXT_BASE] = (byte) v;
                return;
            }
            if (o >= (TEXT_BASE + TEXT_SIZE) && o < FONT_BASE) {
                textResv[o - (TEXT_BASE + TEXT_SIZE)] = (byte) v;
                return;
            }
            if (o >= FONT_BASE && o < (FONT_BASE + FONT_SIZE)) {
                font8x16[o - FONT_BASE] = (byte) v;
                return;
            }
            return;
        }

        switch (o) {
            case REG_CTRL -> ctrl = (v & 0xFF);

            case REG_STATUS -> {
                // W1C only for event flags. VBLANK is timing-owned and clears automatically.
                int w1c = v & (STATUS_FRAME | STATUS_RASTER | STATUS_BLT);
                status &= ~w1c;
            }
            case REG_MODE -> {
                int m = (v & 0x07);
                mode = (m <= 3) ? m : 0;
            }
            case REG_FB_BASE_L -> fbBase = (fbBase & 0xFF00) | v;
            case REG_FB_BASE_H -> fbBase = (fbBase & 0x00FF) | (v << 8);

            case REG_XPAN -> xPan = (v & 0x07);

            case REG_WR_PLANE_MASK -> wrPlaneMask = (v & 0x0F);
            case REG_BIT_MASK -> bitMask = (v & 0xFF);
            case REG_SR_COLOR -> srColor = (v & 0xFF);
            case REG_SR_ENABLE -> srEnable = (v & 0x0F);
            case REG_ROP -> rop = (v & 0x03);
            case REG_WR_MODE -> wrMode = (v & 0x01);

            case REG_TX_ORIGIN_L -> { txOrigin = (txOrigin & 0xFF00) | v; normalizeTxOrigin(); }
            case REG_TX_ORIGIN_H -> { txOrigin = (txOrigin & 0x00FF) | (v << 8); normalizeTxOrigin(); }
            case REG_TX_FINE_Y -> txFineY = (v & 0x0F);
            case REG_TX_FINE_X -> txFineX = (v & 0x07);
            case REG_OVL_MODE -> ovlMode = (v & 0x01);

            case REG_TX_CMD -> txCommand(v);
            case REG_TX_ATTR -> txAttr = (v & 0xFF);
            case REG_TX_PORT -> txPortWrite(v);

            case REG_TX_CTRL -> txCtrl = (v & 0xFF);
            case REG_TX_CUR_X -> txCurX = Math.min(v, TEXT_COLS - 1);
            case REG_TX_CUR_Y -> txCurY = Math.min(v, TEXT_ROWS - 1);
            case REG_TX_CUR_START -> txCurStart = (v & 0x0F);
            case REG_TX_CUR_END -> txCurEnd = (v & 0x0F);

            case REG_RASTER_CMP_L -> rasterCmp = (rasterCmp & 0xFF00) | v;
            case REG_RASTER_CMP_H -> rasterCmp = (rasterCmp & 0x00FF) | (v << 8);

            case REG_COP_CTRL -> copCtrl = (v & 0xFF);
            case REG_COP_LEN_L -> { copLen = (copLen & 0xFF00) | v; copDirty = true; }
            case REG_COP_LEN_H -> { copLen = (copLen & 0x00FF) | (v << 8); copDirty = true; }

            case REG_BLT_CTRL -> bltWriteCtrl(v);
            case REG_BLT_SRC_L -> bltSrc = (bltSrc & 0xFF00) | v;
            case REG_BLT_SRC_H -> bltSrc = (bltSrc & 0x00FF) | (v << 8);
            case REG_BLT_DST_L -> bltDst = (bltDst & 0xFF00) | v;
            case REG_BLT_DST_H -> bltDst = (bltDst & 0x00FF) | (v << 8);
            case REG_BLT_W_L -> bltW = (bltW & 0xFF00) | v;
            case REG_BLT_W_H -> bltW = (bltW & 0x00FF) | (v << 8);
            case REG_BLT_H -> bltH = (v & 0xFF);
            case REG_BLT_SRC_PITCH_L -> bltSrcPitch = (bltSrcPitch & 0xFF00) | v;
            case REG_BLT_SRC_PITCH_H -> bltSrcPitch = (bltSrcPitch & 0x00FF) | (v << 8);
            case REG_BLT_DST_PITCH_L -> bltDstPitch = (bltDstPitch & 0xFF00) | v;
            case REG_BLT_DST_PITCH_H -> bltDstPitch = (bltDstPitch & 0x00FF) | (v << 8);
            case REG_BLT_FILL -> bltFill = (v & 0xFF);
            case REG_BLT_PLANE_MASK -> bltPlaneMask = (v & 0x0F);

            default -> { /* ignored */ }
        }
    }


    private void bltWriteCtrl(int v) {
        // preserve BUSY bit as RO (bit7)
        int next = (v & 0x7F);
        bltCtrl = next;

        if ((v & BLT_START) != 0) {
            bltStart();
        }
    }

    private void bltStart() {
        if (bltBusy) return;

        bltBusy = true;
        bltCyclesAcc = 0;

        // Latch run-time parameters without mutating the MMIO-visible registers.
        bltSrcCur = bltSrc & 0x3FFF;
        bltDstCur = bltDst & 0x3FFF;

        int w = bltW & 0xFFFF;
        if (w <= 0) w = 1;
        bltWRun = w;

        int h = bltH & 0xFF;
        if (h == 0) h = 1;
        bltHRun = h;

        // Treat pitch as signed 16-bit (so 0xFFB0 becomes -80, etc.)
        bltSrcPitchRun = (short) (bltSrcPitch & 0xFFFF);
        bltDstPitchRun = (short) (bltDstPitch & 0xFFFF);

        int pm = bltPlaneMask & 0x0F;
        if (pm == 0) pm = 0x0F; // 0 means "all planes" for blitter
        bltPlaneMaskRun = pm;

        bltX = 0;
        bltY = 0;

        // Clear previous "blt done" flag.
        status &= ~STATUS_BLT;
    }

    // =====================================================================
    // Tick / timing
    // =====================================================================

    @Override
    public void tick(int cycles) {
        if ((ctrl & CTRL_ENABLE) == 0) return;

        // Blitter progresses with CPU cycles (async co-processor).
        bltTick(cycles);

        cycleAccum += cycles;
        final int cps = config.cyclesPerScanline();

        while (cycleAccum >= cps) {
            cycleAccum -= cps;

            // Start-of-frame housekeeping (scanline 0).
            if (scanline == 0) {
                beginFrame();
            }

            // Live scanline rendering is only enabled when raster IRQs are enabled.
            // Otherwise we batch-render once at vblank for maximum speed.
            if (frameLiveRender) {
                if (scanline < config.height()) {
                    if (PROF) {
                        long t0 = System.nanoTime();
                        renderScanlineIntoBackBuffer(scanline);
                        profLiveScanlineNanos += (System.nanoTime() - t0);
                    } else {
                        renderScanlineIntoBackBuffer(scanline);
                    }
                }
            }

            // Raster compare (full-frame scanline)
            if (((ctrl & CTRL_RASTER_IRQ) != 0) && (scanline == (rasterCmp & 0xFFFF))) {
                status |= STATUS_RASTER;
                sink.raise(irqBit);
            }

            // Enter vblank: finish / publish frame
            if (scanline == config.vblankStart()) {
                status |= STATUS_VBLANK | STATUS_FRAME;
                frameCounter++;

                if (frameLiveRender) {
                    publishRenderedFrame();
                } else {
                    if (PROF) {
                        long t0 = System.nanoTime();
                        renderFrameIntoBackBuffer();
                        profBatchRenderNanos += (System.nanoTime() - t0);
                    } else {
                        renderFrameIntoBackBuffer();
                    }
                }
                requestBlit();

                if ((ctrl & CTRL_VBLANK_IRQ) != 0) {
                    sink.raise(irqBit);
                }

                if (PROF) {
                    profFrames++;
                    if ((profFrames % 120) == 0) dumpProf();
                }
            }

            // Advance to next scanline
            scanline++;
            if (scanline >= config.scanlinesPerFrame()) {
                scanline = 0;
                status &= ~STATUS_VBLANK;
            }
        }
    }

    private void bltTick(int cycles) {
        if (!bltBusy) return;

        bltCyclesAcc += cycles;

        while (bltBusy && bltCyclesAcc >= BLT_CYCLES_PER_BYTE) {
            bltCyclesAcc -= BLT_CYCLES_PER_BYTE;
            bltStepOneByte();
        }
    }

    private void bltStepOneByte() {
        // Perform a single byte-op at current (x,y) position.
        int srcOfs = bltSrcCur & 0x3FFF;
        int dstOfs = bltDstCur & 0x3FFF;

        if ((bltCtrl & BLT_FILL) != 0) {
            // NOTE: Blitter uses the same write-assist registers as the CPU aperture (bitMask/sr*/rop).
            // They are intentionally *live* (VGA-like): changing them mid-blit affects subsequent blit bytes.
            writeVramPlaneInternal(0, dstOfs, bltFill & 0xFF, false, bltPlaneMaskRun);
        } else {
            // Copy uses latch pipeline: read loads latch0..3 for all planes, write uses latch-src.
            readVramPlane(0, srcOfs);
            writeVramPlaneInternal(0, dstOfs, 0, true, bltPlaneMaskRun);
        }

        bltX++;
        bltSrcCur = (bltSrcCur + 1) & 0x3FFF;
        bltDstCur = (bltDstCur + 1) & 0x3FFF;

        if (bltX >= bltWRun) {
            bltX = 0;
            bltY++;

            bltSrcCur = (bltSrcCur + bltSrcPitchRun) & 0x3FFF;
            bltDstCur = (bltDstCur + bltDstPitchRun) & 0x3FFF;

            if (bltY >= bltHRun) {
                bltBusy = false;
                status |= STATUS_BLT;

                if ((bltCtrl & BLT_IRQ_EN) != 0) {
                    sink.raise(irqBit);
                }
            }
        }
    }


    // =====================================================================
    // Live scanline rendering helpers (FPGA-ish: CPU writes during frame can affect later scanlines)
    // =====================================================================

    // Frame-latched flags (classic behavior: program before scanline 0).
    private boolean frameCopperEnabled;
    private boolean frameLiveRender;
    private boolean frameCursorEnabled;

    private void beginFrame() {
        frameCopperEnabled = (copCtrl & COP_CTRL_EN) != 0;

        // For speed we only do "live" scanline rendering when software is expected
        // to poke VPU regs mid-frame. In this VPU model that's driven by the raster IRQ.
        frameLiveRender = (ctrl & CTRL_RASTER_IRQ) != 0;

        // Compile copper only if we are going to consume it during active scanline rendering.
        // (In the common case: no raster IRQ, we batch-render at vblank and compile there.)
        if (frameLiveRender && frameCopperEnabled) {
            copperBeginFrame();
        } else {
            copCount = 0;
            copIdx = 0;
        }

        // Cursor blink phase (fixed rate): ~70Hz -> toggle every 16 frames ≈ 2.2Hz.
        boolean blinkPhaseOn = ((txCtrl & TX_CURSOR_BLINK) == 0) || (((frameCounter >> 4) & 1) == 0);
        frameCursorEnabled = ((txCtrl & TX_CURSOR_EN) != 0) && blinkPhaseOn;
    }

    private void renderScanlineIntoBackBuffer(int y) {
        // Copper moves first, so register writes affect this scanline.
        if (frameCopperEnabled) {
            copperApplyForScanline(y);
        }

        if (!frameCopperEnabled && (y & 1) == 1) {
            int W = config.width();
            System.arraycopy(renderBuf, (y - 1) * W, renderBuf, y * W, W);
            return;
        }

        final int W = config.width();
        final int rowOfs = y * W;

        // Cursor blink phase is frame-latched (stable) but only applies in text personality.
        boolean cursorEnabled = frameCursorEnabled;

        boolean gfxDisabled = (ctrl & CTRL_GFX_DIS) != 0;

        boolean spritePersonality = (ovlMode & 0x01) != 0;
        boolean textPersonality = !spritePersonality;

        if (gfxDisabled) {
            Arrays.fill(renderBuf, rowOfs, rowOfs + W, palArgb[0]);
        } else {
            if (!frameCopperEnabled && (mode == 0 || mode == 1)) {
                if (pairDirty) rebuildPairArgb();
                renderGraphicsScanlineFastBitplanes(y);
            } else {
                renderGraphicsScanline(y);
            }
        }

        if (spritePersonality) {
            renderSpritesScanline(y);
        } else if (textPersonality) {
            boolean transparentBg = (txCtrl & TX_TRANSPARENT_BG) != 0;
            boolean textActive = (txCtrl & TX_EN) != 0;
            if (textActive) {
                renderTextScanline(y, transparentBg, cursorEnabled);
            }
        }
    }


    private void publishRenderedFrame() {
        synchronized (fbLock) {
            // If FX hasn't consumed the last queued frame and we have no spare buffer,
            // drop this frame (avoids allocations + keeps scroll smooth).
            if (queuedBuf != null && freeBuf == null) {
                if (PROF) profFramesDropped++;
                return;
            }

            if (queuedBuf != null) {
                freeBuf = queuedBuf;
                queuedBuf = null;
            }

            queuedBuf = renderBuf;

            if (freeBuf != null) {
                renderBuf = freeBuf;
                freeBuf = null;
            }
        }
    }

    // Fast scanline renderer for modes 0/1 (bitplanes) using BITPACK + pairArgb LUTs.
    // Only used when copper is disabled for this frame.
    private void renderGraphicsScanlineFastBitplanes(int yOut) {
        final int W = 640;
        final int ySrc = (yOut >> 1);
        final int rowOfs = yOut * W;

        if (mode == 0) {
            final int bpl = 80;
            final int base0 = fbBase & 0x3FFF;
            final int xByteOfs = (xPan >> 3);
            final int xShift = (xPan & 7);
            final int shiftAmt = 8 - xShift;

            int lineBase = base0 + (ySrc * bpl);

            for (int bx = 0; bx < bpl; bx++) {
                int srcBx = bx + xByteOfs;
                if (srcBx >= bpl) srcBx -= bpl; // wrap

                int srcBxN = (srcBx + 1 == bpl) ? 0 : (srcBx + 1);

                int oc = (lineBase + srcBx) & 0x3FFF;
                int on = (lineBase + srcBxN) & 0x3FFF;

                int p0c = vram[(0 * PLANE_SIZE) + oc] & 0xFF;
                int p1c = vram[(1 * PLANE_SIZE) + oc] & 0xFF;
                int p2c = vram[(2 * PLANE_SIZE) + oc] & 0xFF;
                int p3c = vram[(3 * PLANE_SIZE) + oc] & 0xFF;

                int p0n = vram[(0 * PLANE_SIZE) + on] & 0xFF;
                int p1n = vram[(1 * PLANE_SIZE) + on] & 0xFF;
                int p2n = vram[(2 * PLANE_SIZE) + on] & 0xFF;
                int p3n = vram[(3 * PLANE_SIZE) + on] & 0xFF;

                int w0 = (p0c << 8) | p0n;
                int w1 = (p1c << 8) | p1n;
                int w2 = (p2c << 8) | p2n;
                int w3 = (p3c << 8) | p3n;

                int b0 = (w0 >>> shiftAmt) & 0xFF;
                int b1 = (w1 >>> shiftAmt) & 0xFF;
                int b2 = (w2 >>> shiftAmt) & 0xFF;
                int b3 = (w3 >>> shiftAmt) & 0xFF;

                int packed = BITPACK[b0]
                        | (BITPACK[b1] << 1)
                        | (BITPACK[b2] << 2)
                        | (BITPACK[b3] << 3);

                int xBase = bx * 8;
                int p = packed;
                int x = xBase;

                for (int k = 0; k < 4; k++) {
                    long pair = pairArgb[p & 0xFF];
                    renderBuf[rowOfs + x] = (int) pair;
                    renderBuf[rowOfs + x + 1] = (int) (pair >>> 32);
                    x += 2;
                    p >>>= 8;
                }
            }
            return;
        }

        // mode 1: 2x horizontal scaling
        final int bpl = 40;
        final int base0 = fbBase & 0x3FFF;
        final int xByteOfs = (xPan >> 3);
        final int xShift = (xPan & 7);
        final int shiftAmt = 8 - xShift;

        int lineBase = base0 + (ySrc * bpl);

        for (int bx = 0; bx < bpl; bx++) {
            int srcBx = bx + xByteOfs;
            if (srcBx >= bpl) srcBx -= bpl;

            int srcBxN = (srcBx + 1 == bpl) ? 0 : (srcBx + 1);

            int oc = (lineBase + srcBx) & 0x3FFF;
            int on = (lineBase + srcBxN) & 0x3FFF;

            int p0c = vram[(0 * PLANE_SIZE) + oc] & 0xFF;
            int p1c = vram[(1 * PLANE_SIZE) + oc] & 0xFF;
            int p2c = vram[(2 * PLANE_SIZE) + oc] & 0xFF;
            int p3c = vram[(3 * PLANE_SIZE) + oc] & 0xFF;

            int p0n = vram[(0 * PLANE_SIZE) + on] & 0xFF;
            int p1n = vram[(1 * PLANE_SIZE) + on] & 0xFF;
            int p2n = vram[(2 * PLANE_SIZE) + on] & 0xFF;
            int p3n = vram[(3 * PLANE_SIZE) + on] & 0xFF;

            int w0 = (p0c << 8) | p0n;
            int w1 = (p1c << 8) | p1n;
            int w2 = (p2c << 8) | p2n;
            int w3 = (p3c << 8) | p3n;

            int b0 = (w0 >>> shiftAmt) & 0xFF;
            int b1 = (w1 >>> shiftAmt) & 0xFF;
            int b2 = (w2 >>> shiftAmt) & 0xFF;
            int b3 = (w3 >>> shiftAmt) & 0xFF;

            int packed = BITPACK[b0]
                    | (BITPACK[b1] << 1)
                    | (BITPACK[b2] << 2)
                    | (BITPACK[b3] << 3);

            int xBase = bx * 16;
            int p = packed;
            int x = xBase;

            for (int k = 0; k < 4; k++) {
                long pair = pairArgb[p & 0xFF];
                int c0 = (int) pair;
                int c1 = (int) (pair >>> 32);

                renderBuf[rowOfs + x] = c0; renderBuf[rowOfs + x + 1] = c0;
                renderBuf[rowOfs + x + 2] = c1; renderBuf[rowOfs + x + 3] = c1;

                x += 4;
                p >>>= 8;
            }
        }
    }


    // =====================================================================
    // Rendering
    // =====================================================================

    private void renderFrameIntoBackBuffer() {
        final boolean copperEnabled = frameCopperEnabled;

        if (!copperEnabled) {
            // Frame-wide (fast) path: no mid-frame register writes.
            boolean blinkPhaseOn = ((txCtrl & TX_CURSOR_BLINK) == 0) || (((frameCounter >> 4) & 1) == 0);
            boolean cursorEnabled = ((txCtrl & TX_CURSOR_EN) != 0) && blinkPhaseOn;

            boolean gfxDisabled = (ctrl & CTRL_GFX_DIS) != 0;

            boolean spritePersonality = (ovlMode & 0x01) != 0;
            boolean textActive = !spritePersonality && ((txCtrl & TX_EN) != 0);
            boolean transparentBg = ((txCtrl & TX_TRANSPARENT_BG) != 0);

            if (gfxDisabled) {
                Arrays.fill(renderBuf, palArgb[0]);
            } else {
                // Use LUT acceleration in modes 0/1.
                if (pairDirty) rebuildPairArgb();
                renderGraphicsModesIntoBackBuffer_FastBitplanes();
            }

            if (spritePersonality) {
                renderSpritesIntoBackBuffer();
            } else if (textActive) {
                renderTextIntoBackBuffer(transparentBg, cursorEnabled);
            }
        } else {
            // Copper path: render scanline-by-scanline so copper writes affect graphics + overlay.
            copperBeginFrame();

            final int W = config.width();
            final int H = config.height();

            for (int y = 0; y < H; y++) {
                copperApplyForScanline(y);

                boolean gfxDisabled = (ctrl & CTRL_GFX_DIS) != 0;
                int rowOfs = y * W;

                if (gfxDisabled) {
                    Arrays.fill(renderBuf, rowOfs, rowOfs + W, palArgb[0]);
                } else {
                    renderGraphicsScanline(y);
                }

                boolean spritePersonality = (ovlMode & 0x01) != 0;
                if (spritePersonality) {
                    renderSpritesScanline(y);
                } else {
                    boolean blinkPhaseOn = ((txCtrl & TX_CURSOR_BLINK) == 0) || (((frameCounter >> 4) & 1) == 0);
                    boolean cursorEnabled = ((txCtrl & TX_CURSOR_EN) != 0) && blinkPhaseOn;

                    boolean textActive = ((txCtrl & TX_EN) != 0);
                    if (textActive) {
                        boolean transparentBg = ((txCtrl & TX_TRANSPARENT_BG) != 0);
                        renderTextScanline(y, transparentBg, cursorEnabled);
                    }
                }
            }
        }

        // Publish renderBuf -> queuedBuf (triple buffer)
        publishRenderedFrame();
    }


    private void rebuildPairArgb() {
        for (int i = 0; i < 256; i++) {
            int left = i & 0x0F;
            int right = (i >>> 4) & 0x0F;
            long lo = palArgb[left] & 0xFFFFFFFFL;
            long hi = ((long) palArgb[right]) << 32;
            pairArgb[i] = hi | lo; // low32=left pixel, high32=right pixel
        }
        pairDirty = false;
    }

    // Full-frame renderer with LUT acceleration for bitplane modes (0/1).
    // Modes 2/3 use the straightforward loops.

    private void renderGraphicsModesIntoBackBuffer_FastBitplanes() {
        final int W = 640;
        final int srcH = 200;

        if (mode == 0 || mode == 1) {
            final int bpl = (mode == 0) ? 80 : 40;
            final int base0 = fbBase & 0x3FFF;
            final int xByteOfs = (xPan >> 3);
            final int xShift = (xPan & 7);
            final int shiftAmt = 8 - xShift;

            final int p0Base = 0 * PLANE_SIZE;
            final int p1Base = 1 * PLANE_SIZE;
            final int p2Base = 2 * PLANE_SIZE;
            final int p3Base = 3 * PLANE_SIZE;

            for (int y = 0; y < srcH; y++) {
                int outY0 = y << 1;
                int row0 = outY0 * W;
                int row1 = row0 + W;

                int lineBase = base0 + (y * bpl);

                for (int bx = 0; bx < bpl; bx++) {
                    int srcBx = bx + xByteOfs;
                    if (srcBx >= bpl) srcBx -= bpl;

                    int srcBxN = (srcBx + 1 == bpl) ? 0 : (srcBx + 1);

                    int oc = (lineBase + srcBx) & 0x3FFF;
                    int on = (lineBase + srcBxN) & 0x3FFF;

                    int p0c = vram[p0Base + oc] & 0xFF;
                    int p1c = vram[p1Base + oc] & 0xFF;
                    int p2c = vram[p2Base + oc] & 0xFF;
                    int p3c = vram[p3Base + oc] & 0xFF;

                    int p0n = vram[p0Base + on] & 0xFF;
                    int p1n = vram[p1Base + on] & 0xFF;
                    int p2n = vram[p2Base + on] & 0xFF;
                    int p3n = vram[p3Base + on] & 0xFF;

                    int w0 = (p0c << 8) | p0n;
                    int w1 = (p1c << 8) | p1n;
                    int w2 = (p2c << 8) | p2n;
                    int w3 = (p3c << 8) | p3n;

                    int b0 = (w0 >>> shiftAmt) & 0xFF;
                    int b1 = (w1 >>> shiftAmt) & 0xFF;
                    int b2 = (w2 >>> shiftAmt) & 0xFF;
                    int b3 = (w3 >>> shiftAmt) & 0xFF;

                    int packed = BITPACK[b0]
                            | (BITPACK[b1] << 1)
                            | (BITPACK[b2] << 2)
                            | (BITPACK[b3] << 3);

                    if (mode == 0) {
                        int x = bx * 8;
                        int p = packed;
                        for (int k = 0; k < 4; k++) {
                            long pair = pairArgb[p & 0xFF];
                            renderBuf[row0 + x] = (int) pair;
                            renderBuf[row0 + x + 1] = (int) (pair >>> 32);
                            x += 2;
                            p >>>= 8;
                        }
                    } else {
                        int x = bx * 16;
                        int p = packed;
                        for (int k = 0; k < 4; k++) {
                            long pair = pairArgb[p & 0xFF];
                            int c0 = (int) pair;
                            int c1 = (int) (pair >>> 32);

                            renderBuf[row0 + x] = c0;
                            renderBuf[row0 + x + 1] = c0;
                            renderBuf[row0 + x + 2] = c1;
                            renderBuf[row0 + x + 3] = c1;

                            x += 4;
                            p >>>= 8;
                        }
                    }
                }

                System.arraycopy(renderBuf, row0, renderBuf, row1, W);
            }
            return;
        }

        if (mode == 2) {
            final int bpl = 80; // 320/4
            final int base = fbBase & 0x3FFF;
            for (int y = 0; y < srcH; y++) {
                int outY0 = y << 1;
                int row0 = outY0 * W;
                int row1 = row0 + W;

                final int lineBase = base + (y * bpl);
                final int xOff = (xPan & 3);

                final int p0Base = 0 * PLANE_SIZE;
                final int p1Base = 1 * PLANE_SIZE;
                final int p2Base = 2 * PLANE_SIZE;
                final int p3Base = 3 * PLANE_SIZE;

                for (int byteX = 0; byteX < bpl; byteX++) {
                    int next = (byteX + 1 == bpl) ? 0 : (byteX + 1);

                    int ofs  = (lineBase + byteX) & 0x3FFF;
                    int ofsN = (lineBase + next) & 0x3FFF;

                    int a0 = vram[p0Base + ofs] & 0xFF;
                    int a1 = vram[p1Base + ofs] & 0xFF;
                    int a2 = vram[p2Base + ofs] & 0xFF;
                    int a3 = vram[p3Base + ofs] & 0xFF;

                    int b0 = vram[p0Base + ofsN] & 0xFF;
                    int b1 = vram[p1Base + ofsN] & 0xFF;
                    int b2 = vram[p2Base + ofsN] & 0xFF;

                    int p0, p1, p2, p3;
                    switch (xOff) {
                        case 0 -> { p0 = a0; p1 = a1; p2 = a2; p3 = a3; }
                        case 1 -> { p0 = a1; p1 = a2; p2 = a3; p3 = b0; }
                        case 2 -> { p0 = a2; p1 = a3; p2 = b0; p3 = b1; }
                        default -> { p0 = a3; p1 = b0; p2 = b1; p3 = b2; }
                    }

                    int out = row0 + (byteX << 3);
                    int c;
                    c = palArgb[p0]; renderBuf[out] = c; renderBuf[out + 1] = c; out += 2;
                    c = palArgb[p1]; renderBuf[out] = c; renderBuf[out + 1] = c; out += 2;
                    c = palArgb[p2]; renderBuf[out] = c; renderBuf[out + 1] = c; out += 2;
                    c = palArgb[p3]; renderBuf[out] = c; renderBuf[out + 1] = c;
                }

                System.arraycopy(renderBuf, row0, renderBuf, row1, W);
            }
            return;
        }

        final int bpl = 40; // 160/4
        final int base = fbBase & 0x3FFF;

        for (int y = 0; y < srcH; y++) {
            int outY0 = y << 1;
            int row0 = outY0 * W;
            int row1 = row0 + W;

            final int lineBase = base + (y * bpl);
            final int xOff = (xPan & 3);

            final int p0Base = 0 * PLANE_SIZE;
            final int p1Base = 1 * PLANE_SIZE;
            final int p2Base = 2 * PLANE_SIZE;
            final int p3Base = 3 * PLANE_SIZE;

            for (int byteX = 0; byteX < bpl; byteX++) {
                int next = (byteX + 1 == bpl) ? 0 : (byteX + 1);

                int ofs  = (lineBase + byteX) & 0x3FFF;
                int ofsN = (lineBase + next) & 0x3FFF;

                int a0 = vram[p0Base + ofs] & 0xFF;
                int a1 = vram[p1Base + ofs] & 0xFF;
                int a2 = vram[p2Base + ofs] & 0xFF;
                int a3 = vram[p3Base + ofs] & 0xFF;

                int b0 = vram[p0Base + ofsN] & 0xFF;
                int b1 = vram[p1Base + ofsN] & 0xFF;
                int b2 = vram[p2Base + ofsN] & 0xFF;

                int p0, p1, p2, p3;
                switch (xOff) {
                    case 0 -> { p0 = a0; p1 = a1; p2 = a2; p3 = a3; }
                    case 1 -> { p0 = a1; p1 = a2; p2 = a3; p3 = b0; }
                    case 2 -> { p0 = a2; p1 = a3; p2 = b0; p3 = b1; }
                    default -> { p0 = a3; p1 = b0; p2 = b1; p3 = b2; }
                }

                int out = row0 + (byteX << 4);
                int c;
                c = palArgb[p0]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c; out += 4;
                c = palArgb[p1]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c; out += 4;
                c = palArgb[p2]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c; out += 4;
                c = palArgb[p3]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c;
            }

            System.arraycopy(renderBuf, row0, renderBuf, row1, W);
        }
    }


    /**
     * Full-frame text rendering with hardware scroll:
     * - TX_ORIGIN (coarse row scroll, ring-buffer)
     * - TX_FINE_Y (fine vertical scroll)
     * - TX_FINE_X (fine horizontal scroll, with glyph-bit carry between adjacent cells)
     *
     * Cursor is SCREEN-relative (stable for BASIC): it ignores TX_FINE_Y/TX_FINE_X.
     */
    private void renderTextIntoBackBuffer(boolean transparentBg, boolean cursorEnabled) {
        final int W = 640;

        int fineX = txFineX & 0x07;

        for (int y = 0; y < TEXT_SCANLINES; y++) {
            int yAdj = y + (txFineY & 0x0F);
            if (yAdj >= TEXT_SCANLINES) yAdj -= TEXT_SCANLINES;

            int ty = (yAdj >> 4);     // 0..24
            int sub = (yAdj & 0x0F);  // 0..15

            int rowOfs = y * W;

            int tyScreen = (y >> 4);
            int subScreen = (y & 0x0F);

            boolean cursorRowActive = cursorEnabled
                    && (tyScreen == txCurY)
                    && (subScreen >= txCurStart)
                    && (subScreen <= txCurEnd);

            int cellBase = txOrigin + (ty * TEXT_COLS);
            cellBase %= TEXT_CELLS;
            if (cellBase < 0) cellBase += TEXT_CELLS;

            for (int tx = 0; tx < TEXT_COLS; tx++) {
                int cell = cellBase + tx;
                if (cell >= TEXT_CELLS) cell -= TEXT_CELLS;

                int cellOfs = (cell << 1);
                int ch   = textRam[cellOfs] & 0xFF;
                int attr = textRam[cellOfs + 1] & 0xFF;

                int fg = (attr & 0x0F);
                int bg = (attr >> 4) & 0x0F;

                int glyphCur = font8x16[(ch << 4) + sub] & 0xFF;
                boolean cursorCell = cursorRowActive && (tx == txCurX);

                int xBase = tx << 3;

                if (fineX == 0) {
                    // Fast path (no fine-X, opaque bg): emit 2 pixels at a time via TEXT_PAIR4 + pairArgb.
                    if (!transparentBg && !frameCopperEnabled) {
                        if (pairDirty) rebuildPairArgb();
                        int pairs = TEXT_PAIR4[(glyphCur << 8) | (attr & 0xFF)];
                        int out = rowOfs + xBase;
                        for (int k = 0; k < 4; k++) {
                            long pair = pairArgb[pairs & 0xFF];
                            renderBuf[out] = (int) pair;
                            renderBuf[out + 1] = (int) (pair >>> 32);
                            out += 2;
                            pairs >>>= 8;
                        }
                        if (cursorCell) {
                            for (int i = 0; i < 8; i++) {
                                int p = renderBuf[rowOfs + xBase + i];
                                renderBuf[rowOfs + xBase + i] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
                            }
                        }
                    } else {
                        int g = glyphCur;
                        for (int bit = 7; bit >= 0; bit--) {
                            boolean on = ((g >> bit) & 1) != 0;
                            int outIndex = rowOfs + xBase + (7 - bit);

                            if (on) {
                                renderBuf[outIndex] = palArgb[fg];
                            } else if (!transparentBg) {
                                renderBuf[outIndex] = palArgb[bg];
                            }

                            if (cursorCell) {
                                int p = renderBuf[outIndex];
                                renderBuf[outIndex] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
                            }
                        }
                    }
                } else {
                    // Need carry from the next cell for smooth X panning.
                    int chNext = 0x20, attrNext = txAttr;
                    if (tx + 1 < TEXT_COLS) {
                        int cellN = cell + 1;
                        if (cellN >= TEXT_CELLS) cellN -= TEXT_CELLS;
                        int ofsN = cellN << 1;
                        chNext = textRam[ofsN] & 0xFF;
                        attrNext = textRam[ofsN + 1] & 0xFF;
                    }
                    int fgN = (attrNext & 0x0F);
                    int bgN = (attrNext >> 4) & 0x0F;

                    int glyphNext = font8x16[(chNext << 4) + sub] & 0xFF;
                    int two = (glyphCur << 8) | glyphNext;

                    for (int i = 0; i < 8; i++) {
                        int s = i + fineX;          // 1..14
                        int shift = 15 - s;
                        boolean on = ((two >>> shift) & 1) != 0;

                        boolean fromNext = (s >= 8);
                        int fgUse = fromNext ? fgN : fg;
                        int bgUse = fromNext ? bgN : bg;

                        int outIndex = rowOfs + xBase + i;

                        if (on) {
                            renderBuf[outIndex] = palArgb[fgUse];
                        } else if (!transparentBg) {
                            renderBuf[outIndex] = palArgb[bgUse];
                        }

                        if (cursorCell) {
                            int p = renderBuf[outIndex];
                            renderBuf[outIndex] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
                        }
                    }
                }
            }
        }
    }

    private void requestBlit() {
        // Best practice: never call Platform.runLater() from the emulation/device thread.
        // Instead, mark dirty and let the JavaFX pulse (AnimationTimer) present the latest frame.
        fxDirty.set(true);
    }

    /**
     * Present the latest completed frame to the JavaFX {@link Canvas}.
     *
     * <p><b>Must</b> be called on the JavaFX Application Thread (e.g. from an {@code AnimationTimer}).</p>
     * <p>This is intentionally pull-based: the emulation thread only publishes frames and sets a dirty flag.</p>
     */
    public void fxPulse() {
        if (!fxDirty.getAndSet(false)) return;

        long t0 = 0;
        if (PROF) t0 = System.nanoTime();
        try {
            int[] pixels;
            synchronized (fbLock) {
                if (queuedBuf != null) {
                    int[] old = displayBuf;
                    displayBuf = queuedBuf;
                    queuedBuf = null;
                    freeBuf = old;
                }
                pixels = displayBuf;
            }

            writer.setPixels(
                    0, 0,
                    config.width(), config.height(),
                    ARGB_FORMAT,
                    pixels, 0, config.width()
            );
            gc.drawImage(image, 0, 0, config.canvasWidth(), config.canvasHeight());
        } finally {
            if (PROF) profFxBlitNanos += (System.nanoTime() - t0);
        }
    }

    private void dumpProf() {
        double frames = Math.max(1.0, (double) profFrames);
        double msBatch = (profBatchRenderNanos / 1e6) / frames;
        double msLive  = (profLiveScanlineNanos / 1e6) / frames;
        double msCop   = (profCopperDecodeNanos / 1e6) / frames;
        double msFx    = (profFxBlitNanos / 1e6) / frames;

        System.out.printf(
                "[VPU prof] frames=%d drop=%d  batchRender=%.3fms  liveRender=%.3fms  copperDecode=%.3fms  fxBlit=%.3fms%n",
                (long) frames,
                profFramesDropped,
                msBatch, msLive, msCop, msFx
        );

        // reset window
        profFrames = 0;
        profFramesDropped = 0;
        profBatchRenderNanos = 0;
        profLiveScanlineNanos = 0;
        profCopperDecodeNanos = 0;
        profFxBlitNanos = 0;
    }

    // =====================================================================
    // Copper
    // =====================================================================

    private static int u16(byte lo, byte hi) {
        return (lo & 0xFF) | ((hi & 0xFF) << 8);
    }

    private void copperBeginFrame() {
        // Always restart copper index each frame.
        copIdx = 0;

        // If the list wasn't modified, reuse the compiled/sorted schedule.
        if (!copDirty && copCompiled) {
            return;
        }

        long t0 = 0;
        if (PROF) t0 = System.nanoTime();

        copCount = 0;

        int len = copLen & 0xFFFF;
        if (len <= 0 || len > COPPER_SIZE) len = COPPER_SIZE;
        len = (len / COPPER_STRIDE) * COPPER_STRIDE;

        int n = 0;
        for (int pos = 0; pos + (COPPER_STRIDE - 1) < len && n < COP_MAX; pos += COPPER_STRIDE) {
            int scan = u16(copperRam[pos], copperRam[pos + 1]);
            int reg  = u16(copperRam[pos + 2], copperRam[pos + 3]);
            int vLo  = copperRam[pos + 4] & 0xFF;
            int vHi  = copperRam[pos + 5] & 0xFF;
            int flags = copperRam[pos + 6] & 0xFF;

            if ((flags & COP_FLAG_END) != 0) break;

            if (scan >= 0 && scan < config.height()) {
                copScanTmp[n]  = scan;
                copRegTmp[n]   = reg;
                copValLoTmp[n] = vLo;
                copValHiTmp[n] = vHi;
                copFlagsTmp[n] = flags;
                n++;
            }
        }

        if (n == 0) {
            copCount = 0;
            copDirty = false;
            copCompiled = true;
            if (PROF) profCopperDecodeNanos += (System.nanoTime() - t0);
            return;
        }

        int H = copCounts.length;
        Arrays.fill(copCounts, 0);
        for (int i = 0; i < n; i++) copCounts[copScanTmp[i]]++;

        int sum = 0;
        for (int s = 0; s < H; s++) {
            int c = copCounts[s];
            copCounts[s] = sum;
            sum += c;
        }

        for (int i = 0; i < n; i++) {
            int s = copScanTmp[i];
            int dst = copCounts[s]++;
            copScan[dst]  = s;
            copReg[dst]   = copRegTmp[i];
            copValLo[dst] = copValLoTmp[i];
            copValHi[dst] = copValHiTmp[i];
            copFlags[dst] = copFlagsTmp[i];
        }

        copCount = n;
        copDirty = false;
        copCompiled = true;
        if (PROF) profCopperDecodeNanos += (System.nanoTime() - t0);
    }

    private void copperApplyForScanline(int y) {
        while (copIdx < copCount && copScan[copIdx] == y) {
            int reg = copReg[copIdx] & 0x3FFF;
            int flags = copFlags[copIdx];

            int vLo = copValLo[copIdx] & 0xFF;
            int vHi = copValHi[copIdx] & 0xFF;

            if ((flags & COP_FLAG_WRITE16) != 0) {
                writeMmio(reg, (byte) vLo);
                writeMmio((reg + 1) & 0x3FFF, (byte) vHi);
            } else {
                writeMmio(reg, (byte) vLo);
            }

            copIdx++;
        }
    }

    // =====================================================================
    // Copper scanline renderers (palette can change mid-frame)
    // =====================================================================

    private void renderGraphicsScanline(int yOut) {
        final int W = 640;
        final int ySrc = (yOut >> 1);
        final int rowOfs = yOut * W;

        switch (mode) {
            case 0 -> {
                final int bpl = 80;
                final int base0 = fbBase & 0x3FFF;
                final int xByteOfs = (xPan >> 3);
                final int xShift = (xPan & 7);
                final int shiftAmt = 8 - xShift;

                int lineBase = base0 + (ySrc * bpl);

                for (int bx = 0; bx < bpl; bx++) {
                    int srcBx = bx + xByteOfs;
                    if (srcBx >= bpl) srcBx -= bpl;

                    int srcBxN = (srcBx + 1 == bpl) ? 0 : (srcBx + 1);

                    int oc = (lineBase + srcBx) & 0x3FFF;
                    int on = (lineBase + srcBxN) & 0x3FFF;

                    int p0c = vram[(0 * PLANE_SIZE) + oc] & 0xFF;
                    int p1c = vram[(1 * PLANE_SIZE) + oc] & 0xFF;
                    int p2c = vram[(2 * PLANE_SIZE) + oc] & 0xFF;
                    int p3c = vram[(3 * PLANE_SIZE) + oc] & 0xFF;

                    int p0n = vram[(0 * PLANE_SIZE) + on] & 0xFF;
                    int p1n = vram[(1 * PLANE_SIZE) + on] & 0xFF;
                    int p2n = vram[(2 * PLANE_SIZE) + on] & 0xFF;
                    int p3n = vram[(3 * PLANE_SIZE) + on] & 0xFF;

                    int w0 = (p0c << 8) | p0n;
                    int w1 = (p1c << 8) | p1n;
                    int w2 = (p2c << 8) | p2n;
                    int w3 = (p3c << 8) | p3n;

                    int b0 = (w0 >>> shiftAmt) & 0xFF;
                    int b1 = (w1 >>> shiftAmt) & 0xFF;
                    int b2 = (w2 >>> shiftAmt) & 0xFF;
                    int b3 = (w3 >>> shiftAmt) & 0xFF;

                    int xBase = bx * 8;
                    for (int i = 0; i < 8; i++) {
                        int bit = 7 - i;
                        int idx = ((b0 >>> bit) & 1)
                                | (((b1 >>> bit) & 1) << 1)
                                | (((b2 >>> bit) & 1) << 2)
                                | (((b3 >>> bit) & 1) << 3);
                        renderBuf[rowOfs + xBase + i] = palArgb[idx];
                    }
                }
            }
            case 1 -> {
                final int bpl = 40;
                final int base0 = fbBase & 0x3FFF;
                final int xByteOfs = (xPan >> 3);
                final int xShift = (xPan & 7);
                final int shiftAmt = 8 - xShift;

                int lineBase = base0 + (ySrc * bpl);

                for (int bx = 0; bx < bpl; bx++) {
                    int srcBx = bx + xByteOfs;
                    if (srcBx >= bpl) srcBx -= bpl;

                    int srcBxN = (srcBx + 1 == bpl) ? 0 : (srcBx + 1);

                    int oc = (lineBase + srcBx) & 0x3FFF;
                    int on = (lineBase + srcBxN) & 0x3FFF;

                    int p0c = vram[(0 * PLANE_SIZE) + oc] & 0xFF;
                    int p1c = vram[(1 * PLANE_SIZE) + oc] & 0xFF;
                    int p2c = vram[(2 * PLANE_SIZE) + oc] & 0xFF;
                    int p3c = vram[(3 * PLANE_SIZE) + oc] & 0xFF;

                    int p0n = vram[(0 * PLANE_SIZE) + on] & 0xFF;
                    int p1n = vram[(1 * PLANE_SIZE) + on] & 0xFF;
                    int p2n = vram[(2 * PLANE_SIZE) + on] & 0xFF;
                    int p3n = vram[(3 * PLANE_SIZE) + on] & 0xFF;

                    int w0 = (p0c << 8) | p0n;
                    int w1 = (p1c << 8) | p1n;
                    int w2 = (p2c << 8) | p2n;
                    int w3 = (p3c << 8) | p3n;

                    int b0 = (w0 >>> shiftAmt) & 0xFF;
                    int b1 = (w1 >>> shiftAmt) & 0xFF;
                    int b2 = (w2 >>> shiftAmt) & 0xFF;
                    int b3 = (w3 >>> shiftAmt) & 0xFF;

                    int xBase = bx * 16;
                    for (int i = 0; i < 8; i++) {
                        int bit = 7 - i;
                        int idx = ((b0 >>> bit) & 1)
                                | (((b1 >>> bit) & 1) << 1)
                                | (((b2 >>> bit) & 1) << 2)
                                | (((b3 >>> bit) & 1) << 3);
                        int argb = palArgb[idx];

                        int outX = xBase + (i << 1);
                        renderBuf[rowOfs + outX] = argb;
                        renderBuf[rowOfs + outX + 1] = argb;
                    }
                }
            }
            case 2 -> {
                // Mode 2 (320x200 8bpp Mode-X): decode 4 pixels at a time.
                // This reduces VRAM reads from 320 -> 80 per scanline per plane.
                final int bpl = 80;                 // 320 / 4
                final int base = fbBase & 0x3FFF;
                final int lineBase = base + (ySrc * bpl);
                final int xOff = (xPan & 3);

                final int p0Base = 0 * PLANE_SIZE;
                final int p1Base = 1 * PLANE_SIZE;
                final int p2Base = 2 * PLANE_SIZE;
                final int p3Base = 3 * PLANE_SIZE;

                for (int byteX = 0; byteX < bpl; byteX++) {
                    int next = (byteX + 1 == bpl) ? 0 : (byteX + 1);

                    int ofs  = (lineBase + byteX) & 0x3FFF;
                    int ofsN = (lineBase + next) & 0x3FFF;

                    int a0 = vram[p0Base + ofs] & 0xFF;
                    int a1 = vram[p1Base + ofs] & 0xFF;
                    int a2 = vram[p2Base + ofs] & 0xFF;
                    int a3 = vram[p3Base + ofs] & 0xFF;

                    int b0 = vram[p0Base + ofsN] & 0xFF;
                    int b1 = vram[p1Base + ofsN] & 0xFF;
                    int b2 = vram[p2Base + ofsN] & 0xFF;

                    int p0, p1, p2, p3;
                    switch (xOff) {
                        case 0 -> { p0 = a0; p1 = a1; p2 = a2; p3 = a3; }
                        case 1 -> { p0 = a1; p1 = a2; p2 = a3; p3 = b0; }
                        case 2 -> { p0 = a2; p1 = a3; p2 = b0; p3 = b1; }
                        default -> { p0 = a3; p1 = b0; p2 = b1; p3 = b2; }
                    }

                    int out = rowOfs + (byteX << 3); // byteX*4 src pixels -> *2 scale => *8

                    int c;
                    c = palArgb[p0]; renderBuf[out] = c; renderBuf[out + 1] = c; out += 2;
                    c = palArgb[p1]; renderBuf[out] = c; renderBuf[out + 1] = c; out += 2;
                    c = palArgb[p2]; renderBuf[out] = c; renderBuf[out + 1] = c; out += 2;
                    c = palArgb[p3]; renderBuf[out] = c; renderBuf[out + 1] = c;
                }
            }
            default -> {
                // Mode 3 (160x200 8bpp Mode-X): decode 4 pixels at a time.
                final int bpl = 40;                 // 160 / 4
                final int base = fbBase & 0x3FFF;
                final int lineBase = base + (ySrc * bpl);
                final int xOff = (xPan & 3);

                final int p0Base = 0 * PLANE_SIZE;
                final int p1Base = 1 * PLANE_SIZE;
                final int p2Base = 2 * PLANE_SIZE;
                final int p3Base = 3 * PLANE_SIZE;

                for (int byteX = 0; byteX < bpl; byteX++) {
                    int next = (byteX + 1 == bpl) ? 0 : (byteX + 1);

                    int ofs  = (lineBase + byteX) & 0x3FFF;
                    int ofsN = (lineBase + next) & 0x3FFF;

                    int a0 = vram[p0Base + ofs] & 0xFF;
                    int a1 = vram[p1Base + ofs] & 0xFF;
                    int a2 = vram[p2Base + ofs] & 0xFF;
                    int a3 = vram[p3Base + ofs] & 0xFF;

                    int b0 = vram[p0Base + ofsN] & 0xFF;
                    int b1 = vram[p1Base + ofsN] & 0xFF;
                    int b2 = vram[p2Base + ofsN] & 0xFF;

                    int p0, p1, p2, p3;
                    switch (xOff) {
                        case 0 -> { p0 = a0; p1 = a1; p2 = a2; p3 = a3; }
                        case 1 -> { p0 = a1; p1 = a2; p2 = a3; p3 = b0; }
                        case 2 -> { p0 = a2; p1 = a3; p2 = b0; p3 = b1; }
                        default -> { p0 = a3; p1 = b0; p2 = b1; p3 = b2; }
                    }

                    int out = rowOfs + (byteX << 4); // byteX*4 src pixels -> *4 scale => *16

                    int c;
                    c = palArgb[p0]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c; out += 4;
                    c = palArgb[p1]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c; out += 4;
                    c = palArgb[p2]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c; out += 4;
                    c = palArgb[p3]; renderBuf[out] = c; renderBuf[out+1] = c; renderBuf[out+2] = c; renderBuf[out+3] = c;
                }
            }
        }
    }

    private void renderTextScanline(int y, boolean transparentBg, boolean cursorEnabled) {
        final int W = 640;

        int fineX = txFineX & 0x07;

        int yAdj = y + (txFineY & 0x0F);
        if (yAdj >= TEXT_SCANLINES) yAdj -= TEXT_SCANLINES;

        int ty = (yAdj >> 4);
        int sub = (yAdj & 0x0F);

        int rowOfs = y * W;

        int tyScreen = (y >> 4);
        int subScreen = (y & 0x0F);

        boolean cursorRowActive = cursorEnabled
                && (tyScreen == txCurY)
                && (subScreen >= txCurStart)
                && (subScreen <= txCurEnd);

        int cellBase = txOrigin + (ty * TEXT_COLS);
        cellBase %= TEXT_CELLS;
        if (cellBase < 0) cellBase += TEXT_CELLS;

        for (int tx = 0; tx < TEXT_COLS; tx++) {
            int cell = cellBase + tx;
            if (cell >= TEXT_CELLS) cell -= TEXT_CELLS;

            int cellOfs = cell << 1;
            int ch = textRam[cellOfs] & 0xFF;
            int attr = textRam[cellOfs + 1] & 0xFF;

            int fg = (attr & 0x0F);
            int bg = (attr >> 4) & 0x0F;

            int glyphCur = font8x16[(ch << 4) + sub] & 0xFF;
            boolean cursorCell = cursorRowActive && (tx == txCurX);

            int xBase = tx << 3;

            if (fineX == 0) {
                // Fast path: no copper, opaque bg, no fine-X. Emit 2 pixels at a time via TEXT_PAIR4 + pairArgb.
                if (!transparentBg && !frameCopperEnabled) {
                    if (pairDirty) rebuildPairArgb();
                    int pairs = TEXT_PAIR4[(glyphCur << 8) | (attr & 0xFF)];
                    int out = rowOfs + xBase;
                    for (int k = 0; k < 4; k++) {
                        long pair = pairArgb[pairs & 0xFF];
                        renderBuf[out] = (int) pair;
                        renderBuf[out + 1] = (int) (pair >>> 32);
                        out += 2;
                        pairs >>>= 8;
                    }
                    if (cursorCell) {
                        for (int i = 0; i < 8; i++) {
                            int p = renderBuf[rowOfs + xBase + i];
                            renderBuf[rowOfs + xBase + i] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
                        }
                    }
                } else {
                    for (int bit = 7; bit >= 0; bit--) {
                        boolean on = ((glyphCur >>> bit) & 1) != 0;
                        int outIndex = rowOfs + xBase + (7 - bit);

                        if (on) renderBuf[outIndex] = palArgb[fg];
                        else if (!transparentBg) renderBuf[outIndex] = palArgb[bg];

                        if (cursorCell) {
                            int p = renderBuf[outIndex];
                            renderBuf[outIndex] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
                        }
                    }
                }
            } else {
                int chNext = 0x20, attrNext = txAttr;
                if (tx + 1 < TEXT_COLS) {
                    int cellN = cell + 1;
                    if (cellN >= TEXT_CELLS) cellN -= TEXT_CELLS;
                    int ofsN = cellN << 1;
                    chNext = textRam[ofsN] & 0xFF;
                    attrNext = textRam[ofsN + 1] & 0xFF;
                }
                int fgN = (attrNext & 0x0F);
                int bgN = (attrNext >> 4) & 0x0F;

                int glyphNext = font8x16[(chNext << 4) + sub] & 0xFF;
                int two = (glyphCur << 8) | glyphNext;

                for (int i = 0; i < 8; i++) {
                    int s = i + fineX;
                    int shift = 15 - s;
                    boolean on = ((two >>> shift) & 1) != 0;

                    boolean fromNext = (s >= 8);
                    int fgUse = fromNext ? fgN : fg;
                    int bgUse = fromNext ? bgN : bg;

                    int outIndex = rowOfs + xBase + i;

                    if (on) renderBuf[outIndex] = palArgb[fgUse];
                    else if (!transparentBg) renderBuf[outIndex] = palArgb[bgUse];

                    if (cursorCell) {
                        int p = renderBuf[outIndex];
                        renderBuf[outIndex] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
                    }
                }
            }
        }
    }

    // =====================================================================
    // Sprites (overlay sprite personality)
    // =====================================================================

    /**
     * Sprite OAM entry format (8 bytes per sprite):
     * <pre>
     * [0] Y low
     * [1] X low
     * [2] TILE (0..255) — 8x8 tile index (tile size = 32 bytes)
     * [3] ATTR:
     *     bit0  EN
     *     bit1  HFLIP
     *     bit2  VFLIP
     *     bit3  BEHIND (draw only on background color 0)
     *     bits4-7 PAL (0..15) => palette base = PAL<<4
     * [4] XYHI:
     *     bits0-1 X[9:8]
     *     bits2-3 Y[9:8]
     * [5..7] reserved
     * </pre>
     *
     * Tile RAM format (sprite personality):
     * <ul>
     *   <li>8x8, 4bpp packed (2 pixels per byte)</li>
     *   <li>High nibble = left pixel, low nibble = right pixel</li>
     *   <li>Nibble 0 is transparent</li>
     *   <li>Final CLUT index = (PAL&lt;&lt;4) | nibble</li>
     * </ul>
     */
    private void renderSpritesIntoBackBuffer() {
        final int H = config.height();
        for (int y = 0; y < H; y++) {
            renderSpritesScanline(y);
        }
    }

    /**
     * Render sprites for an output scanline.
     *
     * <p><b>Coordinate space</b>: sprite X/Y are expressed in <b>source pixels</b> for the current graphics mode,
     * not in output (scaled) pixels.
     *
     * <ul>
     *   <li>MODE 0: source = 640x200 (scaleX=1, double-scan Y)</li>
     *   <li>MODE 1/2: source = 320x200 (scaleX=2, double-scan Y)</li>
     *   <li>MODE 3: source = 160x200 (scaleX=4, double-scan Y)</li>
     * </ul>
     * This makes sprites track the active mode's scaling, so they stay visually consistent with the underlay.
     */
    private void renderSpritesScanline(int yOut) {
        // Sprites exist only in sprite/tile overlay personality.
        if ((ovlMode & 0x01) == 0) return;

        final int W = config.width();
        final int rowOfs = yOut * W;

        // Output is always double-scanned in Y (200 -> 400), so both output lines map to the same source line.
        final int ySrc = (yOut >> 1);
        if (ySrc < 0 || ySrc >= 200) return;

        // Horizontal scaling factor (source pixels -> output pixels)
        final int scaleX = switch (mode) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> 4; // mode 3
        };

        // Sprite RAM layout:
        //   OAM: 128 sprites, 8 bytes each @ OAM_BASE
        //     +0 Y low
        //     +1 X low
        //     +2 TILE index
        //     +3 ATTR: bit0 EN, bit1 HFLIP, bit2 VFLIP, bits4-7 PALBANK
        //     +4 XYHI: bits0-1=X[9:8], bits2-3=Y[9:8]
        //   Tile RAM: 8x8, 4bpp, 32 bytes/tile (row=4 bytes, 2 pixels/byte)

        for (int i = 0; i < 128; i++) {
            int o = i * 8;
            int yLo = oamRam[o + 0] & 0xFF;
            int xLo = oamRam[o + 1] & 0xFF;
            int tile = oamRam[o + 2] & 0xFF;
            int attr = oamRam[o + 3] & 0xFF;
            int xyhi = oamRam[o + 4] & 0xFF;

            if ((attr & 0x01) == 0) continue; // not enabled

            int x = xLo | ((xyhi & 0x03) << 8);         // source-space X
            int y = yLo | ((xyhi & 0x0C) << 6);         // source-space Y

            int dy = ySrc - y;
            if (dy < 0 || dy >= 8) continue;
            boolean hflip = (attr & 0x02) != 0;
            boolean vflip = (attr & 0x04) != 0;
            boolean behind = (attr & 0x08) != 0;
            final int bgArgb = palArgb[0];
            if (vflip) dy = 7 - dy;
            int palBase = (attr >>> 4) << 4;            // 16-color bank
            int tileBase = tile * 32;
            int rowBase = tileBase + (dy * 4);

            // Quick clip: convert sprite left edge to output pixels.
            int xOut0 = x * scaleX;
            if (xOut0 >= W || (xOut0 + (8 * scaleX) - 1) < 0) continue;

            for (int tx = 0; tx < 8; tx++) {
                int px = hflip ? (7 - tx) : tx;
                int b = tileRam[rowBase + (px >> 1)] & 0xFF;
                int pix = ((px & 1) == 0) ? ((b >>> 4) & 0x0F) : (b & 0x0F);
                if (pix == 0) continue; // transparent

                int argb = palArgb[palBase | pix];

                int outX = (x + tx) * scaleX;
                if (outX < 0) continue;
                if (outX >= W) break;

                // Write horizontally scaled pixels.

                switch (scaleX) {
                    case 1 -> {
                        int dst = rowOfs + outX;
                        if (!behind || renderBuf[dst] == bgArgb) renderBuf[dst] = argb;
                    }
                    case 2 -> {
                        int p = rowOfs + outX;
                        int end = rowOfs + W;
                        if (p < end) {
                            if (!behind || renderBuf[p] == bgArgb) renderBuf[p] = argb;
                            if (p + 1 < end) {
                                if (!behind || renderBuf[p + 1] == bgArgb) renderBuf[p + 1] = argb;
                            }
                        }
                    }
                    default -> {
                        int p = rowOfs + outX;
                        int end = Math.min(rowOfs + W, p + 4);
                        for (int k = p; k < end; k++) {
                            if (!behind || renderBuf[k] == bgArgb) renderBuf[k] = argb;
                        }
                    }
                }

            }
        }
    }

    // =====================================================================
    // Text helpers (TX_PORT / TX_CMD)
    // =====================================================================

    private void txCommand(int cmd) {
        int c = cmd & 0xFF;

        if ((c & TXCMD_HOME) != 0) {
            txCurX = 0;
            txCurY = 0;
        }
        if ((c & TXCMD_CLR_EOL) != 0) {
            txClearEol();
        }
        if ((c & TXCMD_CLR_LINE) != 0) {
            txClearLine(txCurY);
        }
        if ((c & TXCMD_CLR_SCREEN) != 0) {
            txClearScreen();
        }
        if ((c & TXCMD_SCROLL_UP) != 0) {
            txScrollUpOneRow();
        }
    }

    private void txPortWrite(int v) {
        int ch = v & 0xFF;

        switch (ch) {
            case 0x0A -> txNewline();           // \n
            case 0x0D -> txCurX = 0;            // \r
            case 0x08 -> {                      // \b
                if (txCurX > 0) txCurX--;
            }
            case 0x09 -> {                      // \t (tab stops every 8)
                txCurX = (txCurX + 8) & ~7;
                if (txCurX >= TEXT_COLS) txNewline();
            }
            default -> {
                txPutCharAtCursor(ch, txAttr);
                txCurX++;
                if (txCurX >= TEXT_COLS) {
                    txNewline();
                }
            }
        }
    }

    private void txPutCharAtCursor(int ch, int attr) {
        int cell = txOrigin + (txCurY * TEXT_COLS) + txCurX;
        cell %= TEXT_CELLS;
        if (cell < 0) cell += TEXT_CELLS;

        int ofs = cell << 1;
        textRam[ofs] = (byte) (ch & 0xFF);
        textRam[ofs + 1] = (byte) (attr & 0xFF);
    }

    private void txNewline() {
        txCurX = 0;
        txCurY++;
        if (txCurY >= TEXT_ROWS) {
            txCurY = TEXT_ROWS - 1;
            txScrollUpOneRow();
        }
    }

    private void txScrollUpOneRow() {
        txOrigin += TEXT_COLS;
        normalizeTxOrigin();
        txClearLine(TEXT_ROWS - 1);
    }

    private void txClearEol() {
        int y = txCurY;
        int x0 = txCurX;

        int cell = txOrigin + (y * TEXT_COLS) + x0;
        cell %= TEXT_CELLS;
        if (cell < 0) cell += TEXT_CELLS;

        for (int x = x0; x < TEXT_COLS; x++) {
            int ofs = cell << 1;
            textRam[ofs] = 0x20;
            textRam[ofs + 1] = (byte) (txAttr & 0xFF);

            cell++;
            if (cell == TEXT_CELLS) cell = 0;
        }
    }

    private void txClearLine(int y) {
        int cell = txOrigin + (y * TEXT_COLS);
        cell %= TEXT_CELLS;
        if (cell < 0) cell += TEXT_CELLS;

        for (int x = 0; x < TEXT_COLS; x++) {
            int ofs = cell << 1;
            textRam[ofs] = 0x20;
            textRam[ofs + 1] = (byte) (txAttr & 0xFF);

            cell++;
            if (cell == TEXT_CELLS) cell = 0;
        }
    }

    private void txClearScreen() {
        for (int cell = 0; cell < TEXT_CELLS; cell++) {
            int ofs = cell << 1;
            textRam[ofs] = 0x20;
            textRam[ofs + 1] = (byte) (txAttr & 0xFF);
        }
        txCurX = 0;
        txCurY = 0;
    }

    // =====================================================================
    // Defaults (palette/font/text)
    // =====================================================================

    private void initDefaultTextRam() {
        for (int i = 0; i < TEXT_CELLS; i++) {
            int ofs = i << 1;
            textRam[ofs]     = 0x20;
            textRam[ofs + 1] = 0x07;
        }
    }

    // IBM VGA / CP437 8x16 font (base64).
    private static final String DEFAULT_FONT_8X16_B64 =
            "AAAAAAAAAAAAAAAAAAAAAAAAfoGlgYG9mYGBfgAAAAAAAH7/2///w+f//34AAAAAAAAAAGz+/v7+" +
                    "fDgQAAAAAAAAAAAQOHz+fDgQAAAAAAAAAAAYPDzn5+cYGDwAAAAAAAAAGDx+//9+GBg8AAAAAAAA" +
                    "AAAAABg8PBgAAAAAAAD////////nw8Pn////////AAAAAAA8ZkJCZjwAAAAAAP//////w5m9vZnD" +
                    "//////8AAB4OGjJ4zMzMzHgAAAAAAAA8ZmZmZjwYfhgYAAAAAAAAPzM/MDAwMHDw4AAAAAAAAH9j" +
                    "f2NjY2Nn5+bAAAAAAAAAGBjbPOc82xgYAAAAAACAwODw+P748ODAgAAAAAAAAgYOHj7+Ph4OBgIA" +
                    "AAAAAAAYPH4YGBh+PBgAAAAAAAAAZmZmZmZmZgBmZgAAAAAAAH/b29t7GxsbGxsAAAAAAHzGYDhs" +
                    "xsZsOAzGfAAAAAAAAAAAAAAA/v7+/gAAAAAAABg8fhgYGH48GH4AAAAAAAAYPH4YGBgYGBgYAAAA" +
                    "AAAAGBgYGBgYGH48GAAAAAAAAAAAABgM/gwYAAAAAAAAAAAAAAAwYP5gMAAAAAAAAAAAAAAAAMDA" +
                    "wP4AAAAAAAAAAAAAAChs/mwoAAAAAAAAAAAAABA4OHx8/v4AAAAAAAAAAAD+/nx8ODgQAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAYPDw8GBgYABgYAAAAAABmZmYkAAAAAAAAAAAAAAAAAABsbP5sbGz+" +
                    "bGwAAAAAGBh8xsLAfAYGhsZ8GBgAAAAAAADCxgwYMGDGhgAAAAAAADhsbDh23MzMzHYAAAAAADAw" +
                    "MGAAAAAAAAAAAAAAAAAADBgwMDAwMDAYDAAAAAAAADAYDAwMDAwMGDAAAAAAAAAAAABmPP88ZgAA" +
                    "AAAAAAAAAAAAGBh+GBgAAAAAAAAAAAAAAAAAAAAYGBgwAAAAAAAAAAAAAP4AAAAAAAAAAAAAAAAA" +
                    "AAAAAAAYGAAAAAAAAAAAAgYMGDBgwIAAAAAAAAA4bMbG1tbGxmw4AAAAAAAAGDh4GBgYGBgYfgAA" +
                    "AAAAAHzGBgwYMGDAxv4AAAAAAAB8xgYGPAYGBsZ8AAAAAAAADBw8bMz+DAwMHgAAAAAAAP7AwMD8" +
                    "BgYGxnwAAAAAAAA4YMDA/MbGxsZ8AAAAAAAA/sYGBgwYMDAwMAAAAAAAAHzGxsZ8xsbGxnwAAAAA" +
                    "AAB8xsbGfgYGBgx4AAAAAAAAAAAYGAAAABgYAAAAAAAAAAAAGBgAAAAYGDAAAAAAAAAABgwYMGAw" +
                    "GAwGAAAAAAAAAAAAfgAAfgAAAAAAAAAAAABgMBgMBgwYMGAAAAAAAAB8xsYMGBgYABgYAAAAAAAA" +
                    "AHzGxt7e3tzAfAAAAAAAABA4bMbG/sbGxsYAAAAAAAD8ZmZmfGZmZmb8AAAAAAAAPGbCwMDAwMJm" +
                    "PAAAAAAAAPhsZmZmZmZmbPgAAAAAAAD+ZmJoeGhgYmb+AAAAAAAA/mZiaHhoYGBg8AAAAAAAADxm" +
                    "wsDA3sbGZjoAAAAAAADGxsbG/sbGxsbGAAAAAAAAPBgYGBgYGBgYPAAAAAAAAB4MDAwMDMzMzHgA" +
                    "AAAAAADmZmZseHhsZmbmAAAAAAAA8GBgYGBgYGJm/gAAAAAAAMbu/v7WxsbGxsYAAAAAAADG5vb+" +
                    "3s7GxsbGAAAAAAAAfMbGxsbGxsbGfAAAAAAAAPxmZmZ8YGBgYPAAAAAAAAB8xsbGxsbG1t58DA4A" +
                    "AAAA/GZmZnxsZmZm5gAAAAAAAHzGxmA4DAbGxnwAAAAAAAB+floYGBgYGBg8AAAAAAAAxsbGxsbG" +
                    "xsbGfAAAAAAAAMbGxsbGxsZsOBAAAAAAAADGxsbG1tbW/u5sAAAAAAAAxsZsfDg4fGzGxgAAAAAA" +
                    "AGZmZmY8GBgYGDwAAAAAAAD+xoYMGDBgwsb+AAAAAAAAPDAwMDAwMDAwPAAAAAAAAACAwOBwOBwO" +
                    "BgIAAAAAAAA8DAwMDAwMDAw8AAAAABA4bMYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/wAAMDAY" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAeAx8zMzMdgAAAAAAAOBgYHhsZmZmZnwAAAAAAAAAAAB8xsDAwMZ8" +
                    "AAAAAAAAHAwMPGzMzMzMdgAAAAAAAAAAAHzG/sDAxnwAAAAAAAA4bGRg8GBgYGDwAAAAAAAAAAAA" +
                    "dszMzMzMfAzMeAAAAOBgYGx2ZmZmZuYAAAAAAAAYGAA4GBgYGBg8AAAAAAAABgYADgYGBgYGBmZm" +
                    "PAAAAOBgYGZseHhsZuYAAAAAAAA4GBgYGBgYGBg8AAAAAAAAAAAA7P7W1tbWxgAAAAAAAAAAANxm" +
                    "ZmZmZmYAAAAAAAAAAAB8xsbGxsZ8AAAAAAAAAAAA3GZmZmZmfGBg8AAAAAAAAHbMzMzMzHwMDB4A" +
                    "AAAAAADcdmZgYGDwAAAAAAAAAAAAfMZgOAzGfAAAAAAAABAwMPwwMDAwNhwAAAAAAAAAAADMzMzM" +
                    "zMx2AAAAAAAAAAAAZmZmZmY8GAAAAAAAAAAAAMbG1tbW/mwAAAAAAAAAAADGbDg4OGzGAAAAAAAA" +
                    "AAAAxsbGxsbGfgYM+AAAAAAAAP7MGDBgxv4AAAAAAAAOGBgYcBgYGBgOAAAAAAAAGBgYGAAYGBgY" +
                    "GAAAAAAAAHAYGBgOGBgYGHAAAAAAAAB23AAAAAAAAAAAAAAAAAAAAAAQOGzGxsb+AAAAAAAAADxm" +
                    "wsDAwMJmPAwGfAAAAADMAADMzMzMzMx2AAAAAAAMGDAAfMb+wMDGfAAAAAAAEDhsAHgMfMzMzHYA" +
                    "AAAAAADMAAB4DHzMzMx2AAAAAABgMBgAeAx8zMzMdgAAAAAAOGw4AHgMfMzMzHYAAAAAAAAAADxm" +
                    "YGBmPAwGPAAAAAAQOGwAfMb+wMDGfAAAAAAAAMYAAHzG/sDAxnwAAAAAAGAwGAB8xv7AwMZ8AAAA" +
                    "AAAAZgAAOBgYGBgYPAAAAAAAGDxmADgYGBgYGDwAAAAAAGAwGAA4GBgYGBg8AAAAAADGABA4bMbG" +
                    "/sbGxgAAAAA4bDgAOGzGxv7GxsYAAAAAGDBgAP5mYHxgYGb+AAAAAAAAAAAAzHY2ftjYbgAAAAAA" +
                    "AD5szMz+zMzMzM4AAAAAABA4bAB8xsbGxsZ8AAAAAAAAxgAAfMbGxsbGfAAAAAAAYDAYAHzGxsbG" +
                    "xnwAAAAAADB4zADMzMzMzMx2AAAAAABgMBgAzMzMzMzMdgAAAAAAAMYAAMbGxsbGxn4GDHgAAMYA" +
                    "fMbGxsbGxsZ8AAAAAADGAMbGxsbGxsbGfAAAAAAAGBg8ZmBgYGY8GBgAAAAAADhsZGDwYGBgYOb8" +
                    "AAAAAAAAZmY8GH4YfhgYGAAAAAAA+MzM+MTM3szMzMYAAAAAAA4bGBgYfhgYGBgY2HAAAAAYMGAA" +
                    "eAx8zMzMdgAAAAAADBgwADgYGBgYGDwAAAAAABgwYAB8xsbGxsZ8AAAAAAAYMGAAzMzMzMzMdgAA" +
                    "AAAAAHbcANxmZmZmZmYAAAAAdtwAxub2/t7OxsbGAAAAAAA8bGw+AH4AAAAAAAAAAAAAOGxsOAB8" +
                    "AAAAAAAAAAAAAAAwMAAwMGDAxsZ8AAAAAAAAAAAAAP7AwMDAAAAAAAAAAAAAAAD+BgYGBgAAAAAA" +
                    "AMDAwsbMGDBg3IYMGD4AAADAwMLGzBgwZs6ePgYGAAAAABgYABgYGDw8PBgAAAAAAAAAAAA2bNhs" +
                    "NgAAAAAAAAAAAAAA2Gw2bNgAAAAAAAARRBFEEUQRRBFEEUQRRBFEVapVqlWqVapVqlWqVapVqt13" +
                    "3Xfdd9133Xfdd9133XcYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGPgYGBgYGBgYGBgYGBgY+Bj4GBgY" +
                    "GBgYGBg2NjY2NjY29jY2NjY2NjY2AAAAAAAAAP42NjY2NjY2NgAAAAAA+Bj4GBgYGBgYGBg2NjY2" +
                    "NvYG9jY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NgAAAAAA/gb2NjY2NjY2NjY2NjY2NvYG/gAAAAAA" +
                    "AAAANjY2NjY2Nv4AAAAAAAAAABgYGBgY+Bj4AAAAAAAAAAAAAAAAAAAA+BgYGBgYGBgYGBgYGBgY" +
                    "GB8AAAAAAAAAABgYGBgYGBj/AAAAAAAAAAAAAAAAAAAA/xgYGBgYGBgYGBgYGBgYGB8YGBgYGBgY" +
                    "GAAAAAAAAAD/AAAAAAAAAAAYGBgYGBgY/xgYGBgYGBgYGBgYGBgfGB8YGBgYGBgYGDY2NjY2NjY3" +
                    "NjY2NjY2NjY2NjY2NjcwPwAAAAAAAAAAAAAAAAA/MDc2NjY2NjY2NjY2NjY29wD/AAAAAAAAAAAA" +
                    "AAAAAP8A9zY2NjY2NjY2NjY2NjY3MDc2NjY2NjY2NgAAAAAA/wD/AAAAAAAAAAA2NjY2NvcA9zY2" +
                    "NjY2NjY2GBgYGBj/AP8AAAAAAAAAADY2NjY2Njb/AAAAAAAAAAAAAAAAAP8A/xgYGBgYGBgYAAAA" +
                    "AAAAAP82NjY2NjY2NjY2NjY2NjY/AAAAAAAAAAAYGBgYGB8YHwAAAAAAAAAAAAAAAAAfGB8YGBgY" +
                    "GBgYGAAAAAAAAAA/NjY2NjY2NjY2NjY2NjY2/zY2NjY2NjY2GBgYGBj/GP8YGBgYGBgYGBgYGBgY" +
                    "GBj4AAAAAAAAAAAAAAAAAAAAHxgYGBgYGBgY/////////////////////wAAAAAAAAD/////////" +
                    "///w8PDw8PDw8PDw8PDw8PDwDw8PDw8PDw8PDw8PDw8PD/////////8AAAAAAAAAAAAAAAAAAHTM" +
                    "zMzMzHYAAAAAAAB4zMzM2MzGxsbMAAAAAAAA/mZiYGBgYGBg8AAAAAAAAAAAAP5sbGxsbGwAAAAA" +
                    "AAD+xmIwGBgwYsb+AAAAAAAAAAAAfsjMzMzMeAAAAAAAAAAAZmZmZmZ8YGDAAAAAAAAAAAB+GBgY" +
                    "GBoMAAAAAAAAOBB81tbW1nwQOAAAAAAAADhsxsb+xsbGbDgAAAAAAAA4bMbGxsZsbGzuAAAAAAAA" +
                    "HjAYDD5mZmZmPAAAAAAAAAAAAH7b29t+AAAAAAAAAAAAAAAcVtbW1tZ8EBAQAAAAAAAAPmBgOGBg" +
                    "PgAAAAAAAAB8xsbGxsbGxsYAAAAAAAAAAP4AAP4AAP4AAAAAAAAAAAAYGH4YGAAA/wAAAAAAAAAw" +
                    "GAwGDBgwAH4AAAAAAAAADBgwYDAYDAB+AAAAAAAADhsbGBgYGBgYGBgYGBgYGBgYGBgYGNjY2HAA" +
                    "AAAAAAAAABgYAH4AGBgAAAAAAAAAAAAAdtwAdtwAAAAAAAAAOGxsOAAAAAAAAAAAAAAAAAAAAAAA" +
                    "ABgYAAAAAAAAAAAAAAAAAAAAGAAAAAAAAAAADwwMDAwM7GxsPBwAAAAAANhsbGxsbAAAAAAAAAAA" +
                    "AABw2DBgyPgAAAAAAAAAAAAAAAAAfHx8fHx8fAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    private void initDefaultFont8x16() {
        byte[] decoded = Base64.getDecoder().decode(DEFAULT_FONT_8X16_B64);
        System.arraycopy(decoded, 0, font8x16, 0, Math.min(decoded.length, FONT_SIZE));
    }

    // =====================================================================
    // Palette helpers
    // =====================================================================

    private void initDefaultPaletteRgb565() {
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

        pairDirty = true;
    }

    private static int rgb565ToArgb(int rgb565) {
        int r5 = (rgb565 >> 11) & 0x1F;
        int g6 = (rgb565 >> 5) & 0x3F;
        int b5 = (rgb565) & 0x1F;

        int r = (r5 << 3) | (r5 >> 2);
        int g = (g6 << 2) | (g6 >> 4);
        int b = (b5 << 3) | (b5 >> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
