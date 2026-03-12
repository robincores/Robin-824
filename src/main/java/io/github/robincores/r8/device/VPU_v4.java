package io.github.robincores.r8.device;

import io.github.robincores.r8.system.InterruptSink;
import io.github.robincores.r8.system.Tickable;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPU v4 ("v4-lite" -> now the official v4):
 * <ul>
 *   <li><b>Single 16K VideoWindow</b> (WIN_MMIO selects MMIO vs banked memory).</li>
 *   <li><b>8 bitplanes</b>: 8 x 16K = 128K planar VRAM (1bpp per plane).</li>
 *   <li><b>Sprite bank</b>: 16K 4bpp packed pixels (VBANK=8).</li>
 *   <li><b>Tables bank</b>: 16K packed bank for OAM + copper list (VBANK=9).</li>
 *   <li><b>Text</b>: 80x25 @ 8x16 + 4K font, kept inside MMIO for "power on -> print".</li>
 *   <li><b>Playfields</b>: 4 groups composited by PRIORITY; each group owns a contiguous plane range.</li>
 *   <li><b>Write-modes</b>: VGA-like planar write modes to make an 8-bit CPU practical with bitplanes.</li>
 *   <li><b>Sprites</b>: Lynx-ish, fixed-point scaling via XSTEP/YSTEP (8.8), optional chaining + collision.</li>
 *   <li><b>Copper</b>: scanline-triggered MMIO writes; program lives in VBANK=9 (tables bank).</li>
 *   <li><b>Blitter</b>: kept (VRAM-only) for now (same spirit as v3, but different register map).</li>
 * </ul>
 *
 * <p><b>Compatibility:</b> v4 intentionally breaks v3 MMIO register addresses and sprite/OAM layout.</p>
 *
 * <p><b>Hardening notes:</b>
 * <ul>
 *   <li>Sprite scaling uses long multiply to avoid overflow.</li>
 *   <li>Sprite render has scanline budget + max-out clamp to prevent hangs on hostile OAM.</li>
 *   <li>Sprite slots guard against overflow (corrupt OAM cannot crash JVM).</li>
 *   <li>Group scrollY is clamped when WRAP_Y is off (rejects 200..255 garbage).</li>
 *   <li>Copper compile sets CFG_ERR on truncation.</li>
 * </ul>
 * </p>
 */
public class VPU_v4 implements VPU, Tickable {

    // =====================================================================
    // Memory organization
    // =====================================================================

    public static final int PLANE_SIZE  = 0x4000;  // 16K
    public static final int NUM_PLANES  = 8;
    public static final int VRAM_SIZE   = PLANE_SIZE * NUM_PLANES; // 128K

    /** 16K sprite pattern bank (4bpp packed). */
    public static final int SPR_BANK_SIZE = 0x4000;

    /** 16K tables bank: OAM + copper program (packed). */
    public static final int TBL_BANK_SIZE = 0x4000;

    // Tables-bank sub-layout (VBANK=9)
    private static final int OAM_OFS    = 0x0000;
    @SuppressWarnings("unused")
    private static final int OAM_SIZE   = 0x1000; // 4K reserved for OAM (128 sprites * 32 = 4096)
    private static final int COP_OFS_DFLT = 0x1000; // default copper program offset inside tables bank

    // =====================================================================
    // MMIO layout (inside the 16K window when WIN_MMIO=1)
    // =====================================================================

    // ---- Core timing / IRQ ----
    private static final int REG_CTRL          = 0x0000;
    private static final int REG_STATUS        = 0x0001;
    private static final int REG_SCAN_L        = 0x0002; // RO current scanline
    private static final int REG_SCAN_H        = 0x0003;
    private static final int REG_RASTER_CMP_L  = 0x0004;
    private static final int REG_RASTER_CMP_H  = 0x0005;

    // ---- Text control ----
    private static final int REG_TX_CTRL       = 0x0006;
    private static final int REG_TX_CUR_X      = 0x0007;
    private static final int REG_TX_CUR_Y      = 0x0008;
    private static final int REG_TX_CUR_START  = 0x0009;
    private static final int REG_TX_CUR_END    = 0x000A;
    private static final int REG_TX_ORIGIN_L   = 0x000B;
    private static final int REG_TX_ORIGIN_H   = 0x000C;
    private static final int REG_TX_FINE_Y     = 0x000D;
    private static final int REG_TX_FINE_X     = 0x000E;
    private static final int REG_TX_ATTR       = 0x000F;
    private static final int REG_TX_CMD        = 0x0030;
    private static final int REG_TX_PORT       = 0x0031;

    // ---- VGA-style write modes ----
    private static final int REG_WM_CTRL       = 0x0010;
    private static final int REG_WM_PLANE_MASK = 0x0011;
    private static final int REG_WM_SETRESET   = 0x0012;
    private static final int REG_WM_BITMASK    = 0x0013;

    // ---- Sprites ----
    private static final int REG_SPR_CTRL      = 0x0014;

    /** Convenience: configure groups 0+1 as a dual-playfield split (write-only). */
    private static final int REG_AUTO_SPLIT    = 0x0015;

    // ---- Copper ----
    private static final int REG_COP_CTRL      = 0x0020;
    private static final int REG_COP_LEN_L     = 0x0021;
    private static final int REG_COP_LEN_H     = 0x0022;
    private static final int REG_COP_OFS_L     = 0x0023;
    private static final int REG_COP_OFS_H     = 0x0024;

    // ---- Blitter (VRAM-only) ----
    private static final int REG_BLT_CTRL        = 0x0060; // bit0 START, bit1 FILL, bit2 IRQ_EN, bit7 BUSY(RO)
    private static final int REG_BLT_SRC_L       = 0x0061;
    private static final int REG_BLT_SRC_H       = 0x0062;
    private static final int REG_BLT_DST_L       = 0x0063;
    private static final int REG_BLT_DST_H       = 0x0064;
    private static final int REG_BLT_W_L         = 0x0065; // bytes per row
    private static final int REG_BLT_W_H         = 0x0066;
    private static final int REG_BLT_H           = 0x0067; // rows (0 => 1)
    private static final int REG_BLT_SRC_PITCH_L = 0x0068; // signed16, added after each row
    private static final int REG_BLT_SRC_PITCH_H = 0x0069;
    private static final int REG_BLT_DST_PITCH_L = 0x006A;
    private static final int REG_BLT_DST_PITCH_H = 0x006B;
    private static final int REG_BLT_FILL        = 0x006C;
    private static final int REG_BLT_PLANE_MASK  = 0x006D; // bits0..7 (0 => all)
    private static final int REG_BLT_ROP         = 0x006E; // 0=COPY,1=OR,2=AND,3=XOR
    private static final int REG_BLT_SHIFT       = 0x006F; // 0..7

    /** Read-only VPU ID/version (helps ROM code detect v4). */
    private static final int REG_VPU_ID          = 0x00FF;

    // ---- Palette (256 x RGB565 little-endian) ----
    private static final int PAL_BASE  = 0x0100;
    private static final int PAL_SIZE  = 0x0200;

    // ---- Collision regs ----
    private static final int COL_BASE      = 0x0300; // 16 bytes hit flags
    private static final int COL_CLR       = 0x0310; // write any value to clear hit flags

    // ---- Group descriptor table (4 groups x 16 bytes) ----
    private static final int GROUP_BASE    = 0x0400;
    private static final int GROUP_SIZE    = 0x0400; // 1K reserved
    private static final int GROUP_STRIDE  = 0x10;
    private static final int GROUP_COUNT   = 4;

    // ---- Text cells + font ----
    private static final int TEXT_COLS   = 80;
    private static final int TEXT_ROWS   = 25;
    private static final int TEXT_CELL_B = 2;
    private static final int TEXT_CELLS  = TEXT_COLS * TEXT_ROWS;
    private static final int TEXT_BASE   = 0x2000;
    private static final int TEXT_SIZE   = TEXT_CELLS * TEXT_CELL_B; // 4000 (0x0FA0)
    private static final int TEXT_SCANLINES = TEXT_ROWS * 16; // 400
    private static final int TEXT_RESV_BASE = (TEXT_BASE + TEXT_SIZE); // 0x2FA0
    private static final int TEXT_RESV_SIZE = (0x3000 - TEXT_RESV_BASE); // 0x60
    private static final int FONT_BASE   = 0x3000;
    private static final int FONT_SIZE   = 0x1000; // 4096 (256*16)

    // =====================================================================
    // Bits
    // =====================================================================

    // CTRL bits
    private static final int CTRL_ENABLE        = 0x01;
    private static final int CTRL_VBL_IRQ_EN    = 0x02;
    private static final int CTRL_RASTER_IRQ_EN = 0x04;
    private static final int CTRL_GFX_DIS       = 0x08;

    // STATUS bits
    private static final int STATUS_VBLANK  = 0x01; // RO timing-owned
    private static final int STATUS_FRAME   = 0x02; // W1C
    private static final int STATUS_RASTER  = 0x04; // W1C
    private static final int STATUS_CFG_ERR = 0x08; // W1C
    private static final int STATUS_BLT     = 0x10; // W1C

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_TRANSPARENT_BG = 0x04;
    private static final int TX_CURSOR_BLINK   = 0x08;
    private static final int TX_CHAR_BLINK     = 0x10;

    // Write modes
    private static final int WM_EN        = 0x01;
    private static final int WM_USE_SR    = 0x02;
    private static final int WM_ROP_SHIFT = 2; // bits2-3

    // Sprites
    private static final int SPR_EN = 0x01;

    // Copper
    private static final int COP_EN          = 0x01;
    private static final int COP_FLAG_WRITE16 = 0x01;
    private static final int COP_FLAG_END     = 0x80;

    // TX_CMD
    private static final int TXCMD_CLR_EOL    = 0x01;
    private static final int TXCMD_CLR_LINE   = 0x02;
    private static final int TXCMD_CLR_SCREEN = 0x04;
    private static final int TXCMD_SCROLL_UP  = 0x08;
    private static final int TXCMD_HOME       = 0x10;

    // Blitter
    private static final int BLT_START           = 0x01;
    private static final int BLT_FILL            = 0x02;
    private static final int BLT_IRQ_EN          = 0x04;
    private static final int BLT_BUSY_RO         = 0x80;
    private static final int BLT_CYCLES_PER_BYTE = 2;

    // Group flags
    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;
    private static final int GF_OPAQUE0 = 0x08;

    // Sprite OAM (tables bank) layout
    private static final int SPR_COUNT  = 128;
    private static final int SPR_STRIDE = 32;

    /** Safety cap: maximum output pixels emitted per sprite per scanline (prevents hangs on bad OAM). */
    private static final int SPR_MAX_OUT_PIX = 2048;

    /** Minimum 8.8 step to allow (prevents extreme upscale -> pathological loops). 0x0040 = max 4x scale. */
    private static final int SPR_MIN_STEP = 0x0040;

    /** Per-scanline global sprite pixel-iteration budget multiplier (budget = W * mult). */
    private static final int SPR_SCANLINE_BUDGET_MULT = 8;

    /** Chain traversal cap (defense-in-depth; dedup still applies). */
    private static final int SPR_CHAIN_GUARD_MAX = 64;

    // ATTR bits (byte 8)
    private static final int SA_EN      = 0x01;
    private static final int SA_HFLIP   = 0x02;
    private static final int SA_VFLIP   = 0x04;
    private static final int SA_CHAIN   = 0x08;
    private static final int SA_COLLIDE = 0x10;
    private static final int SA_TILT    = 0x20;

    // =====================================================================
    // Wiring
    // =====================================================================

    private final DisplayConfig config;
    private final InterruptSink sink;
    private final int irqBit;

    // Rendering targets
    private final Canvas canvas;
    private final GraphicsContext gc;
    private final WritableImage image;
    private final PixelWriter writer;
    private static final PixelFormat<IntBuffer> ARGB_FORMAT = PixelFormat.getIntArgbInstance();

    // Triple buffer (render -> queued -> display)
    private final Object fbLock = new Object();
    private int[] renderBuf;
    private int[] displayBuf;
    private int[] freeBuf;
    private int[] queuedBuf;
    private final AtomicBoolean fxDirty = new AtomicBoolean(false);

    // =====================================================================
    // State (video memory)
    // =====================================================================

    private final byte[] vram = new byte[VRAM_SIZE];
    private final byte[] sprBank = new byte[SPR_BANK_SIZE];
    private final byte[] tblBank = new byte[TBL_BANK_SIZE];

    private final short[] pal565 = new short[256];
    private final int[] palArgb  = new int[256];

    private final byte[] groupRam = new byte[GROUP_SIZE];

    private final byte[] textRam  = new byte[TEXT_SIZE];
    private final byte[] textResv = new byte[TEXT_RESV_SIZE];
    private final byte[] font8x16 = new byte[FONT_SIZE];

    // =====================================================================
    // Registers
    // =====================================================================

    private int ctrl;
    private int status;
    private int scanline;
    private int rasterCmp;

    // Text
    private int txCtrl;
    private int txCurX, txCurY;
    private int txCurStart, txCurEnd;
    private int txOrigin;
    private int txFineY;
    private int txFineX;
    private int txAttr;

    // Write modes
    private int wmCtrl;
    private int wmPlaneMask;
    private int wmSetReset;
    private int wmBitMask;

    // Sprites
    private int sprCtrl;

    // Sprite dedup per scanline (prevents chain targets rendering twice)
    private final int[] sprLineGen = new int[SPR_COUNT];
    private int sprStamp = 1;

    // Global per-scanline sprite budget
    private int sprBudget;

    // Per-plane base pointers (still useful and cheap)
    private final int[] bplBase = new int[NUM_PLANES]; // 14-bit
    private boolean framePlaneBaseActive;

    // Scratch: per-group plane bytes for one 8-pixel chunk (avoid allocations)
    private final int[] grpBytes = new int[8];

    // Scratch: group priorities in sorted order (avoid per-frame allocations in sprite slot build)
    private final int[] gpPrioTmp = new int[GROUP_COUNT];

    // Copper
    private int copCtrl;
    private int copLen;
    private int copOfs;
    private boolean copDirty = true;

    // Copper compiled list
    private static final int COPPER_STRIDE = 8;
    private static final int COP_MAX = 1024; // headroom (tables bank has space)
    private final int[] copScan  = new int[COP_MAX];
    private final int[] copReg   = new int[COP_MAX];
    private final int[] copValLo = new int[COP_MAX];
    private final int[] copValHi = new int[COP_MAX];
    private final int[] copFlags = new int[COP_MAX];
    private int copCount;
    private int copIdx;

    // Sorting scratch
    private final int[] copScanTmp  = new int[COP_MAX];
    private final int[] copRegTmp   = new int[COP_MAX];
    private final int[] copValLoTmp = new int[COP_MAX];
    private final int[] copValHiTmp = new int[COP_MAX];
    private final int[] copFlagsTmp = new int[COP_MAX];
    private final int[] copCounts;

    // Blitter
    private int bltCtrl;
    private int bltSrc, bltDst;
    private int bltW, bltH;
    private int bltSrcPitch, bltDstPitch;
    private int bltFill;
    private int bltPlaneMask;
    private int bltRop;
    private int bltShift;
    private boolean bltBusy;
    private int bltWRun, bltHRun;
    private int bltSrcPitchRun, bltDstPitchRun;
    private int bltPlaneMaskRun;
    private int bltRopRun;
    private int bltShiftRun;
    private int bltX, bltY;
    private int bltSrcCur, bltDstCur;
    private int bltCyclesAcc;

    // Timing
    private int cycleAccum;
    private int frameCounter;

    // Frame-latched
    private boolean frameCopperEnabled;
    private boolean frameLiveRender;
    private boolean frameCursorEnabled;

    // =====================================================================
    // Groups (decoded per frame)
    // =====================================================================

    private static final class Group {
        boolean en;
        int planeStart;
        int planeCount;
        int palBase;
        int scrollX;   // signed16
        int scrollY;   // unsigned 0..255
        int priority;
        int flags;
        int bplOfs;    // 14-bit
        int planesMask;
    }

    private final Group[] groups = new Group[GROUP_COUNT];
    private final int[] groupOrder = new int[GROUP_COUNT];
    private int groupOrderCount = 0;

    // Sprite priority slots computed per frame
    private final int[] sprSlotCount = new int[5];
    private final int[][] sprSlots = new int[5][SPR_COUNT];
    private int frameAnyCollide = 0;

    // =====================================================================
    // Collision state
    // =====================================================================

    private static final int COL_HIT_BYTES = (SPR_COUNT + 7) / 8; // 16
    private final byte[] colHit = new byte[COL_HIT_BYTES];
    private final byte[] colLineId;
    private final byte[] colLineSpr;
    private final int[]  colLineGen;
    private int colStamp = 1;

    // =====================================================================
    // Text fast-path LUTs
    // =====================================================================

    private static final int[] BITPACK = new int[256];
    static {
        for (int b = 0; b < 256; b++) {
            int p = 0;
            for (int i = 0; i < 8; i++) {
                int bit = (b >>> (7 - i)) & 1;
                p |= (bit << (i * 4));
            }
            BITPACK[b] = p;
        }
    }

    private static final int[] TEXT_PAIR4 = new int[256 * 256];
    static {
        for (int glyph = 0; glyph < 256; glyph++) {
            int pb = BITPACK[glyph];
            for (int attr = 0; attr < 256; attr++) {
                int fg = attr & 0x0F;
                int bg = (attr >>> 4) & 0x0F;
                int packedPairs = 0;
                for (int k = 0; k < 4; k++) {
                    int n0 = (pb >>> ((2 * k) * 4)) & 1;
                    int n1 = (pb >>> ((2 * k + 1) * 4)) & 1;
                    int left  = bg ^ (-(n0) & (bg ^ fg));
                    int right = bg ^ (-(n1) & (bg ^ fg));
                    int pair = (left & 0x0F) | ((right & 0x0F) << 4);
                    packedPairs |= (pair << (k * 8));
                }
                TEXT_PAIR4[(glyph << 8) | attr] = packedPairs;
            }
        }
    }

    private final long[] pairArgb = new long[256];
    private boolean pairDirty = true;

    // =====================================================================
    // Constructors
    // =====================================================================

    public VPU_v4(DisplayConfig config, InterruptSink sink, int irqBit, Canvas canvas) {
        this(config, sink, irqBit, canvas, null);
    }

    public VPU_v4(DisplayConfig config, InterruptSink sink, int irqBit, WritableImage targetImage) {
        this(config, sink, irqBit, null, targetImage);
    }

    private VPU_v4(DisplayConfig config, InterruptSink sink, int irqBit, Canvas canvas, WritableImage targetImage) {
        this.config = config;
        this.sink = sink;
        this.irqBit = irqBit;
        this.canvas = canvas;
        this.copCounts = new int[config.height()];

        if (canvas != null) {
            canvas.setWidth(config.canvasWidth());
            canvas.setHeight(config.canvasHeight());
        }

        this.image = (targetImage != null) ? targetImage : new WritableImage(config.width(), config.height());
        this.writer = image.getPixelWriter();
        this.gc = (canvas != null) ? canvas.getGraphicsContext2D() : null;
        if (this.gc != null) this.gc.setImageSmoothing(false);

        int npx = config.width() * config.height();
        this.renderBuf  = new int[npx];
        this.displayBuf = new int[npx];
        this.freeBuf    = new int[npx];
        this.queuedBuf  = null;

        this.colLineId  = new byte[config.width()];
        this.colLineSpr = new byte[config.width()];
        this.colLineGen = new int[config.width()];

        for (int i = 0; i < GROUP_COUNT; i++) groups[i] = new Group();

        initDefaultPaletteRgb565();
        initDefaultTextRam();
        initDefaultFont8x16();
        reset(false);
    }

    // =====================================================================
    // Reset
    // =====================================================================

    public void reset() { reset(true); }

    public void reset(boolean hard) {
        ctrl = CTRL_ENABLE;
        status = 0;
        scanline = 0;
        rasterCmp = 0;

        txCtrl = (TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK);
        txCurX = 0;
        txCurY = 0;
        txCurStart = 14;
        txCurEnd = 15;
        txOrigin = 0;
        txFineY = 0;
        txFineX = 0;
        txAttr = 0x07;

        wmCtrl = 0;
        wmPlaneMask = 0xFF;
        wmSetReset = 0;
        wmBitMask = 0xFF;

        sprCtrl = SPR_EN;

        Arrays.fill(bplBase, 0);

        copCtrl = 0;
        copLen = 0;
        copOfs = COP_OFS_DFLT;
        copDirty = true;
        copCount = 0;
        copIdx = 0;

        bltCtrl = 0;
        bltBusy = false;
        bltSrc = bltDst = 0;
        bltW = 0;
        bltH = 1;
        bltSrcPitch = bltDstPitch = 0;
        bltFill = 0;
        bltPlaneMask = 0xFF;
        bltRop = 0;
        bltShift = 0;
        bltCyclesAcc = 0;

        cycleAccum = 0;
        frameCounter = 0;
        pairDirty = true;

        Arrays.fill(colHit, (byte) 0);
        Arrays.fill(groupRam, (byte) 0);

        if (hard) {
            Arrays.fill(vram, (byte) 0);
            Arrays.fill(sprBank, (byte) 0);
            Arrays.fill(tblBank, (byte) 0);
            Arrays.fill(textResv, (byte) 0);
            initDefaultPaletteRgb565();
            initDefaultTextRam();
            initDefaultFont8x16();
        }
    }

    // =====================================================================
    // VideoWindow bank access (WIN_MMIO=0)
    // =====================================================================

    /**
     * Read from a bank exposed through the 16K VideoWindow when WIN_MMIO=0.
     * <ul>
     *   <li>0..7 = VRAM planes</li>
     *   <li>8    = sprite pattern bank (4bpp)</li>
     *   <li>9    = tables bank (OAM + copper program)</li>
     * </ul>
     */
    public byte readVramPlane(int bank, int offset) {
        int ofs = offset & (PLANE_SIZE - 1);
        if (bank >= 0 && bank < NUM_PLANES) {
            return vram[(bank * PLANE_SIZE) + ofs];
        }
        if (bank == 8) {
            return sprBank[ofs];
        }
        if (bank == 9) {
            return tblBank[ofs];
        }
        return 0;
    }

    public void writeVramPlane(int bank, int offset, byte value) {
        int ofs = offset & (PLANE_SIZE - 1);

        // Planar write-modes: when enabled and bank is any VRAM plane (0..7),
        // one CPU write updates multiple planes based on WM_*.
        if (bank >= 0 && bank < NUM_PLANES) {
            if ((wmCtrl & WM_EN) != 0) {
                wmApplyWrite(ofs, value & 0xFF);
            } else {
                vram[(bank * PLANE_SIZE) + ofs] = value;
            }
            return;
        }
        if (bank == 8) {
            sprBank[ofs] = value;
            return;
        }
        if (bank == 9) {
            tblBank[ofs] = value;
            // Only mark copper dirty if the write hits the active copper program region.
            if (isCopperRegionWrite(ofs)) {
                copDirty = true;
            }
        }
    }

    private boolean isCopperRegionWrite(int tblOfs) {
        int len = copLen & 0xFFFF;
        if (len <= 0) return false;
        int start = copOfs & 0x3FFF;
        if (start >= TBL_BANK_SIZE) start = COP_OFS_DFLT;
        if (start + len > TBL_BANK_SIZE) len = TBL_BANK_SIZE - start;
        len = (len / COPPER_STRIDE) * COPPER_STRIDE;
        if (len <= 0) return false;
        return (tblOfs >= start) && (tblOfs < (start + len));
    }

    private void wmApplyWrite(int addr, int d) {
        int mask = wmPlaneMask & 0xFF;
        if (mask == 0) return;

        boolean useSR = (wmCtrl & WM_USE_SR) != 0;
        int rop = (wmCtrl >>> WM_ROP_SHIFT) & 0x03;
        int m = wmBitMask & 0xFF;

        for (int p = 0; p < NUM_PLANES; p++) {
            if ((mask & (1 << p)) == 0) continue;

            int baseP = p * PLANE_SIZE;
            int old = vram[baseP + addr] & 0xFF;
            int src = useSR ? (((wmSetReset >>> p) & 1) != 0 ? 0xFF : 0x00) : d;

            int out;
            switch (rop) {
                case 1 -> out = old | (src & m);
                case 2 -> out = old & (src | (~m & 0xFF));
                case 3 -> out = old ^ (src & m);
                default -> out = (old & (~m & 0xFF)) | (src & m);
            }
            vram[baseP + addr] = (byte) out;
        }
    }

    // =====================================================================
    // MMIO view (WIN_MMIO=1)
    // =====================================================================

    public byte readMmio(int offset) {
        int o = offset & 0x3FFF;

        // Palette
        if (o >= PAL_BASE && o < (PAL_BASE + PAL_SIZE)) {
            int idx = (o - PAL_BASE) >> 1;
            boolean hi = ((o - PAL_BASE) & 1) != 0;
            int v = pal565[idx] & 0xFFFF;
            return (byte) (hi ? ((v >>> 8) & 0xFF) : (v & 0xFF));
        }

        // Collision hit flags
        if (o >= COL_BASE && o < (COL_BASE + COL_HIT_BYTES)) {
            return colHit[o - COL_BASE];
        }

        // Group table
        if (o >= GROUP_BASE && o < (GROUP_BASE + GROUP_SIZE)) {
            return groupRam[o - GROUP_BASE];
        }

        // Text personality: TEXT + reserved gap + FONT
        if (o >= TEXT_BASE && o < (TEXT_BASE + TEXT_SIZE)) return textRam[o - TEXT_BASE];
        if (o >= TEXT_RESV_BASE && o < FONT_BASE) return textResv[o - TEXT_RESV_BASE];
        if (o >= FONT_BASE && o < (FONT_BASE + FONT_SIZE)) return font8x16[o - FONT_BASE];

        // Per-plane base pointers: 0x0040..0x004F (2 bytes per plane)
        if (o >= 0x0040 && o < (0x0040 + NUM_PLANES * 2)) {
            int p = (o - 0x0040) >> 1;
            boolean hi = ((o - 0x0040) & 1) != 0;
            int base = bplBase[p] & 0x3FFF;
            return (byte) (hi ? ((base >>> 8) & 0x3F) : (base & 0xFF));
        }

        return (byte) switch (o) {
            case REG_CTRL -> ctrl;
            case REG_STATUS -> status;
            case REG_SCAN_L -> (scanline & 0xFF);
            case REG_SCAN_H -> ((scanline >>> 8) & 0xFF);
            case REG_RASTER_CMP_L -> (rasterCmp & 0xFF);
            case REG_RASTER_CMP_H -> ((rasterCmp >>> 8) & 0xFF);

            case REG_TX_CTRL -> txCtrl;
            case REG_TX_CUR_X -> txCurX;
            case REG_TX_CUR_Y -> txCurY;
            case REG_TX_CUR_START -> txCurStart;
            case REG_TX_CUR_END -> txCurEnd;
            case REG_TX_ORIGIN_L -> (txOrigin & 0xFF);
            case REG_TX_ORIGIN_H -> ((txOrigin >>> 8) & 0xFF);
            case REG_TX_FINE_Y -> (txFineY & 0x0F);
            case REG_TX_FINE_X -> (txFineX & 0x07);
            case REG_TX_ATTR -> (txAttr & 0xFF);
            case REG_TX_CMD -> 0;
            case REG_TX_PORT -> 0;

            case REG_WM_CTRL -> (wmCtrl & 0x0F);
            case REG_WM_PLANE_MASK -> (wmPlaneMask & 0xFF);
            case REG_WM_SETRESET -> (wmSetReset & 0xFF);
            case REG_WM_BITMASK -> (wmBitMask & 0xFF);

            case REG_SPR_CTRL -> (sprCtrl & 0xFF);
            case REG_AUTO_SPLIT -> 0;

            case REG_COP_CTRL -> (copCtrl & 0xFF);
            case REG_COP_LEN_L -> (copLen & 0xFF);
            case REG_COP_LEN_H -> ((copLen >>> 8) & 0xFF);
            case REG_COP_OFS_L -> (copOfs & 0xFF);
            case REG_COP_OFS_H -> ((copOfs >>> 8) & 0xFF);

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
            case REG_BLT_PLANE_MASK -> (bltPlaneMask & 0xFF);
            case REG_BLT_ROP -> (bltRop & 0x03);
            case REG_BLT_SHIFT -> (bltShift & 0x07);

            case REG_VPU_ID -> 0x40; // v4.0 (major in high nibble)

            default -> 0;
        };
    }

    public void writeMmio(int offset, byte value) {
        int o = offset & 0x3FFF;
        int v = value & 0xFF;

        // Palette
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

        // Collision clear
        if (o == COL_CLR) {
            Arrays.fill(colHit, (byte) 0);
            return;
        }

        // Group table
        if (o >= GROUP_BASE && o < (GROUP_BASE + GROUP_SIZE)) {
            groupRam[o - GROUP_BASE] = (byte) v;
            return;
        }

        // Text personality
        if (o >= TEXT_BASE && o < (TEXT_BASE + TEXT_SIZE)) {
            textRam[o - TEXT_BASE] = (byte) v;
            return;
        }
        if (o >= TEXT_RESV_BASE && o < FONT_BASE) {
            textResv[o - TEXT_RESV_BASE] = (byte) v;
            return;
        }
        if (o >= FONT_BASE && o < (FONT_BASE + FONT_SIZE)) {
            font8x16[o - FONT_BASE] = (byte) v;
            return;
        }

        // Per-plane base pointers
        if (o >= 0x0040 && o < (0x0040 + NUM_PLANES * 2)) {
            int p = (o - 0x0040) >> 1;
            boolean hi = ((o - 0x0040) & 1) != 0;
            int cur = bplBase[p] & 0x3FFF;
            int next = hi ? ((cur & 0x00FF) | ((v & 0x3F) << 8)) : ((cur & 0x3F00) | v);
            bplBase[p] = next & 0x3FFF;
            return;
        }

        switch (o) {
            case REG_CTRL -> ctrl = (v & 0xFF);
            case REG_STATUS -> {
                int w1c = v & (STATUS_FRAME | STATUS_RASTER | STATUS_CFG_ERR | STATUS_BLT);
                status &= ~w1c;
            }
            case REG_RASTER_CMP_L -> rasterCmp = (rasterCmp & 0xFF00) | v;
            case REG_RASTER_CMP_H -> rasterCmp = (rasterCmp & 0x00FF) | (v << 8);

            case REG_TX_CTRL -> txCtrl = (v & 0xFF);
            case REG_TX_CUR_X -> txCurX = Math.min(v, TEXT_COLS - 1);
            case REG_TX_CUR_Y -> txCurY = Math.min(v, TEXT_ROWS - 1);
            case REG_TX_CUR_START -> txCurStart = (v & 0x0F);
            case REG_TX_CUR_END -> txCurEnd = (v & 0x0F);
            case REG_TX_ORIGIN_L -> { txOrigin = (txOrigin & 0xFF00) | v; normalizeTxOrigin(); }
            case REG_TX_ORIGIN_H -> { txOrigin = (txOrigin & 0x00FF) | (v << 8); normalizeTxOrigin(); }
            case REG_TX_FINE_Y -> txFineY = (v & 0x0F);
            case REG_TX_FINE_X -> txFineX = (v & 0x07);
            case REG_TX_ATTR -> txAttr = (v & 0xFF);
            case REG_TX_CMD -> txCommand(v);
            case REG_TX_PORT -> txPortWrite(v);

            case REG_WM_CTRL -> wmCtrl = (v & 0x0F);
            case REG_WM_PLANE_MASK -> wmPlaneMask = (v & 0xFF);
            case REG_WM_SETRESET -> wmSetReset = (v & 0xFF);
            case REG_WM_BITMASK -> wmBitMask = (v & 0xFF);

            case REG_SPR_CTRL -> sprCtrl = (v & 0xFF);
            case REG_AUTO_SPLIT -> autoSplit(v);

            case REG_COP_CTRL -> copCtrl = (v & 0xFF);
            case REG_COP_LEN_L -> { copLen = (copLen & 0xFF00) | v; copDirty = true; }
            case REG_COP_LEN_H -> { copLen = (copLen & 0x00FF) | (v << 8); copDirty = true; }
            case REG_COP_OFS_L -> { copOfs = (copOfs & 0xFF00) | v; copDirty = true; }
            case REG_COP_OFS_H -> { copOfs = (copOfs & 0x00FF) | (v << 8); copDirty = true; }

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
            case REG_BLT_PLANE_MASK -> bltPlaneMask = (v & 0xFF);
            case REG_BLT_ROP -> bltRop = (v & 0x03);
            case REG_BLT_SHIFT -> bltShift = (v & 0x07);

            default -> { /* ignore */ }
        }
    }

    private void autoSplit(int n) {
        // Configure groups 0+1 in one write:
        //  group0 = planes 0..n-1, group1 = planes n..7, both LORES, priorities 0/1.
        int split = n & 0x07;
        if (split == 0) split = 1; // clamp to [1..7]
        if (split >= 8) split = 7;

        // group0 @ base 0x000
        int g0 = 0x00;
        groupRam[g0 + 0] = 0;                 // PLANE_START
        groupRam[g0 + 1] = (byte) split;      // PLANE_COUNT
        groupRam[g0 + 2] = 0;                 // PAL_BASE
        groupRam[g0 + 3] = 0;                 // SCROLL_X_L
        groupRam[g0 + 4] = 0;                 // SCROLL_X_H
        groupRam[g0 + 5] = 0;                 // SCROLL_Y
        groupRam[g0 + 6] = 0;                 // PRIORITY
        groupRam[g0 + 7] = (byte) (GF_LORES | GF_WRAP_X | GF_WRAP_Y); // FLAGS
        groupRam[g0 + 8] = 0;                 // BPL_OFS_L
        groupRam[g0 + 9] = 0;                 // BPL_OFS_H

        // group1 @ base 0x010
        int g1 = 0x10;
        groupRam[g1 + 0] = (byte) split;      // PLANE_START
        groupRam[g1 + 1] = (byte) (8 - split);// PLANE_COUNT
        groupRam[g1 + 2] = 16;                // PAL_BASE
        groupRam[g1 + 3] = 0;
        groupRam[g1 + 4] = 0;
        groupRam[g1 + 5] = 0;
        groupRam[g1 + 6] = 1;                 // PRIORITY
        groupRam[g1 + 7] = (byte) (GF_LORES | GF_WRAP_X | GF_WRAP_Y); // FLAGS
        groupRam[g1 + 8] = 0;
        groupRam[g1 + 9] = 0;

        // Disable groups 2+3 by default.
        groupRam[0x20 + 1] = 0;
        groupRam[0x30 + 1] = 0;
    }

    private void normalizeTxOrigin() {
        txOrigin %= TEXT_CELLS;
        if (txOrigin < 0) txOrigin += TEXT_CELLS;
    }

    // =====================================================================
    // Tick / timing
    // =====================================================================

    @Override
    public void tick(int cycles) {
        if (cycles <= 0) return;
        if ((ctrl & CTRL_ENABLE) == 0) return;

        bltTick(cycles);

        cycleAccum += cycles;
        final int cps = config.cyclesPerScanline();
        while (cycleAccum >= cps) {
            cycleAccum -= cps;

            if (scanline == 0) beginFrame();

            if (frameLiveRender && scanline < config.height()) {
                renderScanlineIntoBackBuffer(scanline);
            }

            if (((ctrl & CTRL_RASTER_IRQ_EN) != 0) && (scanline == (rasterCmp & 0xFFFF))) {
                status |= STATUS_RASTER;
                sink.raise(irqBit);
            }

            if (scanline == config.vblankStart()) {
                status |= STATUS_VBLANK | STATUS_FRAME;
                frameCounter++;

                if (frameLiveRender) {
                    publishRenderedFrame();
                } else {
                    renderFrameIntoBackBuffer();
                }
                fxDirty.set(true);

                if ((ctrl & CTRL_VBL_IRQ_EN) != 0) {
                    sink.raise(irqBit);
                }
            }

            scanline++;
            if (scanline >= config.scanlinesPerFrame()) {
                scanline = 0;
                status &= ~STATUS_VBLANK;
            }
        }
    }

    private void beginFrame() {
        frameCopperEnabled = (copCtrl & COP_EN) != 0;
        frameLiveRender = (ctrl & CTRL_RASTER_IRQ_EN) != 0;
        if (frameCopperEnabled) copperCompileIfNeeded();

        boolean blinkPhaseOn = ((txCtrl & TX_CURSOR_BLINK) == 0) || (((frameCounter >> 4) & 1) == 0);
        frameCursorEnabled = ((txCtrl & TX_CURSOR_EN) != 0) && blinkPhaseOn;

        // Compute whether plane-base pointers are active (avoid extra add in inner loops when unused).
        framePlaneBaseActive = false;
        for (int p = 0; p < NUM_PLANES; p++) {
            if ((bplBase[p] & 0x3FFF) != 0) { framePlaneBaseActive = true; break; }
        }

        Arrays.fill(colHit, (byte) 0);
        copIdx = 0;

        decodeGroupsAndBuildOrder();
        buildSpritePrioritySlots();
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    private void renderFrameIntoBackBuffer() {
        final int H = config.height();
        for (int y = 0; y < H; y++) renderScanlineIntoBackBuffer(y);
        publishRenderedFrame();
    }

    private void renderScanlineIntoBackBuffer(int y) {
        if (frameCopperEnabled) copperApplyForScanline(y);

        final int W = config.width();
        final int rowOfs = y * W;

        final int tx = txCtrl;
        final boolean textOn = (tx & TX_EN) != 0;
        final boolean transparentBg = (tx & TX_TRANSPARENT_BG) != 0;

        // Doubled-scan fast path: source is 200 lines mapped to 400, so odd scanlines repeat the previous line.
        // We can always reuse the underlay (groups+sprites). Then render text on top (opaque or transparent).
        final boolean canCopyDoubledUnderlay = !frameCopperEnabled && !frameLiveRender && ((y & 1) == 1);
        if (canCopyDoubledUnderlay) {
            System.arraycopy(renderBuf, (y - 1) * W, renderBuf, rowOfs, W);
            if (textOn) {
                renderTextScanline(y, transparentBg, frameCursorEnabled);
            }
            return;
        }

        final boolean spritesOn = (sprCtrl & SPR_EN) != 0;
        if (spritesOn) {
            // NOTE: runtime-optimization introduced a sprite budget guard in renderSpritesScanlineSlot.
            // It must be initialized to a positive value, otherwise sprites will never render.
            // (If we later implement a real per-scanline budget, decrement it in renderOneSpriteScanline.)
            sprBudget = SPR_COUNT;
            if (frameAnyCollide != 0) beginSpriteCollisionScanline();
            beginSpriteVisitedScanline();
        }

        if ((ctrl & CTRL_GFX_DIS) != 0) {
            Arrays.fill(renderBuf, rowOfs, rowOfs + W, palArgb[0]);
        } else {
            renderGroupsAndSpritesScanline(y);
        }

        if (textOn) {
            renderTextScanline(y, transparentBg, frameCursorEnabled);
        }
    }


    private void renderGroupsAndSpritesScanline(int yOut) {
        final int W = config.width();
        final int rowOfs = yOut * W;

        // Start with background color 0.
        Arrays.fill(renderBuf, rowOfs, rowOfs + W, palArgb[0]);

        // Sprite slot 0 (below all groups)
        if ((sprCtrl & SPR_EN) != 0) renderSpritesScanlineSlot(yOut, 0);

        for (int oi = 0; oi < groupOrderCount; oi++) {
            Group g = groups[groupOrder[oi]];
            renderGroupScanline(yOut, g);
            if ((sprCtrl & SPR_EN) != 0) renderSpritesScanlineSlot(yOut, oi + 1);
        }
    }

    private static int mod(int v, int m) {
        v %= m;
        if (v < 0) v += m;
        return v;
    }
    /**
     * Transpose an 8x8 bit-matrix packed as 8 bytes in a 64-bit word.
     * Input: byte i contains bits for plane i (bit7 = left-most pixel).
     * Output: byte j contains the 8-bit color index for source bit j (bit0..7),
     * so pixels 0..7 use bytes 7..0 respectively.
     */
    private static long transpose8x8(long x) {
        long t = (x ^ (x >>> 7)) & 0x00AA00AA00AA00AAL;
        x ^= t ^ (t << 7);
        t = (x ^ (x >>> 14)) & 0x0000CCCC0000CCCCL;
        x ^= t ^ (t << 14);
        t = (x ^ (x >>> 28)) & 0x00000000F0F0F0F0L;
        x ^= t ^ (t << 28);
        return x;
    }



    private void renderGroupScanline(int yOut, Group g) {
        if (!g.en) return;

        // Byte-oriented scanline renderer (v3-style): read each VRAM byte once and emit 8 pixels.
        // Hot spot: make common cases (8-plane 8bpp) as branch-free as possible.

        final int W = config.width();
        final int rowOfs = yOut * W;

        final int ySrc = (yOut >>> 1);
        if (ySrc < 0 || ySrc >= 200) return;

        final boolean lores = (g.flags & GF_LORES) != 0;
        final int bpl = lores ? 40 : 80; // bytes per source row

        int sy = ySrc + g.scrollY;
        if ((g.flags & GF_WRAP_Y) != 0) {
            sy = mod(sy, 200);
        } else {
            if (sy < 0 || sy >= 200) return;
        }

        final boolean wrapX = (g.flags & GF_WRAP_X) != 0;
        final boolean opaque0 = (g.flags & GF_OPAQUE0) != 0;
        final int palBase = g.palBase & 0xFF;

        // Signed 16-bit scroll. Power-of-two div/mod by 8 done via shifts.
        final int scrollX = (short) g.scrollX;
        int byteOfs = scrollX >> 3;
        final int sh = scrollX & 7;
        final int shAmt = 8 - sh;

        // Normalize byte offset when wrapping to avoid modulo in the inner loop.
        if (wrapX) {
            byteOfs %= bpl;
            if (byteOfs < 0) byteOfs += bpl;
        }

        // Base offset for this scanline inside each plane.
        final int lineBase = (g.bplOfs + sy * bpl) & 0x3FFF;

        final byte[] v = this.vram;
        final int[] pal = this.palArgb;
        final int[] dst = this.renderBuf;

        final int planeStart = g.planeStart;
        final int planeCount = g.planeCount;

        // Fast path: full 8-plane group (8bpp). planeStart must be 0 when planeCount==8.
        if (planeCount == 8 && planeStart == 0) {
            final int b0 = 0 * PLANE_SIZE;
            final int b1 = 1 * PLANE_SIZE;
            final int b2 = 2 * PLANE_SIZE;
            final int b3 = 3 * PLANE_SIZE;
            final int b4 = 4 * PLANE_SIZE;
            final int b5 = 5 * PLANE_SIZE;
            final int b6 = 6 * PLANE_SIZE;
            final int b7 = 7 * PLANE_SIZE;

            for (int bx = 0; bx < bpl; bx++) {
                int srcBx = bx + byteOfs;
                int srcBxN = srcBx + 1;

                boolean curIn = true;
                boolean nxtIn = true;

                if (wrapX) {
                    if (srcBx >= bpl) srcBx -= bpl;
                    if (srcBxN == bpl) srcBxN = 0;
                } else {
                    curIn = (srcBx >= 0 && srcBx < bpl);
                    nxtIn = (srcBxN >= 0 && srcBxN < bpl);
                }

                final int oc = curIn ? ((lineBase + srcBx) & 0x3FFF) : 0;
                final int on = (sh != 0 && nxtIn) ? ((lineBase + srcBxN) & 0x3FFF) : 0;

                int p0, p1, p2, p3, p4, p5, p6, p7b;

                if (sh == 0) {
                    p0 = curIn ? (v[b0 + oc] & 0xFF) : 0;
                    p1 = curIn ? (v[b1 + oc] & 0xFF) : 0;
                    p2 = curIn ? (v[b2 + oc] & 0xFF) : 0;
                    p3 = curIn ? (v[b3 + oc] & 0xFF) : 0;
                    p4 = curIn ? (v[b4 + oc] & 0xFF) : 0;
                    p5 = curIn ? (v[b5 + oc] & 0xFF) : 0;
                    p6 = curIn ? (v[b6 + oc] & 0xFF) : 0;
                    p7b = curIn ? (v[b7 + oc] & 0xFF) : 0;
                } else {
                    int cur, nxt, w16;
                    cur = curIn ? (v[b0 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b0 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p0 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b1 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b1 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p1 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b2 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b2 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p2 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b3 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b3 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p3 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b4 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b4 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p4 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b5 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b5 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p5 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b6 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b6 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p6 = (w16 >>> shAmt) & 0xFF;
                    cur = curIn ? (v[b7 + oc] & 0xFF) : 0; nxt = nxtIn ? (v[b7 + on] & 0xFF) : 0; w16 = (cur << 8) | nxt; p7b = (w16 >>> shAmt) & 0xFF;
                }

                long packed = (p0 & 0xFFL)
                        | ((p1 & 0xFFL) << 8)
                        | ((p2 & 0xFFL) << 16)
                        | ((p3 & 0xFFL) << 24)
                        | ((p4 & 0xFFL) << 32)
                        | ((p5 & 0xFFL) << 40)
                        | ((p6 & 0xFFL) << 48)
                        | ((p7b & 0xFFL) << 56);

                long tr = transpose8x8(packed);

                final int outX = lores ? (bx << 4) : (bx << 3);
                int outPos = rowOfs + outX;

                if (lores) {
                    int idx;

                    idx = (int) ((tr >>> 56) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) ((tr >>> 48) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) ((tr >>> 40) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) ((tr >>> 32) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) ((tr >>> 24) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) ((tr >>> 16) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) ((tr >>> 8) & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                    outPos += 2;

                    idx = (int) (tr & 0xFF);
                    if (idx != 0 || opaque0) { int argb = pal[(palBase + idx) & 0xFF]; dst[outPos] = argb; dst[outPos + 1] = argb; }
                } else {
                    int idx;

                    idx = (int) ((tr >>> 56) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 0] = pal[(palBase + idx) & 0xFF];

                    idx = (int) ((tr >>> 48) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 1] = pal[(palBase + idx) & 0xFF];

                    idx = (int) ((tr >>> 40) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 2] = pal[(palBase + idx) & 0xFF];

                    idx = (int) ((tr >>> 32) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 3] = pal[(palBase + idx) & 0xFF];

                    idx = (int) ((tr >>> 24) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 4] = pal[(palBase + idx) & 0xFF];

                    idx = (int) ((tr >>> 16) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 5] = pal[(palBase + idx) & 0xFF];

                    idx = (int) ((tr >>> 8) & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 6] = pal[(palBase + idx) & 0xFF];

                    idx = (int) (tr & 0xFF);
                    if (idx != 0 || opaque0) dst[outPos + 7] = pal[(palBase + idx) & 0xFF];
                }
            }
            return;
        }

        // General path (<=7 planes, or planeStart != 0).
        final int[] pb = this.grpBytes;

        for (int bx = 0; bx < bpl; bx++) {
            int srcBx = bx + byteOfs;
            int srcBxN = srcBx + 1;

            boolean curIn = true;
            boolean nxtIn = true;

            if (wrapX) {
                if (srcBx >= bpl) srcBx -= bpl;
                if (srcBxN == bpl) srcBxN = 0;
            } else {
                curIn = (srcBx >= 0 && srcBx < bpl);
                nxtIn = (srcBxN >= 0 && srcBxN < bpl);
            }

            final int oc = curIn ? ((lineBase + srcBx) & 0x3FFF) : 0;
            final int on = (sh != 0 && nxtIn) ? ((lineBase + srcBxN) & 0x3FFF) : 0;

            if (sh == 0) {
                for (int i = 0; i < planeCount; i++) {
                    final int p = planeStart + i;
                    pb[i] = curIn ? (v[(p * PLANE_SIZE) + oc] & 0xFF) : 0;
                }
            } else {
                for (int i = 0; i < planeCount; i++) {
                    final int p = planeStart + i;
                    final int baseP = p * PLANE_SIZE;
                    final int cur = curIn ? (v[baseP + oc] & 0xFF) : 0;
                    final int nxt = nxtIn ? (v[baseP + on] & 0xFF) : 0;
                    final int w16 = (cur << 8) | nxt;
                    pb[i] = (w16 >>> shAmt) & 0xFF;
                }
            }

            final int outX = lores ? (bx << 4) : (bx << 3);
            for (int i = 0; i < 8; i++) {
                final int bit = 7 - i;
                int idx = 0;
                for (int pi = 0; pi < planeCount; pi++) {
                    idx |= ((pb[pi] >>> bit) & 1) << pi;
                }
                if (idx == 0 && !opaque0) continue;

                final int argb = pal[(palBase + idx) & 0xFF];
                if (lores) {
                    final int dstPos = rowOfs + outX + (i << 1);
                    dst[dstPos] = argb;
                    dst[dstPos + 1] = argb;
                } else {
                    dst[rowOfs + outX + i] = argb;
                }
            }
        }
    }


    // =====================================================================
    // Group decode & overlap rule
    // =====================================================================

    private void decodeGroupsAndBuildOrder() {
        boolean cfgErr = false;

        for (int gi = 0; gi < GROUP_COUNT; gi++) {
            int base = gi * GROUP_STRIDE;
            Group g = groups[gi];

            int planeStart = groupRam[base + 0] & 0x07;
            int planeCount = groupRam[base + 1] & 0xFF;
            if (planeCount > 8) { planeCount = 8; cfgErr = true; }
            if (planeCount != 0 && planeStart + planeCount > 8) { planeCount = 8 - planeStart; cfgErr = true; }

            int flags = groupRam[base + 7] & 0xFF;

            g.en = planeCount != 0;
            g.planeStart = planeStart;
            g.planeCount = planeCount;
            g.palBase = groupRam[base + 2] & 0xFF;
            g.scrollX = (short) u16(groupRam[base + 3], groupRam[base + 4]);

            int sy = groupRam[base + 5] & 0xFF;
            // Hardening: if WRAP_Y is off, reject 200..255 garbage (likely a sign-extension bug in ROM).
            if ((flags & GF_WRAP_Y) == 0 && sy >= 200) { sy = 199; cfgErr = true; }
            g.scrollY = sy;

            g.priority = groupRam[base + 6] & 0xFF;
            g.flags = flags;
            g.bplOfs = u16(groupRam[base + 8], groupRam[base + 9]) & 0x3FFF;

            if (!g.en) {
                g.planesMask = 0;
            } else {
                g.planesMask = ((1 << planeCount) - 1) << planeStart;
            }
        }

        // Sort group indices by priority (ascending), stable.
        int n = 0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            groupOrder[n++] = i;
        }
        // insertion sort for 4 items
        for (int i = 1; i < n; i++) {
            int key = groupOrder[i];
            int kp = groups[key].priority;
            int j = i - 1;
            while (j >= 0) {
                int gi = groupOrder[j];
                if (groups[gi].priority <= kp) break;
                groupOrder[j + 1] = gi;
                j--;
            }
            groupOrder[j + 1] = key;
        }

        // Enforce overlap rule: disable any front (later) group that overlaps already-claimed planes.
        int used = 0;
        groupOrderCount = 0;
        for (int oi = 0; oi < n; oi++) {
            int gi = groupOrder[oi];
            Group g = groups[gi];
            if (!g.en) continue;
            if ((used & g.planesMask) != 0) {
                g.en = false;
                cfgErr = true;
                continue;
            }
            used |= g.planesMask;
            groupOrder[groupOrderCount++] = gi;
        }

        if (cfgErr) {
            status |= STATUS_CFG_ERR;
        }
    }

    // =====================================================================
    // Sprites (tables bank OAM)
    // =====================================================================

    private void buildSpritePrioritySlots() {
        Arrays.fill(sprSlotCount, 0);
        frameAnyCollide = 0;

        // Collect active group priorities (ascending) for slot boundaries.
        int gpN = groupOrderCount;
        int[] gp = gpPrioTmp;
        for (int i = 0; i < gpN; i++) gp[i] = groups[groupOrder[i]].priority & 0xFF;

        // Bucket sprites by priority relative to group priorities.
        for (int si = 0; si < SPR_COUNT; si++) {
            int o = OAM_OFS + si * SPR_STRIDE;
            int attr = tblBank[(o + 8) & 0x3FFF] & 0xFF;
            if ((attr & SA_EN) == 0) continue;

            int sprPri = tblBank[(o + 11) & 0x3FFF] & 0xFF;
            int slot = 0;
            while (slot < gpN && sprPri >= gp[slot]) slot++;
            if (slot > 4) slot = 4;

            int c = sprSlotCount[slot];
            if (c < SPR_COUNT) {
                sprSlots[slot][c] = si;
                sprSlotCount[slot] = c + 1;
            } else {
                // Corrupt OAM can't crash us; signal error and stop collecting.
                status |= STATUS_CFG_ERR;
                break;
            }

            if ((attr & SA_COLLIDE) != 0) frameAnyCollide = 1;
        }
    }

    private void beginSpriteCollisionScanline() {
        if (++colStamp == 0) {
            Arrays.fill(colLineGen, 0);
            colStamp = 1;
        }
    }

    private void beginSpriteVisitedScanline() {
        if (++sprStamp == 0) {
            Arrays.fill(sprLineGen, 0);
            sprStamp = 1;
        }
    }

    private void markSpriteHit(int spriteIndex) {
        int bi = spriteIndex >>> 3;
        int bit = spriteIndex & 7;
        colHit[bi] = (byte) ((colHit[bi] & 0xFF) | (1 << bit));
    }

    private void renderSpritesScanlineSlot(int yOut, int slot) {
        if (slot < 0 || slot > 4) return;
        int n = sprSlotCount[slot];
        if (n <= 0) return;

        final int ySrc = (yOut >>> 1);
        if (ySrc >= 200) return;
        final int W = config.width();
        final int rowOfs = yOut * W;

        for (int li = 0; li < n; li++) {
            if (sprBudget <= 0) return;
            int si = sprSlots[slot][li];
            renderOneSpriteScanline(ySrc, rowOfs, W, si);
        }
    }

    private void renderOneSpriteScanline(int ySrc, int rowOfs, int W, int si) {
        // Dedup: a sprite might be reached both as a head (slot list) and via CHAIN.
        if (sprLineGen[si] == sprStamp) return;
        sprLineGen[si] = sprStamp;

        final int o = OAM_OFS + (si * SPR_STRIDE);

        // OAM reads: OAM is a fixed 4KB window, so we can skip &0x3FFF masking in the hot path.
        final byte[] oam = tblBank;
        final int attr = oam[o + 8] & 0xFF;
        if ((attr & SA_EN) == 0) return;

        int x = (short) u16(oam[o + 0], oam[o + 1]);
        int y = (short) u16(oam[o + 2], oam[o + 3]);
        int w = oam[o + 4] & 0xFF;
        int h = oam[o + 5] & 0xFF;
        int dataOfs = u16(oam[o + 6], oam[o + 7]) & 0x3FFF;
        int pal = oam[o + 9] & 0x0F;
        int colId = oam[o + 10] & 0x0F;

        int xstep = u16(oam[o + 12], oam[o + 13]) & 0xFFFF;
        int ystep = u16(oam[o + 14], oam[o + 15]) & 0xFFFF;
        int tiltDx = (byte) oam[o + 16];

        if (w == 0) w = 256;
        if (h == 0) h = 256;
        if (xstep == 0) xstep = 0x0100;
        if (ystep == 0) ystep = 0x0100;

        final int dy = ySrc - y;
        if (dy < 0) return;

        // y scaling (8.8): do it in long to avoid overflow if Y is negative / large.
        int srcRow = (ystep == 0x0100) ? dy : (int) (((long) dy * (long) ystep) >>> 8);
        if (srcRow < 0 || srcRow >= h) return;

        final boolean hflip = (attr & SA_HFLIP) != 0;
        final boolean vflip = (attr & SA_VFLIP) != 0;
        final boolean tilt = (attr & SA_TILT) != 0;

        if (vflip) srcRow = h - 1 - srcRow;

        final int rowStride = (w + 1) >> 1; // bytes per source row
        final int rowBase = (dataOfs + srcRow * rowStride) & 0x3FFF;

        final int xStart = x + (tilt ? (tiltDx * srcRow) : 0);

        // Estimate output width (in output pixels) from XSTEP.
        int outWidth = (int) (((((long) w) << 8) + xstep - 1L) / (long) xstep);
        if (outWidth <= 0) return;
        if (outWidth > SPR_MAX_OUT_PIX) outWidth = SPR_MAX_OUT_PIX;

        // Fast reject if fully off-screen.
        if (xStart >= W) return;
        if (xStart + outWidth <= 0) return;

        // Clip in output space by choosing the output-pixel range [iStart, iEnd).
        int iStart = (xStart < 0) ? -xStart : 0;
        int iEnd = outWidth;
        int maxRight = W - xStart;
        if (iEnd > maxRight) iEnd = maxRight;
        if (iEnd <= iStart) return;

        final int palBase = (pal & 0x0F) << 4;
        final int[] palA = palArgb;
        final int[] rb = renderBuf;
        final byte[] sb = sprBank;

        final boolean collide = (frameAnyCollide != 0) && ((attr & SA_COLLIDE) != 0);

        int outX = xStart + iStart;
        int prevByteIdx = -1;
        int cachedByte = 0;

        // Common case: 1:1 horizontal scaling (xstep = 1.0).
        if (xstep == 0x0100) {
            if (!hflip) {
                for (int sx = iStart; sx < iEnd; sx++, outX++) {
                    int px = sx;
                    int byteIdx = (rowBase + (px >>> 1)) & 0x3FFF;
                    if (byteIdx != prevByteIdx) {
                        cachedByte = sb[byteIdx] & 0xFF;
                        prevByteIdx = byteIdx;
                    }
                    int pix = (cachedByte >>> (((px & 1) ^ 1) << 2)) & 0x0F;
                    if (pix == 0) continue;

                    int argb = palA[palBase | pix];
                    rb[rowOfs + outX] = argb;

                    if (collide) {
                        if (colLineGen[outX] == colStamp) {
                            int existingId = colLineId[outX] & 0xFF;
                            if (existingId != 0xFF && existingId != colId) {
                                int other = colLineSpr[outX] & 0xFF;
                                if (other < SPR_COUNT) markSpriteHit(other);
                                markSpriteHit(si);
                            }
                        }
                        colLineGen[outX] = colStamp;
                        colLineId[outX] = (byte) (colId & 0x0F);
                        colLineSpr[outX] = (byte) (si & 0xFF);
                    }
                }
            } else {
                for (int sx = iStart; sx < iEnd; sx++, outX++) {
                    int px = (w - 1) - sx;
                    int byteIdx = (rowBase + (px >>> 1)) & 0x3FFF;
                    if (byteIdx != prevByteIdx) {
                        cachedByte = sb[byteIdx] & 0xFF;
                        prevByteIdx = byteIdx;
                    }
                    int pix = (cachedByte >>> (((px & 1) ^ 1) << 2)) & 0x0F;
                    if (pix == 0) continue;

                    int argb = palA[palBase | pix];
                    rb[rowOfs + outX] = argb;

                    if (collide) {
                        if (colLineGen[outX] == colStamp) {
                            int existingId = colLineId[outX] & 0xFF;
                            if (existingId != 0xFF && existingId != colId) {
                                int other = colLineSpr[outX] & 0xFF;
                                if (other < SPR_COUNT) markSpriteHit(other);
                                markSpriteHit(si);
                            }
                        }
                        colLineGen[outX] = colStamp;
                        colLineId[outX] = (byte) (colId & 0x0F);
                        colLineSpr[outX] = (byte) (si & 0xFF);
                    }
                }
            }
        } else {
            long acc = (long) iStart * (long) xstep;
            for (int i = iStart; i < iEnd; i++, outX++, acc += xstep) {
                int sx = (int) (acc >>> 8);
                if (sx >= w) break;
                int px = hflip ? (w - 1 - sx) : sx;

                int byteIdx = (rowBase + (px >>> 1)) & 0x3FFF;
                if (byteIdx != prevByteIdx) {
                    cachedByte = sb[byteIdx] & 0xFF;
                    prevByteIdx = byteIdx;
                }
                int pix = (cachedByte >>> (((px & 1) ^ 1) << 2)) & 0x0F;
                if (pix == 0) continue;

                int argb = palA[palBase | pix];
                rb[rowOfs + outX] = argb;

                if (collide) {
                    if (colLineGen[outX] == colStamp) {
                        int existingId = colLineId[outX] & 0xFF;
                        if (existingId != 0xFF && existingId != colId) {
                            int other = colLineSpr[outX] & 0xFF;
                            if (other < SPR_COUNT) markSpriteHit(other);
                            markSpriteHit(si);
                        }
                    }
                    colLineGen[outX] = colStamp;
                    colLineId[outX] = (byte) (colId & 0x0F);
                    colLineSpr[outX] = (byte) (si & 0xFF);
                }
            }
        }

        // Chaining: follow LINK immediately. Dedup + guard keeps the scanline time bounded.
        if ((attr & SA_CHAIN) != 0) {
            int link = oam[o + 17] & 0x7F;
            int guard = 0;
            int next = link;
            while (next != si && guard++ < SPR_COUNT) {
                renderOneSpriteScanline(ySrc, rowOfs, W, next);
                int o2 = OAM_OFS + (next * SPR_STRIDE);
                int a2 = oam[o2 + 8] & 0xFF;
                if ((a2 & SA_CHAIN) == 0) break;
                next = oam[o2 + 17] & 0x7F;
            }
        }
    }


    // =====================================================================
    // Text overlay
    // =====================================================================

    private void rebuildPairArgb() {
        for (int i = 0; i < 256; i++) {
            int left = i & 0x0F;
            int right = (i >>> 4) & 0x0F;
            long lo = palArgb[left] & 0xFFFFFFFFL;
            long hi = ((long) palArgb[right]) << 32;
            pairArgb[i] = hi | lo;
        }
        pairDirty = false;
    }

    private void renderTextScanline(int y, boolean transparentBg, boolean cursorEnabled) {
        final int W = config.width();
        final int rowOfs = y * W;

        final int fineX = txFineX & 0x07;
        final boolean charBlinkMode = (txCtrl & TX_CHAR_BLINK) != 0;
        final boolean blinkPhaseOn = ((frameCounter >> 4) & 1) == 0;

        int yAdj = y + (txFineY & 0x0F);
        if (yAdj >= TEXT_SCANLINES) yAdj -= TEXT_SCANLINES;
        final int ty = (yAdj >> 4);
        final int sub = (yAdj & 0x0F);

        final int tyScreen = (y >> 4);
        final int subScreen = (y & 0x0F);
        final boolean cursorRowActive =
                cursorEnabled && (tyScreen == txCurY) && (subScreen >= txCurStart) && (subScreen <= txCurEnd);

        int cell = txOrigin + (ty * TEXT_COLS);
        cell %= TEXT_CELLS;
        if (cell < 0) cell += TEXT_CELLS;

        final boolean pairFastPossible = (fineX == 0) && !transparentBg && !frameCopperEnabled;
        if (pairFastPossible && pairDirty) rebuildPairArgb();

        for (int col = 0; col < TEXT_COLS; col++) {
            final int cellOfs = cell << 1;
            final int ch = textRam[cellOfs] & 0xFF;
            final int attr = textRam[cellOfs + 1] & 0xFF;

            int fg = attr & 0x0F;
            int bg = (attr >>> 4) & 0x0F;

            int glyphCur = font8x16[(ch << 4) + sub] & 0xFF;
            int attrUsed = attr;
            if (charBlinkMode && (attr & 0x80) != 0) {
                bg &= 0x07;
                attrUsed = (bg << 4) | fg;
                if (!blinkPhaseOn) glyphCur = 0;
            }

            final boolean cursorCell = cursorRowActive && (col == txCurX);
            final int xBase = col << 3;

            if (fineX == 0) {
                if (pairFastPossible) {
                    int pairs = TEXT_PAIR4[(glyphCur << 8) | (attrUsed & 0xFF)];
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
                int chNext = 0x20;
                int attrNext = txAttr;
                if (col + 1 < TEXT_COLS) {
                    int cellN = cell + 1;
                    if (cellN == TEXT_CELLS) cellN = 0;
                    int ofsN = cellN << 1;
                    chNext = textRam[ofsN] & 0xFF;
                    attrNext = textRam[ofsN + 1] & 0xFF;
                }
                int fgN = attrNext & 0x0F;
                int bgN = (attrNext >>> 4) & 0x0F;
                int glyphNext = font8x16[(chNext << 4) + sub] & 0xFF;
                if (charBlinkMode && (attrNext & 0x80) != 0) {
                    bgN &= 0x07;
                    if (!blinkPhaseOn) glyphNext = 0;
                }
                int two = (glyphCur << 8) | glyphNext;
                for (int i = 0; i < 8; i++) {
                    int s = i + fineX;
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

            cell++;
            if (cell == TEXT_CELLS) cell = 0;
        }
    }

    // =====================================================================
    // Text helpers (TX_PORT / TX_CMD)
    // =====================================================================

    private void txCommand(int cmd) {
        int c = cmd & 0xFF;
        if ((c & TXCMD_HOME) != 0) { txCurX = 0; txCurY = 0; }
        if ((c & TXCMD_CLR_EOL) != 0) txClearEol();
        if ((c & TXCMD_CLR_LINE) != 0) txClearLine(txCurY);
        if ((c & TXCMD_CLR_SCREEN) != 0) txClearScreen();
        if ((c & TXCMD_SCROLL_UP) != 0) txScrollUpOneRow();
    }

    private void txPortWrite(int v) {
        int ch = v & 0xFF;
        switch (ch) {
            case 0x0A -> txNewline();
            case 0x0D -> txCurX = 0;
            case 0x08 -> { if (txCurX > 0) txCurX--; }
            case 0x09 -> {
                int nextX = (txCurX + 8) & ~7;
                if (nextX >= TEXT_COLS) txNewline();
                else txCurX = nextX;
            }
            default -> {
                txPutCharAtCursor(ch, txAttr);
                txCurX++;
                if (txCurX >= TEXT_COLS) txNewline();
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
    // Copper (tables bank)
    // =====================================================================

    private static int u16(byte lo, byte hi) {
        return (lo & 0xFF) | ((hi & 0xFF) << 8);
    }

    private void copperCompileIfNeeded() {
        if (!copDirty) return;

        int len = copLen & 0xFFFF;
        if (len <= 0) {
            copCount = 0;
            copDirty = false;
            return;
        }

        int start = copOfs & 0x3FFF;
        if (start >= TBL_BANK_SIZE) start = 0;
        if (start + len > TBL_BANK_SIZE) len = TBL_BANK_SIZE - start;
        len = (len / COPPER_STRIDE) * COPPER_STRIDE;

        int n = 0;
        boolean truncated = false;
        for (int pos = 0; pos + (COPPER_STRIDE - 1) < len; pos += COPPER_STRIDE) {
            if (n >= COP_MAX) { truncated = true; break; }

            int p = start + pos;
            int scan = u16(tblBank[p], tblBank[p + 1]);
            int reg  = u16(tblBank[p + 2], tblBank[p + 3]);
            int vLo  = tblBank[p + 4] & 0xFF;
            int vHi  = tblBank[p + 5] & 0xFF;
            int flags = tblBank[p + 6] & 0xFF;

            if ((flags & COP_FLAG_END) != 0) break;
            if (scan < config.height()) {
                copScan[n]  = scan;
                copReg[n]   = reg;
                copValLo[n] = vLo;
                copValHi[n] = vHi;
                copFlags[n] = flags;
                n++;
            }
        }

        if (truncated) status |= STATUS_CFG_ERR;

        // Counting sort by scanline.
        if (n > 1) {
            final int H = config.height();
            Arrays.fill(copCounts, 0, H, 0);
            for (int i = 0; i < n; i++) copCounts[copScan[i]]++;
            int sum = 0;
            for (int s = 0; s < H; s++) {
                int c = copCounts[s];
                copCounts[s] = sum;
                sum += c;
            }
            for (int i = 0; i < n; i++) {
                int s = copScan[i];
                int dst = copCounts[s]++;
                copScanTmp[dst]  = copScan[i];
                copRegTmp[dst]   = copReg[i];
                copValLoTmp[dst] = copValLo[i];
                copValHiTmp[dst] = copValHi[i];
                copFlagsTmp[dst] = copFlags[i];
            }
            System.arraycopy(copScanTmp, 0, copScan, 0, n);
            System.arraycopy(copRegTmp, 0, copReg, 0, n);
            System.arraycopy(copValLoTmp, 0, copValLo, 0, n);
            System.arraycopy(copValHiTmp, 0, copValHi, 0, n);
            System.arraycopy(copFlagsTmp, 0, copFlags, 0, n);
        }

        copCount = n;
        copDirty = false;
    }

    /**
     * Copper-safe MMIO write: blocks copper from modifying its own control/length/offset
     * registers mid-frame (prevents self-modification loops).
     */
    private void copperWriteMmio(int reg, byte value) {
        int r = reg & 0x3FFF;
        if (r >= REG_COP_CTRL && r <= REG_COP_OFS_H) return;
        writeMmio(r, value);
    }

    private void copperApplyForScanline(int y) {
        while (copIdx < copCount && copScan[copIdx] == y) {
            int reg = copReg[copIdx] & 0x3FFF;
            int flags = copFlags[copIdx];
            int vLo = copValLo[copIdx] & 0xFF;
            int vHi = copValHi[copIdx] & 0xFF;

            if ((flags & COP_FLAG_WRITE16) != 0) {
                copperWriteMmio(reg, (byte) vLo);
                copperWriteMmio((reg + 1) & 0x3FFF, (byte) vHi);
            } else {
                copperWriteMmio(reg, (byte) vLo);
            }
            copIdx++;
        }
    }

    // =====================================================================
    // Blitter (VRAM-only)
    // =====================================================================

    private void bltWriteCtrl(int v) {
        bltCtrl = (v & 0x7F);
        if ((v & BLT_START) != 0) bltStart();
    }

    private void bltStart() {
        if (bltBusy) return;
        bltBusy = true;
        bltCyclesAcc = 0;
        bltSrcCur = bltSrc & 0x3FFF;
        bltDstCur = bltDst & 0x3FFF;

        int w = bltW & 0xFFFF;
        if (w <= 0) w = 1;
        bltWRun = w;

        int h = bltH & 0xFF;
        if (h == 0) h = 1;
        bltHRun = h;

        bltSrcPitchRun = (short) (bltSrcPitch & 0xFFFF);
        bltDstPitchRun = (short) (bltDstPitch & 0xFFFF);

        int pm = bltPlaneMask & 0xFF;
        if (pm == 0) pm = 0xFF;
        bltPlaneMaskRun = pm;

        bltRopRun = bltRop & 0x03;
        bltShiftRun = bltShift & 0x07;

        bltX = 0;
        bltY = 0;

        status &= ~STATUS_BLT;
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
        int srcOfs = bltSrcCur & 0x3FFF;
        int dstOfs = bltDstCur & 0x3FFF;

        if ((bltCtrl & BLT_FILL) != 0) {
            int fill = bltFill & 0xFF;
            for (int p = 0; p < NUM_PLANES; p++) {
                if ((bltPlaneMaskRun & (1 << p)) == 0) continue;
                vram[(p * PLANE_SIZE) + dstOfs] = (byte) fill;
            }
        } else {
            final int rop = bltRopRun & 0x03;
            final int sh  = bltShiftRun & 0x07;
            for (int p = 0; p < NUM_PLANES; p++) {
                if ((bltPlaneMaskRun & (1 << p)) == 0) continue;
                int baseP = p * PLANE_SIZE;
                int s = vram[baseP + srcOfs] & 0xFF;
                if (sh != 0) {
                    int n = vram[baseP + ((srcOfs + 1) & 0x3FFF)] & 0xFF;
                    int w = (s << 8) | n;
                    s = (w >>> (8 - sh)) & 0xFF;
                }
                int d = vram[baseP + dstOfs] & 0xFF;
                int out;
                switch (rop) {
                    case 1 -> out = d | s;
                    case 2 -> out = d & s;
                    case 3 -> out = d ^ s;
                    default -> out = s;
                }
                vram[baseP + dstOfs] = (byte) out;
            }
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
    // JavaFX present
    // =====================================================================

    private void publishRenderedFrame() {
        synchronized (fbLock) {
            // Always publish the freshest frame.
            // If FX hasn't consumed the previous queued frame yet, recycle it so we can queue the new one.
            if (queuedBuf != null) {
                if (freeBuf == null) freeBuf = queuedBuf;
                queuedBuf = null;
            }
            queuedBuf = renderBuf;
            if (freeBuf != null) {
                renderBuf = freeBuf;
                freeBuf = null;
            }
        }
    }

    /** Call from JavaFX thread (e.g., AnimationTimer). */
    public void fxPulse() {
        if (!fxDirty.getAndSet(false)) return;

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

        writer.setPixels(0, 0, config.width(), config.height(), ARGB_FORMAT, pixels, 0, config.width());
        if (gc != null) {
            gc.drawImage(image, 0, 0, config.canvasWidth(), config.canvasHeight());
        }
    }

    // =====================================================================
    // Defaults (text/font/palette)
    // =====================================================================

    private void initDefaultTextRam() {
        for (int i = 0; i < TEXT_CELLS; i++) {
            int ofs = i << 1;
            textRam[ofs] = 0x20;
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
