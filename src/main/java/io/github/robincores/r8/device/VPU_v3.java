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
 * VPU (v3) - Amiga-inspired bitplane video with scanline copper + async blitter,
 * plus convenience overlays: sprites (OAM + 16K sprite bank) and text (80x25@8x16 in MMIO).
 *
 * <h3>Active display</h3>
 * Output framebuffer is always {@code 640x400} (typically presented inside a 640x480 timing with borders).
 * Internally the playfield is one of:
 * <ul>
 *   <li><b>HIRES</b>: 640x200 (1x in X, 2x in Y)</li>
 *   <li><b>LORES</b>: 320x200 (2x in X, 2x in Y)</li>
 * </ul>
 *
 * <h3>Bitplanes</h3>
 * Physical VRAM is {@code 128K} organized as {@code 8 planes x 16K}. Each plane is 1bpp.
 * A pixel index is formed by combining enabled plane bits (plane number is the bit position).
 *
 * <h3>Layers / priority</h3>
 * Layers are composited deterministically per scanline:
 * <ul>
 *   <li>Base playfield: PF0 (and PF1 if dual-playfield enabled)</li>
 *   <li>Sprites: two priority groups (ATTR bit3): back group then front group</li>
 *   <li>Text overlay: always on top when enabled</li>
 * </ul>
 * In dual-playfield mode PF1 overlays PF0 with a transparency key of PF1-index==0.
 *
 * <h3>CPU access</h3>
 * The CPU accesses video memory via the 16K VideoWindow at {@code 0xC000-0xFFFF}:
 * <ul>
 *   <li>WIN_MMIO=1: VPU MMIO page (regs + palette + OAM + copper + text + font)</li>
 *   <li>WIN_MMIO=0: banked memory: vbank 0..7 = VRAM planes, vbank 8 = SPR bank</li>
 * </ul>
 */
public final class VPU_v3 implements Tickable {

    // =====================================================================
    // Memory organization
    // =====================================================================

    public static final int PLANE_SIZE  = 0x4000;  // 16K
    public static final int NUM_PLANES  = 8;
    public static final int VRAM_SIZE   = PLANE_SIZE * NUM_PLANES; // 128K

    /** Extra 16K storage bank for sprite pattern data (tile set). */
    public static final int SPR_BANK_SIZE = 0x4000;

    // =====================================================================
    // MMIO layout (within the 16K window when WIN_MMIO=1)
    // =====================================================================

    // Core
    private static final int REG_CTRL       = 0x0000;
    private static final int REG_STATUS     = 0x0001;
    private static final int REG_MODE       = 0x0002; // bit0 RES: 0=HIRES(640), 1=LORES(320)
    private static final int REG_SCAN_L     = 0x0003; // RO current scanline (full frame)
    private static final int REG_SCAN_H     = 0x0004;
    private static final int REG_FB_BASE_L  = 0x0005; // base offset within each plane (low)
    private static final int REG_FB_BASE_H  = 0x0006; // base offset within each plane (high)

    // Text overlay (kept compatible with v1+)
    private static final int REG_TX_CTRL      = 0x0007;
    private static final int REG_TX_CUR_X     = 0x0008;
    private static final int REG_TX_CUR_Y     = 0x0009;
    private static final int REG_TX_CUR_START = 0x000A;
    private static final int REG_TX_CUR_END   = 0x000B;

    // Global fine X (legacy; added to PF0/PF1 scroll X)
    private static final int REG_XPAN         = 0x000C; // 0..7

    // Plane enable mask (repurpose of old WR_PLANE_MASK)
    private static final int REG_BPL_MASK     = 0x000D; // bits0..7

    // Copper
    private static final int REG_COP_CTRL    = 0x0016; // bit0 enable
    private static final int REG_COP_LEN_L   = 0x0017;
    private static final int REG_COP_LEN_H   = 0x0018;

    // Text scrolling helpers (kept compatible)
    private static final int REG_TX_ORIGIN_L  = 0x0013;
    private static final int REG_TX_ORIGIN_H  = 0x0014;
    private static final int REG_TX_FINE_Y    = 0x0015;
    private static final int REG_TX_FINE_X    = 0x001E;

    // Text programming helpers
    private static final int REG_TX_CMD       = 0x0019;
    private static final int REG_TX_ATTR      = 0x001A;
    private static final int REG_TX_PORT      = 0x001B;

    // Raster IRQ
    private static final int REG_RASTER_CMP_L = 0x001C;
    private static final int REG_RASTER_CMP_H = 0x001D;

    // Blitter (VRAM only)
    private static final int REG_BLT_CTRL        = 0x0020; // bit0 START, bit1 FILL, bit2 IRQ_EN, bit7 BUSY(RO)
    private static final int REG_BLT_SRC_L       = 0x0021;
    private static final int REG_BLT_SRC_H       = 0x0022;
    private static final int REG_BLT_DST_L       = 0x0023;
    private static final int REG_BLT_DST_H       = 0x0024;
    private static final int REG_BLT_W_L         = 0x0025; // bytes per row
    private static final int REG_BLT_W_H         = 0x0026;
    private static final int REG_BLT_H           = 0x0027; // rows (0 => 1)
    private static final int REG_BLT_SRC_PITCH_L = 0x0028; // signed16, added after each row
    private static final int REG_BLT_SRC_PITCH_H = 0x0029;
    private static final int REG_BLT_DST_PITCH_L = 0x002A;
    private static final int REG_BLT_DST_PITCH_H = 0x002B;
    private static final int REG_BLT_FILL        = 0x002C;
    private static final int REG_BLT_PLANE_MASK  = 0x002D; // bits0..7 (0 => all)

    // Playfield layering / parallax
    private static final int REG_PF0_SCROLL_X_L  = 0x0030;
    private static final int REG_PF0_SCROLL_X_H  = 0x0031;
    private static final int REG_PF0_SCROLL_Y    = 0x0032;
    private static final int REG_PF1_SCROLL_X_L  = 0x0033;
    private static final int REG_PF1_SCROLL_X_H  = 0x0034;
    private static final int REG_PF1_SCROLL_Y    = 0x0035;
    /** 0=single playfield, else split plane index (1..7): planes [0..split-1]=PF0, [split..7]=PF1 */
    private static final int REG_PF_SPLIT        = 0x0036;
    /** Palette base (added to playfield index), 0..255 */
    private static final int REG_PF0_PAL_BASE    = 0x0037;
    private static final int REG_PF1_PAL_BASE    = 0x0038;

    // Sprites
    private static final int REG_SPR_CTRL        = 0x0039; // bit0 enable

    // Palette RAM (256 x RGB565 little-endian)
    private static final int PAL_BASE  = 0x0100;
    private static final int PAL_SIZE  = 0x0200;

    // OAM (sprites)
    private static final int OAM_BASE  = 0x0300;
    private static final int OAM_SIZE  = 0x0400; // 1024
    private static final int SPRITE_COUNT  = 128;
    private static final int SPRITE_STRIDE = 8;

    // Copper list RAM
    private static final int COPPER_BASE   = 0x1000;
    private static final int COPPER_SIZE   = 0x1000;
    private static final int COPPER_STRIDE = 8;

    // Text RAM + font RAM (in MMIO)
    private static final int TEXT_COLS   = 80;
    private static final int TEXT_ROWS   = 25;
    private static final int TEXT_CELL_B = 2;
    private static final int TEXT_CELLS  = TEXT_COLS * TEXT_ROWS;
    private static final int TEXT_BASE   = 0x2000;
    private static final int TEXT_SIZE   = TEXT_CELLS * TEXT_CELL_B; // 4000
    private static final int TEXT_SCANLINES = TEXT_ROWS * 16; // 400

    private static final int FONT_BASE   = 0x3000;
    private static final int FONT_SIZE   = 0x1000; // 4096 (256*16)

    // =====================================================================
    // Bits
    // =====================================================================

    // CTRL bits
    private static final int CTRL_ENABLE      = 0x01;
    private static final int CTRL_VBLANK_IRQ  = 0x02;
    private static final int CTRL_RASTER_IRQ  = 0x04;
    private static final int CTRL_GFX_DIS     = 0x08;

    // STATUS bits
    private static final int STATUS_VBLANK = 0x01; // RO timing-owned
    private static final int STATUS_FRAME  = 0x02; // W1C
    private static final int STATUS_RASTER = 0x04; // W1C
    private static final int STATUS_BLT    = 0x08; // W1C

    // TX_CTRL bits
    private static final int TX_EN             = 0x01;
    private static final int TX_CURSOR_EN      = 0x02;
    private static final int TX_TRANSPARENT_BG = 0x04;
    private static final int TX_CURSOR_BLINK   = 0x08;

    // Copper
    private static final int COP_CTRL_EN       = 0x01;
    private static final int COP_FLAG_WRITE16  = 0x01;
    private static final int COP_FLAG_END      = 0x80;

    // TX_CMD
    private static final int TXCMD_CLR_EOL     = 0x01;
    private static final int TXCMD_CLR_LINE    = 0x02;
    private static final int TXCMD_CLR_SCREEN  = 0x04;
    private static final int TXCMD_SCROLL_UP   = 0x08;
    private static final int TXCMD_HOME        = 0x10;

    // Blitter
    private static final int BLT_START   = 0x01;
    private static final int BLT_FILL    = 0x02;
    private static final int BLT_IRQ_EN  = 0x04;
    private static final int BLT_BUSY_RO = 0x80;

    private static final int BLT_CYCLES_PER_BYTE = 2;

    // =====================================================================
    // Wiring
    // =====================================================================

    private final DisplayConfig config;
    private final InterruptSink sink;
    private final int irqBit;

    // Rendering
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
    // State
    // =====================================================================

    private final byte[] vram = new byte[VRAM_SIZE];
    private final byte[] sprBank = new byte[SPR_BANK_SIZE];

    private final short[] pal565 = new short[256];
    private final int[] palArgb  = new int[256];

    private final byte[] oamRam   = new byte[OAM_SIZE];
    private final byte[] copperRam = new byte[COPPER_SIZE];

    private final byte[] textRam  = new byte[TEXT_SIZE];
    private final byte[] font8x16 = new byte[FONT_SIZE];

    // Registers
    private int ctrl;
    private int status;
    private int mode;
    private int fbBase;
    private int scanline;

    private int xPan;
    private int bplMask;

    private int pf0ScrollX;
    private int pf0ScrollY;
    private int pf1ScrollX;
    private int pf1ScrollY;
    private int pfSplit;
    private int pf0PalBase;
    private int pf1PalBase;

    private int sprCtrl;

    // Raster IRQ
    private int rasterCmp;

    // Text
    private int txCtrl;
    private int txCurX, txCurY;
    private int txCurStart, txCurEnd;
    private int txOrigin;
    private int txFineY;
    private int txFineX;
    private int txAttr;

    // Copper
    private int copCtrl;
    private int copLen;
    private boolean copDirty = true;

    private static final int COP_MAX = COPPER_SIZE / COPPER_STRIDE; // 512
    private final int[] copScan  = new int[COP_MAX];
    private final int[] copReg   = new int[COP_MAX];
    private final int[] copValLo = new int[COP_MAX];
    private final int[] copValHi = new int[COP_MAX];
    private final int[] copFlags = new int[COP_MAX];
    private int copCount;
    private int copIdx;

    // Blitter
    private int bltCtrl;
    private int bltSrc, bltDst;
    private int bltW, bltH;
    private int bltSrcPitch, bltDstPitch;
    private int bltFill;
    private int bltPlaneMask;

    private boolean bltBusy;
    private int bltWRun, bltHRun;
    private int bltSrcPitchRun, bltDstPitchRun;
    private int bltPlaneMaskRun;
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

    // Sprite tile format
    private static final int TILE_BYTES = 32;      // 8x8 4bpp
    private static final int TILE_ROW_BYTES = 4;   // 8 pixels / 2 per byte

    public VPU_v3(DisplayConfig config, InterruptSink sink, int irqBit, Canvas canvas) {
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

        reset(false);
    }

    // =====================================================================
    // Reset
    // =====================================================================

    public void reset() { reset(true); }

    public void reset(boolean hard) {
        ctrl = CTRL_ENABLE;
        status = 0;
        mode = 0;          // HIRES
        fbBase = 0;
        xPan = 0;
        bplMask = 0x0F;    // default 4 planes

        pf0ScrollX = pf0ScrollY = 0;
        pf1ScrollX = pf1ScrollY = 0;
        pfSplit = 0;
        pf0PalBase = 0;
        pf1PalBase = 16;

        sprCtrl = 0x01; // sprites enabled by default

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

        copCtrl = 0;
        copLen = 0;
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
        bltCyclesAcc = 0;

        scanline = 0;
        cycleAccum = 0;
        frameCounter = 0;

        if (hard) {
            Arrays.fill(vram, (byte) 0);
            Arrays.fill(sprBank, (byte) 0);
            Arrays.fill(oamRam, (byte) 0);
            Arrays.fill(copperRam, (byte) 0);
            initDefaultPaletteRgb565();
            initDefaultTextRam();
            initDefaultFont8x16();
        }
    }

    // =====================================================================
    // VideoWindow plane/bank access
    // =====================================================================

    /**
     * Read from a bank exposed through the 16K VideoWindow when WIN_MMIO=0.
     * bank 0..7 = VRAM planes, bank 8 = sprite pattern bank.
     */
    public byte readVramPlane(int bank, int offset) {
        int ofs = offset & (PLANE_SIZE - 1);
        if (bank >= 0 && bank < NUM_PLANES) {
            return vram[(bank * PLANE_SIZE) + ofs];
        }
        if (bank == 8) {
            return sprBank[ofs];
        }
        return 0;
    }

    public void writeVramPlane(int bank, int offset, byte value) {
        int ofs = offset & (PLANE_SIZE - 1);
        if (bank >= 0 && bank < NUM_PLANES) {
            vram[(bank * PLANE_SIZE) + ofs] = value;
            return;
        }
        if (bank == 8) {
            sprBank[ofs] = value;
        }
    }

    // =====================================================================
    // MMIO view
    // =====================================================================

    public byte readMmio(int offset) {
        int o = offset & 0x3FFF;

        // Palette
        if (o >= PAL_BASE && o < (PAL_BASE + PAL_SIZE)) {
            int idx = (o - PAL_BASE) >> 1;
            boolean hi = ((o - PAL_BASE) & 1) != 0;
            int v = pal565[idx] & 0xFFFF;
            return (byte) (hi ? ((v >> 8) & 0xFF) : (v & 0xFF));
        }

        // OAM
        if (o >= OAM_BASE && o < (OAM_BASE + OAM_SIZE)) {
            return oamRam[o - OAM_BASE];
        }

        // Copper RAM
        if (o >= COPPER_BASE && o < (COPPER_BASE + COPPER_SIZE)) {
            return copperRam[o - COPPER_BASE];
        }

        // Text + font
        if (o >= TEXT_BASE && o < (TEXT_BASE + TEXT_SIZE)) {
            return textRam[o - TEXT_BASE];
        }
        if (o >= FONT_BASE && o < (FONT_BASE + FONT_SIZE)) {
            return font8x16[o - FONT_BASE];
        }

        return (byte) switch (o) {
            case REG_CTRL -> ctrl;
            case REG_STATUS -> status;
            case REG_MODE -> mode;
            case REG_SCAN_L -> (scanline & 0xFF);
            case REG_SCAN_H -> ((scanline >>> 8) & 0xFF);
            case REG_FB_BASE_L -> (fbBase & 0xFF);
            case REG_FB_BASE_H -> ((fbBase >>> 8) & 0xFF);
            case REG_XPAN -> (xPan & 0x07);
            case REG_BPL_MASK -> (bplMask & 0xFF);

            case REG_PF0_SCROLL_X_L -> (pf0ScrollX & 0xFF);
            case REG_PF0_SCROLL_X_H -> ((pf0ScrollX >>> 8) & 0xFF);
            case REG_PF0_SCROLL_Y -> (pf0ScrollY & 0xFF);
            case REG_PF1_SCROLL_X_L -> (pf1ScrollX & 0xFF);
            case REG_PF1_SCROLL_X_H -> ((pf1ScrollX >>> 8) & 0xFF);
            case REG_PF1_SCROLL_Y -> (pf1ScrollY & 0xFF);
            case REG_PF_SPLIT -> (pfSplit & 0x07);
            case REG_PF0_PAL_BASE -> (pf0PalBase & 0xFF);
            case REG_PF1_PAL_BASE -> (pf1PalBase & 0xFF);

            case REG_SPR_CTRL -> (sprCtrl & 0xFF);

            case REG_TX_CTRL -> txCtrl;
            case REG_TX_CUR_X -> txCurX;
            case REG_TX_CUR_Y -> txCurY;
            case REG_TX_CUR_START -> txCurStart;
            case REG_TX_CUR_END -> txCurEnd;

            case REG_TX_ORIGIN_L -> (txOrigin & 0xFF);
            case REG_TX_ORIGIN_H -> ((txOrigin >>> 8) & 0xFF);
            case REG_TX_FINE_Y -> (txFineY & 0x0F);
            case REG_TX_FINE_X -> (txFineX & 0x07);

            case REG_TX_CMD -> 0;
            case REG_TX_ATTR -> (txAttr & 0xFF);
            case REG_TX_PORT -> 0;

            case REG_RASTER_CMP_L -> (rasterCmp & 0xFF);
            case REG_RASTER_CMP_H -> ((rasterCmp >>> 8) & 0xFF);

            case REG_COP_CTRL -> (copCtrl & 0xFF);
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
            case REG_BLT_PLANE_MASK -> (bltPlaneMask & 0xFF);

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
            return;
        }

        // OAM
        if (o >= OAM_BASE && o < (OAM_BASE + OAM_SIZE)) {
            oamRam[o - OAM_BASE] = (byte) v;
            return;
        }

        // Copper RAM
        if (o >= COPPER_BASE && o < (COPPER_BASE + COPPER_SIZE)) {
            copperRam[o - COPPER_BASE] = (byte) v;
            copDirty = true;
            return;
        }

        // Text + font
        if (o >= TEXT_BASE && o < (TEXT_BASE + TEXT_SIZE)) {
            textRam[o - TEXT_BASE] = (byte) v;
            return;
        }
        if (o >= FONT_BASE && o < (FONT_BASE + FONT_SIZE)) {
            font8x16[o - FONT_BASE] = (byte) v;
            return;
        }

        switch (o) {
            case REG_CTRL -> ctrl = (v & 0xFF);
            case REG_STATUS -> {
                int w1c = v & (STATUS_FRAME | STATUS_RASTER | STATUS_BLT);
                status &= ~w1c;
            }
            case REG_MODE -> mode = (v & 0x01);
            case REG_FB_BASE_L -> fbBase = (fbBase & 0xFF00) | v;
            case REG_FB_BASE_H -> fbBase = (fbBase & 0x00FF) | (v << 8);
            case REG_XPAN -> xPan = (v & 0x07);
            case REG_BPL_MASK -> bplMask = (v & 0xFF);

            case REG_PF0_SCROLL_X_L -> pf0ScrollX = (pf0ScrollX & 0xFF00) | v;
            case REG_PF0_SCROLL_X_H -> pf0ScrollX = (pf0ScrollX & 0x00FF) | (v << 8);
            case REG_PF0_SCROLL_Y -> pf0ScrollY = (v & 0xFF);
            case REG_PF1_SCROLL_X_L -> pf1ScrollX = (pf1ScrollX & 0xFF00) | v;
            case REG_PF1_SCROLL_X_H -> pf1ScrollX = (pf1ScrollX & 0x00FF) | (v << 8);
            case REG_PF1_SCROLL_Y -> pf1ScrollY = (v & 0xFF);
            case REG_PF_SPLIT -> pfSplit = (v & 0x07);
            case REG_PF0_PAL_BASE -> pf0PalBase = (v & 0xFF);
            case REG_PF1_PAL_BASE -> pf1PalBase = (v & 0xFF);

            case REG_SPR_CTRL -> sprCtrl = (v & 0xFF);

            case REG_TX_CTRL -> txCtrl = (v & 0xFF);
            case REG_TX_CUR_X -> txCurX = Math.min(v, TEXT_COLS - 1);
            case REG_TX_CUR_Y -> txCurY = Math.min(v, TEXT_ROWS - 1);
            case REG_TX_CUR_START -> txCurStart = (v & 0x0F);
            case REG_TX_CUR_END -> txCurEnd = (v & 0x0F);

            case REG_TX_ORIGIN_L -> { txOrigin = (txOrigin & 0xFF00) | v; normalizeTxOrigin(); }
            case REG_TX_ORIGIN_H -> { txOrigin = (txOrigin & 0x00FF) | (v << 8); normalizeTxOrigin(); }
            case REG_TX_FINE_Y -> txFineY = (v & 0x0F);
            case REG_TX_FINE_X -> txFineX = (v & 0x07);

            case REG_TX_CMD -> txCommand(v);
            case REG_TX_ATTR -> txAttr = (v & 0xFF);
            case REG_TX_PORT -> txPortWrite(v);

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
            case REG_BLT_PLANE_MASK -> bltPlaneMask = (v & 0xFF);

            default -> { /* ignore */ }
        }
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

            if (((ctrl & CTRL_RASTER_IRQ) != 0) && (scanline == (rasterCmp & 0xFFFF))) {
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

                if ((ctrl & CTRL_VBLANK_IRQ) != 0) {
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
        frameCopperEnabled = (copCtrl & COP_CTRL_EN) != 0;
        frameLiveRender = (ctrl & CTRL_RASTER_IRQ) != 0;
        if (frameCopperEnabled) copperCompileIfNeeded();

        boolean blinkPhaseOn = ((txCtrl & TX_CURSOR_BLINK) == 0) || (((frameCounter >> 4) & 1) == 0);
        frameCursorEnabled = ((txCtrl & TX_CURSOR_EN) != 0) && blinkPhaseOn;

        // reset copper cursor for this frame
        copIdx = 0;
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    private void renderFrameIntoBackBuffer() {
        final int H = config.height();
        for (int y = 0; y < H; y++) {
            renderScanlineIntoBackBuffer(y);
        }
        publishRenderedFrame();
    }

    private void renderScanlineIntoBackBuffer(int y) {
        if (frameCopperEnabled) copperApplyForScanline(y);

        final int W = config.width();
        final int rowOfs = y * W;

        final boolean textOn = (txCtrl & TX_EN) != 0;
        final boolean transparentBg = (txCtrl & TX_TRANSPARENT_BG) != 0;

        // Double-scan optimization is only safe when:
        //  - copper is off (no mid-scanline register writes)
        //  - live scanline rendering is off (no mid-frame register changes expected)
        //  - this is the 2nd line of the doubled pair
        final boolean canCopyDoubledUnderlay =
                !frameCopperEnabled && !frameLiveRender && ((y & 1) == 1);

        if (canCopyDoubledUnderlay) {
            // Copy the already-rendered previous line as an approximation of the *underlay*.
            // BUT: previous line includes text (if text was enabled), so we must handle overlay carefully.

            System.arraycopy(renderBuf, (y - 1) * W, renderBuf, rowOfs, W);

            if (!textOn) {
                // If text is OFF, copying is only correct if text was also off on y-1.
                // Because we only allow this path when !frameLiveRender, txCtrl is stable across the frame.
                return;
            }

            if (!transparentBg) {
                // OPAQUE text: safe to redraw text for this scanline (it overwrites every pixel in each cell).
                renderTextScanline(y, false, frameCursorEnabled);
                return;
            }

            // TRANSPARENT text: copying would leave "old" pixels where glyph bits are 0.
            // Fall through to full render for correctness.
        }

        // ---- Full render path (always correct) ----

        if ((ctrl & CTRL_GFX_DIS) != 0) {
            Arrays.fill(renderBuf, rowOfs, rowOfs + W, palArgb[0]);
        } else {
            renderPlayfieldsScanline(y);
        }

        // Sprites
        if ((sprCtrl & 0x01) != 0) {
            if (pfSplit != 0) {
                // Back sprites were drawn inside renderPlayfieldsScanline (between PF0/PF1)
                renderSpritesScanline(y, 1);     // Front group
            } else {
                renderSpritesScanline(y, -1);    // All sprites above single PF
            }
        }

        // Text overlay (always top)
        if (textOn) {
            renderTextScanline(y, transparentBg, frameCursorEnabled);
        }
    }

    private void renderPlayfieldsScanline(int yOut) {
        final int W = 640;
        final int ySrc = (yOut >> 1);
        if (ySrc < 0 || ySrc >= 200) {
            Arrays.fill(renderBuf, yOut * W, yOut * W + W, palArgb[0]);
            return;
        }

        final boolean lores = (mode & 0x01) != 0;
        final int widthSrc = lores ? 320 : 640;
        final int bpl = lores ? 40 : 80;

        final int base = fbBase & 0x3FFF;

        final int rowOfs = yOut * W;

        final int split = pfSplit & 0x07;
        final boolean dual = split != 0;

        // effective scrolls with legacy xPan applied
        int fx = xPan & 0x07;

        final int pf0SX = (pf0ScrollX + fx) % widthSrc;
        final int pf1SX = (pf1ScrollX + fx) % widthSrc;

        final int pf0SY = mod200(ySrc + pf0ScrollY);
        final int pf1SY = mod200(ySrc + pf1ScrollY);

        final int pf0ByteOfs = (pf0SX >> 3) % bpl;
        final int pf1ByteOfs = (pf1SX >> 3) % bpl;
        final int pf0Shift = pf0SX & 7;
        final int pf1Shift = pf1SX & 7;

        final int pf0ShiftAmt = 8 - pf0Shift;
        final int pf1ShiftAmt = 8 - pf1Shift;

        // Precompute which planes are enabled
        final int mask = bplMask & 0xFF;

        // Scratch arrays reused per scanline (avoid per-byte allocations)
        final int[] pb0 = new int[NUM_PLANES];
        final int[] pb1 = new int[NUM_PLANES];

        // PF0 first
        for (int bx = 0; bx < bpl; bx++) {
            int srcBx0 = bx + pf0ByteOfs;
            if (srcBx0 >= bpl) srcBx0 -= bpl;
            int srcBx0N = (srcBx0 + 1 == bpl) ? 0 : (srcBx0 + 1);

            int lineBase0 = (base + pf0SY * bpl) & 0x3FFF;
            int oc0 = (lineBase0 + srcBx0) & 0x3FFF;
            int on0 = (lineBase0 + srcBx0N) & 0x3FFF;

            // aligned plane bytes for this byte group (PF0)
            Arrays.fill(pb0, 0);
            for (int p = 0; p < NUM_PLANES; p++) {
                if ((mask & (1 << p)) == 0) continue;
                int baseP = p * PLANE_SIZE;
                int cur = vram[baseP + oc0] & 0xFF;
                if (pf0Shift == 0) {
                    pb0[p] = cur;
                } else {
                    int nxt = vram[baseP + on0] & 0xFF;
                    int w = (cur << 8) | nxt;
                    pb0[p] = (w >>> pf0ShiftAmt) & 0xFF;
                }
            }

            int outX = lores ? (bx * 16) : (bx * 8);
            // 8 pixels
            for (int i = 0; i < 8; i++) {
                int bit = 7 - i;

                int idx0 = 0;

                if (!dual) {
                    // single playfield: use plane number as bit position
                    for (int p = 0; p < NUM_PLANES; p++) {
                        if ((mask & (1 << p)) == 0) continue;
                        idx0 |= ((pb0[p] >>> bit) & 1) << p;
                    }
                    int argb = palArgb[idx0 & 0xFF];
                    if (lores) {
                        int dst = rowOfs + outX + (i << 1);
                        renderBuf[dst] = argb;
                        renderBuf[dst + 1] = argb;
                    } else {
                        renderBuf[rowOfs + outX + i] = argb;
                    }
                } else {
                    // dual playfield: PF0 uses planes [0..split-1]
                    for (int p = 0; p < split; p++) {
                        if ((mask & (1 << p)) == 0) continue;
                        idx0 |= ((pb0[p] >>> bit) & 1) << p;
                    }

                    int c0 = palArgb[(pf0PalBase + idx0) & 0xFF];
                    int outIndex;
                    if (lores) {
                        outIndex = rowOfs + outX + (i << 1);
                        renderBuf[outIndex] = c0;
                        renderBuf[outIndex + 1] = c0;
                    } else {
                        outIndex = rowOfs + outX + i;
                        renderBuf[outIndex] = c0;
                    }

                    // PF1 is drawn in a second pass below (after back sprites).
                }
            }
        }

        if (!dual) return;

        // Back sprites between PF0 and PF1
        if ((sprCtrl & 0x01) != 0) {
            renderSpritesScanline(yOut, 0);
        }

        // PF1 overlay pass (uses its own scroll)
        for (int bx = 0; bx < bpl; bx++) {
            int srcBx1 = bx + pf1ByteOfs;
            if (srcBx1 >= bpl) srcBx1 -= bpl;
            int srcBx1N = (srcBx1 + 1 == bpl) ? 0 : (srcBx1 + 1);

            int lineBase1 = (base + pf1SY * bpl) & 0x3FFF;
            int oc1 = (lineBase1 + srcBx1) & 0x3FFF;
            int on1 = (lineBase1 + srcBx1N) & 0x3FFF;

            Arrays.fill(pb1, 0);
            for (int p = split; p < NUM_PLANES; p++) {
                if ((mask & (1 << p)) == 0) continue;
                int baseP = p * PLANE_SIZE;
                int cur = vram[baseP + oc1] & 0xFF;
                if (pf1Shift == 0) {
                    pb1[p] = cur;
                } else {
                    int nxt = vram[baseP + on1] & 0xFF;
                    int w = (cur << 8) | nxt;
                    pb1[p] = (w >>> pf1ShiftAmt) & 0xFF;
                }
            }

            int outX = lores ? (bx * 16) : (bx * 8);
            for (int i = 0; i < 8; i++) {
                int bit = 7 - i;
                int idx1 = 0;
                for (int p = split; p < NUM_PLANES; p++) {
                    if ((mask & (1 << p)) == 0) continue;
                    idx1 |= ((pb1[p] >>> bit) & 1) << (p - split);
                }
                if (idx1 == 0) continue; // PF1 transparency key

                int c1 = palArgb[(pf1PalBase + idx1) & 0xFF];
                if (lores) {
                    int dst = rowOfs + outX + (i << 1);
                    renderBuf[dst] = c1;
                    renderBuf[dst + 1] = c1;
                } else {
                    renderBuf[rowOfs + outX + i] = c1;
                }
            }
        }
    }

    private static int mod200(int v) {
        v %= 200;
        if (v < 0) v += 200;
        return v;
    }

    // =====================================================================
    // Sprites
    // =====================================================================

    /**
     * OAM entry format (8 bytes per sprite):
     * <pre>
     * [0] Y low
     * [1] X low
     * [2] TILE index (0..255)
     * [3] ATTR:
     *     bit0 EN
     *     bit1 HFLIP
     *     bit2 VFLIP
     *     bit3 PRIO (0=back group, 1=front group)
     *     bits4-7 PAL (0..15): final CLUT = (PAL<<4) | pixNibble
     * [4] XYHI: bits0-1 X[9:8], bits2-3 Y[9:8]
     * [5..7] reserved
     * </pre>
     * Sprite pattern bank (vbank=8) stores 8x8 4bpp packed tiles, 32 bytes each:
     * high nibble = left pixel, low nibble = right pixel; nibble 0 is transparent.
     */
    private void renderSpritesScanline(int yOut, int prioGroup) {
        final int ySrc = (yOut >> 1);
        if (ySrc < 0 || ySrc >= 200) return;

        final boolean lores = (mode & 0x01) != 0;
        final int scaleX = lores ? 2 : 1;
        final int W = 640;
        final int rowOfs = yOut * W;

        for (int i = 0; i < SPRITE_COUNT; i++) {
            int o = i * SPRITE_STRIDE;
            int yLo = oamRam[o] & 0xFF;
            int xLo = oamRam[o + 1] & 0xFF;
            int tile = oamRam[o + 2] & 0xFF;
            int attr = oamRam[o + 3] & 0xFF;
            int xyhi = oamRam[o + 4] & 0xFF;

            if ((attr & 0x01) == 0) continue;

            int sprPrio = (attr >>> 3) & 1;
            if (prioGroup >= 0 && sprPrio != prioGroup) continue;

            int x = xLo | ((xyhi & 0x03) << 8);
            int y = yLo | ((xyhi & 0x0C) << 6);

            int dy = ySrc - y;
            if (dy < 0 || dy >= 8) continue;

            boolean hflip = (attr & 0x02) != 0;
            boolean vflip = (attr & 0x04) != 0;
            if (vflip) dy = 7 - dy;

            int palBase = (attr >>> 4) << 4;
            int tileBase = tile * TILE_BYTES;
            int rowBase = tileBase + (dy * TILE_ROW_BYTES);

            int xOut0 = x * scaleX;
            if (xOut0 >= W || (xOut0 + (8 * scaleX) - 1) < 0) continue;

            for (int tx = 0; tx < 8; tx++) {
                int px = hflip ? (7 - tx) : tx;
                int b = sprBank[rowBase + (px >> 1)] & 0xFF;
                int pix = ((px & 1) == 0) ? ((b >>> 4) & 0x0F) : (b & 0x0F);
                if (pix == 0) continue;

                int argb = palArgb[(palBase | pix) & 0xFF];

                int outX = (x + tx) * scaleX;
                if (outX < 0) continue;
                if (outX >= W) break;

                int dst = rowOfs + outX;
                if (scaleX == 2) {
                    if (dst < rowOfs + W) renderBuf[dst] = argb;
                    if (dst + 1 < rowOfs + W) renderBuf[dst + 1] = argb;
                } else {
                    renderBuf[dst] = argb;
                }
            }
        }
    }

    // =====================================================================
    // Text overlay
    // =====================================================================

    private void renderTextScanline(int y, boolean transparentBg, boolean cursorEnabled) {
        final int W = 640;
        final int rowOfs = y * W;

        int fineX = txFineX & 0x07;

        int yAdj = y + (txFineY & 0x0F);
        if (yAdj >= TEXT_SCANLINES) yAdj -= TEXT_SCANLINES;

        int ty = (yAdj >> 4);
        int sub = (yAdj & 0x0F);

        int tyScreen = (y >> 4);
        int subScreen = (y & 0x0F);
        boolean cursorRowActive = cursorEnabled && (tyScreen == txCurY)
                && (subScreen >= txCurStart) && (subScreen <= txCurEnd);

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
            int bg = (attr >>> 4) & 0x0F;

            int glyphCur = font8x16[(ch << 4) + sub] & 0xFF;
            boolean cursorCell = cursorRowActive && (tx == txCurX);

            int xBase = tx << 3;

            if (fineX == 0) {
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
            } else {
                // Carry from next cell
                int chNext = 0x20, attrNext = txAttr;
                if (tx + 1 < TEXT_COLS) {
                    int cellN = cell + 1;
                    if (cellN >= TEXT_CELLS) cellN -= TEXT_CELLS;
                    int ofsN = cellN << 1;
                    chNext = textRam[ofsN] & 0xFF;
                    attrNext = textRam[ofsN + 1] & 0xFF;
                }

                int fgN = (attrNext & 0x0F);
                int bgN = (attrNext >>> 4) & 0x0F;

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
            case 0x0A -> txNewline();
            case 0x0D -> txCurX = 0;
            case 0x08 -> { if (txCurX > 0) txCurX--; }
            case 0x09 -> {
                txCurX = (txCurX + 8) & ~7;
                if (txCurX >= TEXT_COLS) txNewline();
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
    // Copper
    // =====================================================================

    private static int u16(byte lo, byte hi) {
        return (lo & 0xFF) | ((hi & 0xFF) << 8);
    }

    private void copperCompileIfNeeded() {
        if (!copDirty) return;

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
                copScan[n]  = scan;
                copReg[n]   = reg;
                copValLo[n] = vLo;
                copValHi[n] = vHi;
                copFlags[n] = flags;
                n++;
            }
        }

        // Sort by scanline (stable), using counting sort because scanline range is small (<=400).
        if (n > 0) {
            int H = config.height();
            int[] counts = new int[H];
            for (int i = 0; i < n; i++) counts[copScan[i]]++;

            int sum = 0;
            for (int s = 0; s < H; s++) {
                int c = counts[s];
                counts[s] = sum;
                sum += c;
            }

            int[] scan2 = new int[n];
            int[] reg2 = new int[n];
            int[] lo2 = new int[n];
            int[] hi2 = new int[n];
            int[] fl2 = new int[n];

            for (int i = 0; i < n; i++) {
                int s = copScan[i];
                int dst = counts[s]++;
                scan2[dst] = copScan[i];
                reg2[dst] = copReg[i];
                lo2[dst] = copValLo[i];
                hi2[dst] = copValHi[i];
                fl2[dst] = copFlags[i];
            }

            System.arraycopy(scan2, 0, copScan, 0, n);
            System.arraycopy(reg2, 0, copReg, 0, n);
            System.arraycopy(lo2, 0, copValLo, 0, n);
            System.arraycopy(hi2, 0, copValHi, 0, n);
            System.arraycopy(fl2, 0, copFlags, 0, n);
        }

        copCount = n;
        copDirty = false;
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
            for (int p = 0; p < NUM_PLANES; p++) {
                if ((bltPlaneMaskRun & (1 << p)) == 0) continue;
                int baseP = p * PLANE_SIZE;
                vram[baseP + dstOfs] = vram[baseP + srcOfs];
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
            if (queuedBuf != null && freeBuf == null) {
                // drop (avoid allocation)
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
        gc.drawImage(image, 0, 0, config.canvasWidth(), config.canvasHeight());
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

    // IBM VGA / CP437 8x16 font (base64). (Reused for convenience.)
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
