package io.github.robincores.r8.demo;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4_1;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Random;

/**
 * VPU v4.1 — Demoscene Blitter Showcase (STABLE)
 *
 * Key fix vs “improved but not fixed”:
 *  - The XOR scanner is now non-destructive: we SAVE-UNDER the affected bytes (plane2),
 *    XOR-draw the bar, and next frame we RESTORE those bytes before moving the bar.
 *    This prevents accumulation even if RECTBLIT/sprites/other ops touch plane2.
 */
public class Vpu4_1Showcase2 extends Application {

    // =====================================================================
    //  Display / timing
    // =====================================================================

    private static final DisplayConfig CFG = new DisplayConfig(640, 400, 640, 400, 400, 449, 400);
    private static final int CPF = CFG.cyclesPerFrame();

    // Logical LORES framebuffer
    private static final int LW  = 320;
    private static final int LH  = 200;
    private static final int BPR = LW / 8; // 40 bytes/row

    // =====================================================================
    //  Banks
    // =====================================================================

    private static final int BANK_PLANE0   = 0x00;
    private static final int BANK_TABLES   = 0x08;
    private static final int BANK_SPRITES0 = 0x09;

    // =====================================================================
    //  MMIO addresses
    // =====================================================================

    private static final int REG_CTRL     = 0x0000;
    private static final int REG_STATUS   = 0x0001;
    private static final int REG_TX_CTRL  = 0x0006;
    private static final int REG_TX_CMD   = 0x0030;
    private static final int REG_SPR_CTRL = 0x0014;

    private static final int COP_CTRL     = 0x0020;
    private static final int COP_LEN_L    = 0x0021;
    private static final int COP_LEN_H    = 0x0022;
    private static final int COP_OFS_L    = 0x0023;
    private static final int COP_OFS_H    = 0x0024;

    private static final int GROUP_BASE   = 0x0400;
    private static final int PAL_BASE     = 0x0100;
    private static final int TEXT_BASE    = 0x2000;
    private static final int OAM_BASE     = 0x1000;

    // =====================================================================
    //  Blitter registers
    // =====================================================================

    private static final int BLT_CTRL         = 0x0560;
    private static final int BLT_SRC_L        = 0x0561;
    private static final int BLT_DST_L        = 0x0563;
    private static final int BLT_W_L          = 0x0565;
    private static final int BLT_H            = 0x0567;
    private static final int BLT_SRC_PITCH_L  = 0x0568;
    private static final int BLT_DST_PITCH_L  = 0x056A;
    private static final int BLT_FILL         = 0x056C;
    private static final int BLT_PLANE_MASK   = 0x056D;
    private static final int BLT_ROP          = 0x056E;
    private static final int BLT_SHIFT        = 0x056F;
    private static final int BLT_FIRST_MASK   = 0x0570;
    private static final int BLT_LAST_MASK    = 0x0571;

    private static final int BLT_X_L          = 0x0572;
    private static final int BLT_Y_L          = 0x0574;
    private static final int BLT_WPX_L        = 0x0576;
    private static final int BLT_HPX          = 0x0578;

    private static final int BLT_SRCBANK      = 0x0579;
    private static final int BLT_SRCOFS_L     = 0x057A;
    private static final int BLT_SX_L         = 0x057C;
    private static final int BLT_SY_L         = 0x057E;
    private static final int BLT_PSRC_PITCH_L = 0x0580;

    private static final int BLT_DXSTEP_L     = 0x0582;
    private static final int BLT_DYSTEP_L     = 0x0584;
    private static final int BLT_SXSTEP_L     = 0x0586;
    private static final int BLT_SYSTEP_L     = 0x0588;

    // BLT_CTRL bits
    private static final int BC_START = 0x01;
    private static final int BC_DIR   = 0x08;
    private static final int BC_TRANS = 0x10;

    // BLT_ROP opcodes
    private static final int ROP_COPY     = 0;
    private static final int ROP_XOR      = 3;
    private static final int ROP_RECTFILL = 4;
    private static final int ROP_RECTBLIT = 5;

    // STATUS
    private static final int STATUS_BLT = 0x10;

    // Copper
    private static final int COP_W16 = 0x01, COP_END = 0x80;
    private static final int TBL_COP_BASE = 0x0000;

    // Group flags
    private static final int GF_LORES   = 0x01;
    private static final int GF_WRAP_X  = 0x02;
    private static final int GF_WRAP_Y  = 0x04;

    // Sprite OAM
    private static final int SA_EN = 0x01;

    // =====================================================================
    //  Hidden VRAM regions (per-plane)
    // =====================================================================

    // XOR pattern source lives in plane 2 hidden region.
    // Use stride 9 so shifting can borrow a carry byte safely.
    private static final int XOR_PAT_BASE   = 0x2400;
    private static final int XOR_PAT_STRIDE = 9;          // bytes per row in hidden source
    private static final int XOR_BAR_W      = 8;          // bytes copied to screen (=64px)
    private static final int XOR_PAT_H      = LH;

    // Save-under for XOR bar (plane2): 8 bytes * 200 rows = 1600 bytes.
    private static final int XOR_SAVE_BASE  = 0x2C00;

    // Save-under buffers for stamps (planes 0..3)
    private static final int ORB_SAVE_BASE  = 0x2000;     // safe (above visible 0x1F40)
    private static final int ORB_SAVE_WMAX  = 7;          // (48px) worst-case bytes if xBit!=0
    private static final int ORB_SAVE_SIZE  = ORB_SAVE_WMAX * 48; // 336

    private static final int GEM_SAVE_BASE  = ORB_SAVE_BASE + ORB_SAVE_SIZE + 0x40;
    private static final int GEM_SAVE_WMAX  = 5;          // (32px)
    private static final int GEM_SAVE_SIZE  = GEM_SAVE_WMAX * 32; // 160

    // =====================================================================
    //  Packed stamp art offsets in BANK_SPRITES0 (bank 0x9)
    // =====================================================================

    private static final int STAMP_ORB_OFS   = 0x0000;
    private static final int STAMP_ORB_W     = 48;
    private static final int STAMP_ORB_H     = 48;
    private static final int STAMP_ORB_PITCH = STAMP_ORB_W / 2; // 24

    private static final int STAMP_GEM_OFS   = 0x0480;
    private static final int STAMP_GEM_W     = 32;
    private static final int STAMP_GEM_H     = 32;
    private static final int STAMP_GEM_PITCH = STAMP_GEM_W / 2; // 16

    // Sprite patterns (for OAM sprites, not RECTBLIT)
    private static final int SPR_PAT0 = 0x0680;
    private static final int SPR_PAT1 = 0x0700;
    private static final int SPR_PAT2 = 0x0780;
    private static final int SPR_PAT3 = 0x0800;

    // =====================================================================
    //  Sine LUT
    // =====================================================================

    private static final int[] SIN = new int[256];
    static {
        for (int i = 0; i < 256; i++)
            SIN[i] = (int) Math.round(Math.sin(i * Math.PI * 2.0 / 256.0) * 127.0);
    }
    private static int sin256(int idx) { return SIN[idx & 0xFF]; }

    // =====================================================================
    //  Sky gradient keypoints
    // =====================================================================

    private static final int[] SKY_RGB  = { 0x020410, 0x081838, 0x183870, 0x4080B8, 0xD0A040, 0xA03018, 0x201010, 0x080404 };
    private static final int[] SKY_POS  = { 0, 30, 60, 100, 140, 165, 185, 200 };

    // =====================================================================
    //  Raster bars
    // =====================================================================

    private static final int[][] BAR_COLORS = {
            { 0xC02020, 0xFF4040, 0xFF8080, 0xFFD0D0, 0xFF8080, 0xFF4040, 0xC02020 },
            { 0x20C020, 0x40FF40, 0x80FF80, 0xD0FFD0, 0x80FF80, 0x40FF40, 0x20C020 },
            { 0x2040C0, 0x4080FF, 0x80B0FF, 0xD0E0FF, 0x80B0FF, 0x4080FF, 0x2040C0 },
            { 0xC020C0, 0xFF40FF, 0xFF80FF, 0xFFD0FF, 0xFF80FF, 0xFF40FF, 0xC020C0 },
            { 0xC0C020, 0xFFFF40, 0xFFFF80, 0xFFFFD0, 0xFFFF80, 0xFFFF40, 0xC0C020 },
    };
    private static final int BAR_H    = 7;
    private static final int NUM_BARS = 5;

    // =====================================================================
    //  Scroll message
    // =====================================================================

    private static final String SCROLL_MSG =
            "          " +
                    "\u00db\u00db\u00db VPU v4.1 BLITTER SHOWCASE \u00db\u00db\u00db" +
                    "     RECTFILL pixel-fills " +
                    "\u00b7 RECTBLIT packed-to-planar + TRANS " +
                    "\u00b7 COPY+SHIFT animated patterns " +
                    "\u00b7 COPY+XOR scanner with FIRST/LAST masks " +
                    "\u00b7 5 copper raster bars " +
                    "\u00b7 dual-playfield parallax " +
                    "\u00b7 16 hardware sprites with Lynx-style scaling " +
                    "\u00b7 8 bitplanes / 256 colours " +
                    "\u00b7 greetings to all retro enthusiasts! " +
                    "          ";

    // =====================================================================
    //  State
    // =====================================================================

    private VPU_v4_1 vpu;
    private volatile int frame;
    private int frameCycles;

    // Sprite OAM animation
    private static final int N_SPR = 16;
    private final int[] sprCX    = new int[N_SPR];
    private final int[] sprCY    = new int[N_SPR];
    private final int[] sprAmpX  = new int[N_SPR];
    private final int[] sprAmpY  = new int[N_SPR];
    private final int[] sprPhase = new int[N_SPR];
    private final int[] sprSpd   = new int[N_SPR];
    private final int[] sprScl   = new int[N_SPR];

    // RECTBLIT stamp bounce
    private int orbX = 60, orbY = 15, orbVx = 2, orbVy = 1;
    private int gemX = 200, gemY = 35, gemVx = -3, gemVy = 2;

    // save-under meta for stamps
    private final SavedRect orbSaved = new SavedRect();
    private final SavedRect gemSaved = new SavedRect();

    // XOR scanner sweep
    private int xorX = 0, xorDir = 1;

    // XOR bar save-under state
    private boolean xorSavedValid = false;
    private int xorSavedXBytes = 0;

    // Neon floor phase
    private int floorPhase = 0;

    // =====================================================================
    //  JavaFX lifecycle
    // =====================================================================

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas();
        vpu = new VPU_v4_1(CFG, bit -> {}, 0, canvas);

        initDemo();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, CFG.canvasWidth(), CFG.canvasHeight());
        scene.getRoot().setStyle("-fx-background-color: black;");
        stage.setTitle("VPU v4.1 — Demoscene Blitter Showcase (STABLE)");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        Thread emu = new Thread(() -> {
            final long FRAME_NS = 14_285_714L; // ~70 Hz
            long next = System.nanoTime();
            while (!Thread.interrupted()) {
                long now = System.nanoTime();
                if (now >= next) {
                    runOneFrame();
                    frame++;
                    next += FRAME_NS;
                    if (next < now) next = now;
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "vpu-showcase-stable");
        emu.setDaemon(true);
        emu.start();

        new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        }.start();

        stage.setOnCloseRequest(e -> { emu.interrupt(); Platform.exit(); });
    }

    public static void main(String[] args) { launch(args); }

    // =====================================================================
    //  FRAME RUNNER
    // =====================================================================

    private void runOneFrame() {
        frameCycles = 0;
        int f = frame;

        // 1) Animate (no ticks)
        animate(f);

        // 2) Copper rebuild (tables bank writes)
        buildCopper(f);

        // 3) Parallax scrolls
        setGroupScroll(0, f / 3, 0);
        setGroupScroll(1, f,     0);

        // 4) Sprites
        updateSprites(f);

        // 5) Text
        if (f % 3 == 0) updateTextScroller(f / 3);

        // ==============================================================
        //  BLITTER SEQUENCE (stable ordering)
        // ==============================================================

        // A) Restore previous XOR bar area (plane2) from save-under buffer
        restoreXorBarIfAny();

        // B) Restore old stamps (planes0-3) so they don't trail
        restoreSaved(ORB_SAVE_BASE, orbSaved, 0x0F);
        restoreSaved(GEM_SAVE_BASE, gemSaved, 0x0F);

        // C) Shift XOR pattern in hidden VRAM (plane2)
        shiftXorPatternLeft1();

        // D) Save-under NEW stamp positions (planes0-3)
        saveUnderTo(ORB_SAVE_BASE, ORB_SAVE_WMAX, orbSaved, orbX, orbY, STAMP_ORB_W, STAMP_ORB_H, 0x0F);
        saveUnderTo(GEM_SAVE_BASE, GEM_SAVE_WMAX, gemSaved, gemX, gemY, STAMP_GEM_W, STAMP_GEM_H, 0x0F);

        // E) Draw stamps (RECTBLIT, TRANS) into planes0-3
        bltRectBlit(0x0000, BPR, orbX, orbY, STAMP_ORB_W, STAMP_ORB_H,
                BANK_SPRITES0, STAMP_ORB_OFS, STAMP_ORB_PITCH, 0, 0, true, 0x0F);
        bltRectBlit(0x0000, BPR, gemX, gemY, STAMP_GEM_W, STAMP_GEM_H,
                BANK_SPRITES0, STAMP_GEM_OFS, STAMP_GEM_PITCH, 0, 0, true, 0x0F);

        // F) Neon floor bars (planes4-7)
        int floorY = 152;
        for (int bar = 0; bar < 8 && floorY + 6 <= LH; bar++) {
            int ci = ((floorPhase + bar * 2) % 14) + 17; // palette 17..30
            int colorBits = (ci & 0x0F) << 4;            // plane4..7 bits
            bltRectFill(0x0000, BPR, 0, floorY, LW, 6, colorBits, 0xF0);
            floorY += 6;
        }

        // G) SAVE-UNDER for XOR bar at NEW position (plane2), then XOR draw it
        saveXorBarUnder(xorX >>> 3);
        drawXorBar(xorX);

        // Finish frame ticks
        int remaining = CPF - frameCycles;
        if (remaining > 0) tickBudget(remaining);
    }

    // =====================================================================
    //  Animation updates
    // =====================================================================

    private void animate(int f) {
        xorX += xorDir * 4;
        if (xorX < 0)          { xorX = 0;          xorDir =  1; }
        if (xorX > LW - 64)    { xorX = LW - 64;    xorDir = -1; }

        orbX += orbVx; orbY += orbVy;
        if (orbX < 0 || orbX > LW - STAMP_ORB_W)  { orbVx = -orbVx; orbX = clamp(orbX, 0, LW - STAMP_ORB_W); }
        if (orbY < 5 || orbY > 90 - STAMP_ORB_H)  { orbVy = -orbVy; orbY = clamp(orbY, 5, 90 - STAMP_ORB_H); }

        gemX += gemVx; gemY += gemVy;
        if (gemX < 0 || gemX > LW - STAMP_GEM_W)  { gemVx = -gemVx; gemX = clamp(gemX, 0, LW - STAMP_GEM_W); }
        if (gemY < 5 || gemY > 130 - STAMP_GEM_H) { gemVy = -gemVy; gemY = clamp(gemY, 5, 130 - STAMP_GEM_H); }

        if ((f & 127) == 0) {
            gemVx += ((f >> 7) & 1) == 0 ? 1 : -1;
            gemVx = clamp(gemVx, -4, 4);
            if (gemVx == 0) gemVx = 2;
        }

        floorPhase = (f / 4) % 14;
    }

    // =====================================================================
    //  XOR scanner (save-under based, non-destructive)
    // =====================================================================

    private void restoreXorBarIfAny() {
        if (!xorSavedValid) return;

        int dstOfs = xorSavedXBytes; // y=0
        bltCopyBytes(
                XOR_SAVE_BASE, dstOfs,
                XOR_BAR_W, LH,
                /*srcPitchDelta*/0,
                /*dstPitchDelta*/(BPR - XOR_BAR_W),
                /*planeMask*/0x04,
                /*rop*/ROP_COPY,
                /*shift*/0,
                /*firstMask*/0xFF,
                /*lastMask*/0xFF,
                false, false
        );

        xorSavedValid = false;
    }

    private void saveXorBarUnder(int xBytes) {
        // Save the *destination bytes* that we are going to XOR-modify.
        // This makes the effect reversible even if other ops touch plane2.
        int srcOfs = xBytes;       // y=0, x = xBytes
        int dstOfs = XOR_SAVE_BASE;

        bltCopyBytes(
                srcOfs, dstOfs,
                XOR_BAR_W, LH,
                /*srcPitchDelta*/(BPR - XOR_BAR_W),
                /*dstPitchDelta*/0,
                /*planeMask*/0x04,
                /*rop*/ROP_COPY,
                /*shift*/0,
                /*firstMask*/0xFF,
                /*lastMask*/0xFF,
                false, false
        );

        xorSavedValid = true;
        xorSavedXBytes = xBytes;
    }

    private void drawXorBar(int x) {
        int barXBytes = (x >>> 3);
        int sh = x & 7;

        int dstOfs = barXBytes;
        int dstDelta = BPR - XOR_BAR_W;

        int srcOfs = XOR_PAT_BASE;
        int srcDelta = XOR_PAT_STRIDE - XOR_BAR_W; // 9-8=1

        int firstM = 0x3F;
        int lastM  = 0xFC;

        bltCopyBytes(srcOfs, dstOfs,
                XOR_BAR_W, LH,
                srcDelta, dstDelta,
                0x04, ROP_XOR, sh,
                firstM, lastM,
                false, false);
    }

    private void shiftXorPatternLeft1() {
        // shift first 8 bytes per row; keep last byte as carry=0
        bltCopyBytes(XOR_PAT_BASE, XOR_PAT_BASE,
                /*wBytes*/XOR_PAT_STRIDE - 1, /*hRows*/XOR_PAT_H,
                /*srcPitchDelta*/1, /*dstPitchDelta*/1,
                /*planeMask*/0x04, ROP_COPY, /*shift*/1,
                0xFF, 0xFF,
                false, false);

        int bankPlane2 = BANK_PLANE0 + 2;
        for (int y = 0; y < LH; y++) {
            int ofs = XOR_PAT_BASE + y * XOR_PAT_STRIDE + (XOR_PAT_STRIDE - 1);
            vpu.writeVramPlane(bankPlane2, ofs, (byte) 0);
        }
    }

    // =====================================================================
    //  Sprite OAM updates
    // =====================================================================

    private void updateSprites(int f) {
        for (int i = 0; i < N_SPR; i++) {
            int phase = (f * sprSpd[i] + sprPhase[i]) & 0xFF;
            int sx = sprCX[i] + sin256(phase) * sprAmpX[i] / 127;
            int sy = sprCY[i] + sin256(phase + 64) * sprAmpY[i] / 127;

            sx = ((sx % 640) + 640) % 640;
            sy = ((sy % 170) + 170) % 170 + 15;

            int scale = sprScl[i];
            if ((i & 1) == 0) {
                int pulse = 256 + sin256(f * 3 + i * 30) * 96 / 127;
                scale = clamp(sprScl[i] * pulse / 256, 0x40, 0x0200);
            }

            oamWord(i, 0, sx);
            oamWord(i, 2, sy);
            oamWord(i, 12, scale);
            oamWord(i, 14, scale);
        }
    }

    // =====================================================================
    //  Initialization
    // =====================================================================

    private void initDemo() {
        vpu.reset(true);

        mmio(REG_TX_CTRL,  0x00);
        mmio(REG_SPR_CTRL, 0x00);

        setupPalette();
        configureGroups();
        drawMountains();
        drawCityscape();
        buildStampArt();
        buildSpritePatterns();
        configureSpriteOAM();
        seedXorPattern();
        setupText();

        mmio(COP_OFS_L, 0);
        mmio(COP_OFS_H, 0);
        buildCopper(0);

        mmio(REG_CTRL,     0x01);
        mmio(COP_CTRL,     0x01);
        mmio(REG_SPR_CTRL, 0x01);

        xorSavedValid = false;
        xorSavedXBytes = 0;
    }

    // =====================================================================
    //  Palette
    // =====================================================================

    private void setupPalette() {
        setPal(0,  0x000000);
        setPal(1,  0x6080A0);
        setPal(2,  0x405870);
        setPal(3,  0x408850);
        setPal(4,  0x206830);
        setPal(5,  0x508838);
        setPal(6,  0x184020);
        setPal(7,  0x483020);

        setPal(8,  0x00E8FF);
        setPal(9,  0xFF00C0);
        setPal(10, 0xFFE000);
        setPal(11, 0xFFFFFF);
        setPal(12, 0xFF6000);
        setPal(13, 0x60FF60);
        setPal(14, 0x6060FF);
        setPal(15, 0xC0C0C0);

        setPal(16, 0x000000);
        setPal(17, 0xFF0040);
        setPal(18, 0xFF4000);
        setPal(19, 0xFFB000);
        setPal(20, 0xFFFF00);
        setPal(21, 0x80FF00);
        setPal(22, 0x00FF60);
        setPal(23, 0x00FFD0);
        setPal(24, 0x00B0FF);
        setPal(25, 0x4060FF);
        setPal(26, 0x8020FF);
        setPal(27, 0xD000FF);
        setPal(28, 0xFF00A0);
        setPal(29, 0xFFFFFF);
        setPal(30, 0x181828);
        setPal(31, 0x282840);

        setPal(32, 0x000000);
        setPal(33, 0xFF3030);  setPal(34, 0xFF6060);  setPal(35, 0xFFA0A0);  setPal(36, 0xFFD0D0);
        setPal(37, 0x30FF30);  setPal(38, 0x60FF60);  setPal(39, 0xA0FFA0);
        setPal(40, 0x3060FF);  setPal(41, 0x6090FF);  setPal(42, 0xA0C0FF);
        setPal(43, 0xFFFF30);  setPal(44, 0xFF30FF);  setPal(45, 0x30FFFF);
        setPal(46, 0xFFFFFF);  setPal(47, 0x808080);

        for (int i = 48; i < 256; i++) {
            int t = i - 48;
            int r = (t / 36) * 51, g = ((t / 6) % 6) * 51, b = (t % 6) * 51;
            setPal(i, (r << 16) | (g << 8) | b);
        }
    }

    // =====================================================================
    //  Groups
    // =====================================================================

    private void configureGroups() {
        setGroup(0, 0, 4,  0, 0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        setGroup(1, 4, 4, 16, 0, 0, 1, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        setGroup(2, 0, 0,  0, 0, 0, 2, 0, 0);
        setGroup(3, 0, 0,  0, 0, 0, 3, 0, 0);
    }

    // =====================================================================
    //  Bitplane art — mountains (planes 0–3)
    // =====================================================================

    private void drawMountains() {
        for (int x = 0; x < LW; x++) {
            int h = 55 + msin(x, 1, 0, 28) + msin(x, 3, 80, 14) + msin(x, 7, 200, 6);
            fillColumn(0, 3, x, h, LH, 1, 2);
        }
        for (int x = 0; x < LW; x++) {
            int h = 85 + msin(x, 2, 40, 30) + msin(x, 5, 150, 15) + msin(x, 11, 60, 5);
            fillColumn(0, 3, x, h, LH, 3, 4);
        }
        for (int x = 0; x < LW; x++) {
            int h = 115 + msin(x, 3, 100, 24) + msin(x, 8, 220, 12) + msin(x, 13, 30, 4);
            fillColumn(0, 3, x, h, LH, 5, 6);
        }
        for (int x = 0; x < LW; x++)
            fillColumn(0, 3, x, 150, LH, 7, 7);
    }

    // =====================================================================
    //  Bitplane art — cityscape (planes 4–7)
    // =====================================================================

    private void drawCityscape() {
        long seed = 42;
        int bx = 0;
        while (bx < LW) {
            seed = lcg(seed);
            int bw = 6 + (int)(seed & 0x07);
            seed = lcg(seed);
            int bh = 25 + (int)(seed & 0x3F);
            seed = lcg(seed);
            int shade = ((seed & 1) == 0) ? 30 : 31;

            int by = LH - bh;
            for (int y = by; y < LH; y++) {
                for (int cx = bx; cx < bx + bw && cx < LW; cx++) {
                    int color;
                    if (cx == bx || cx == bx + bw - 1) color = 30;
                    else if (y == by) color = 31;
                    else if (isWindow(cx - bx, y - by, bw, bh, seed)) {
                        seed = lcg(seed);
                        color = ((seed & 3) == 0) ? 20 : ((seed & 1) == 0 ? 19 : 29);
                    } else color = shade;
                    setPixelGroup(4, 7, cx, y, color - 16);
                }
            }
            seed = lcg(seed);
            bx += bw + 1 + (int)(seed & 0x03);
        }
    }

    // =====================================================================
    //  Packed stamp art (BANK_SPRITES0)
    // =====================================================================

    private void buildStampArt() {
        float cx = 23.5f, cy = 23.5f;
        for (int y = 0; y < STAMP_ORB_H; y++) {
            for (int x = 0; x < STAMP_ORB_W; x++) {
                float dx = x - cx, dy = y - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 22.0f) { sprPx4(STAMP_ORB_OFS, STAMP_ORB_PITCH, x, y, 0); continue; }

                float ldx = dx + 8, ldy = dy + 8;
                float ldist = (float) Math.sqrt(ldx * ldx + ldy * ldy);

                int c;
                if (ldist < 5)       c = 11;
                else if (ldist < 10) c = 8;
                else if (dist < 10)  c = 9;
                else if (dist < 16)  c = 14;
                else if (dist < 20)  c = 12;
                else                 c = 15;

                if (dist > 20.0f && dist <= 22.0f) c = 10;

                sprPx4(STAMP_ORB_OFS, STAMP_ORB_PITCH, x, y, c);
            }
        }

        for (int y = 0; y < STAMP_GEM_H; y++) {
            for (int x = 0; x < STAMP_GEM_W; x++) {
                int mdist = Math.abs(x - 16) + Math.abs(y - 16);
                if (mdist >= 15) { sprPx4(STAMP_GEM_OFS, STAMP_GEM_PITCH, x, y, 0); continue; }

                int c;
                if (mdist < 3)       c = 11;
                else if (mdist < 6)  c = 8;
                else if (mdist < 9)  c = 13;
                else if (mdist < 12) c = 14;
                else                 c = 9;

                if (x == 16 || y == 16 || x + y == 31 || x - y == 0) {
                    if (mdist > 2) c = 15;
                }

                sprPx4(STAMP_GEM_OFS, STAMP_GEM_PITCH, x, y, c);
            }
        }
    }

    // =====================================================================
    //  Sprite patterns (OAM)
    // =====================================================================

    private void buildSpritePatterns() {
        drawBall(SPR_PAT0, new int[]{0, 1, 2, 3, 4, 3, 2});
        drawBall(SPR_PAT1, new int[]{0, 8, 9, 10, 11, 10, 9});
        drawDiamond(SPR_PAT2);
        drawStar(SPR_PAT3);
    }

    private void drawBall(int offset, int[] shades) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                if (dx * dx + dy * dy < 49.0f) {
                    float ld = (float) Math.sqrt((dx + 3) * (dx + 3) + (dy + 3) * (dy + 3));
                    int si = clamp((int)(ld / 2.2f), 0, shades.length - 1);
                    int c = shades[si]; if (c == 0) c = 1;
                    sprPxSmall(offset, x, y, c);
                } else {
                    sprPxSmall(offset, x, y, 0);
                }
            }
        }
    }

    private void drawDiamond(int offset) {
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int d = Math.abs(x - 8) + Math.abs(y - 8);
                sprPxSmall(offset, x, y, d < 8 ? 1 + Math.min(3, d / 2) : 0);
            }
    }

    private void drawStar(int offset) {
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float arm = (float)(3.5 + 3.5 * Math.cos(Math.atan2(dy, dx) * 5));
                int c = (dist < arm && dist < 7.5f) ? (dist < arm * 0.4f ? 4 : dist < arm * 0.7f ? 3 : 2) : 0;
                sprPxSmall(offset, x, y, c);
            }
    }

    private void configureSpriteOAM() {
        Random rng = new Random(7);
        int bank = BANK_SPRITES0 & 0x0F;
        int bankLo = bank & 0x03;
        int bankHi = (bank >>> 2) & 0x03;

        int[] patOfs = { SPR_PAT0, SPR_PAT1, SPR_PAT2, SPR_PAT3 };
        int[] scales  = { 0x0100, 0x00C0, 0x0080, 0x00A0 };

        for (int i = 0; i < N_SPR; i++) {
            int dataOfs = patOfs[i & 3];
            sprCX[i]    = 80 + rng.nextInt(480);
            sprCY[i]    = 20 + rng.nextInt(130);
            sprAmpX[i]  = 40 + rng.nextInt(120);
            sprAmpY[i]  = 15 + rng.nextInt(60);
            sprPhase[i] = rng.nextInt(256);
            sprSpd[i]   = 1 + rng.nextInt(3);
            sprScl[i]   = scales[i & 3];

            int attr = SA_EN | (bankHi << 6);
            int dataWord = (bankLo << 14) | (dataOfs & 0x3FFF);
            writeOAM(i, sprCX[i], sprCY[i], 16, 16, dataWord,
                    attr, 32, 0, (i < 8) ? 0 : 1, sprScl[i], sprScl[i]);
        }
    }

    // =====================================================================
    //  XOR pattern seed
    // =====================================================================

    private void seedXorPattern() {
        int bankPlane2 = BANK_PLANE0 + 2;
        for (int y = 0; y < XOR_PAT_H; y++) {
            int rowOfs = XOR_PAT_BASE + y * XOR_PAT_STRIDE;
            for (int bx = 0; bx < XOR_PAT_STRIDE; bx++) {
                int v;
                if (bx == XOR_PAT_STRIDE - 1) v = 0; // carry byte
                else {
                    int phase = (y + bx * 5) & 0x0F;
                    v = (phase < 6) ? 0xCC : (phase < 10) ? 0x33 : 0xAA;
                }
                vpu.writeVramPlane(bankPlane2, rowOfs + bx, (byte) v);
            }
        }
    }

    // =====================================================================
    //  Text
    // =====================================================================

    private void setupText() {
        mmio(REG_TX_CTRL, 0x01 | 0x04);
        mmio(REG_TX_CMD,  0x04);

        String title = "\u00db\u00db VPU v4.1 BLITTER SHOWCASE \u00db\u00db";
        putString(0, (80 - title.length()) / 2, title, 0x0F);

        putString(1, (80 - 42) / 2, "RECTFILL \u00b7 RECTBLIT \u00b7 SHIFT \u00b7 XOR \u00b7 COPPER", 0x0B);

        putString(3, 2, "COPPER SKY  \u00b7  PARALLAX  \u00b7  SPRITES  \u00b7  8 BITPLANES  \u00b7  256 COLORS", 0x0E);

        updateTextScroller(0);
    }

    private void updateTextScroller(int pos) {
        int len = SCROLL_MSG.length();
        for (int col = 0; col < 80; col++) {
            int ci = (pos + col) % len;
            char ch = SCROLL_MSG.charAt(ci);
            int hue = (pos + col) & 0x0F;
            int attr = (hue < 2) ? 0x0F : (0x09 + (hue % 7));
            putChar(24, col, ch, attr);
        }
    }

    // =====================================================================
    //  Copper
    // =====================================================================

    private void buildCopper(int f) {
        int[] barY = new int[NUM_BARS];
        for (int b = 0; b < NUM_BARS; b++)
            barY[b] = 40 + sin256(f * 2 + b * 51) * 50 / 127;

        int idx = 0;

        for (int ly = 0; ly < LH; ly++) {
            int displayScan = ly * 2;
            int rgb = skyGradient(ly, f);

            for (int b = 0; b < NUM_BARS; b++) {
                int dist = ly - barY[b];
                if (dist >= 0 && dist < BAR_H) { rgb = BAR_COLORS[b][dist]; break; }
            }

            int rgb565 = toRgb565(rgb);
            copEntry(idx++, displayScan, PAL_BASE, rgb565 & 0xFF, (rgb565 >> 8) & 0xFF, COP_W16);
        }

        int warmPulse = 180 + sin256(f * 4) * 75 / 127;
        int warmRgb565 = toRgb565((warmPulse << 16) | ((warmPulse * 3 / 4) << 8) | 0x10);
        copEntry(idx++, 280, PAL_BASE + 20 * 2, warmRgb565 & 0xFF, (warmRgb565 >> 8) & 0xFF, COP_W16);

        copEntry(idx++, 0, 0, 0, 0, COP_END);

        int totalBytes = idx * 8;
        mmio(COP_LEN_L, totalBytes & 0xFF);
        mmio(COP_LEN_H, (totalBytes >> 8) & 0xFF);
    }

    private int skyGradient(int ly, int f) {
        int breathe = sin256(f) * 10 / 127;
        int adjY = clamp(ly + breathe, 0, LH - 1);
        for (int k = 0; k < SKY_POS.length - 1; k++) {
            if (adjY >= SKY_POS[k] && adjY < SKY_POS[k + 1]) {
                float t = (float)(adjY - SKY_POS[k]) / (SKY_POS[k + 1] - SKY_POS[k]);
                return lerpRgb(SKY_RGB[k], SKY_RGB[k + 1], t);
            }
        }
        return SKY_RGB[SKY_RGB.length - 1];
    }

    // =====================================================================
    //  Save-under for stamps
    // =====================================================================

    private static final class SavedRect {
        boolean valid;
        int dstXByte;
        int dstY;
        int wBytes;
        int hRows;
        int firstMask;
        int lastMask;
    }

    private void restoreSaved(int saveBase, SavedRect s, int planeMask) {
        if (!s.valid) return;

        int dstOfs = s.dstY * BPR + s.dstXByte;
        bltCopyBytes(saveBase, dstOfs,
                s.wBytes, s.hRows,
                0, (BPR - s.wBytes),
                planeMask, ROP_COPY, 0,
                s.firstMask, s.lastMask,
                false, false);

        s.valid = false;
    }

    private void saveUnderTo(int saveBase, int wBytesMax, SavedRect out,
                             int x, int y, int wPx, int hPx, int planeMask) {

        int x0 = clamp(x, 0, LW);
        int y0 = clamp(y, 0, LH);
        int x1 = clamp(x + wPx, 0, LW);
        int y1 = clamp(y + hPx, 0, LH);
        if (x1 <= x0 || y1 <= y0) { out.valid = false; return; }

        int w = x1 - x0;
        int h = y1 - y0;

        int xBit  = x0 & 7;
        int xByte = x0 >>> 3;
        int wBytes = (xBit + w + 7) >>> 3;
        if (wBytes > wBytesMax) wBytes = wBytesMax;

        int firstMask = (0xFF >>> xBit) & 0xFF;
        int end = xBit + w;
        int lastBits = end & 7;
        int lastMask = (lastBits == 0) ? 0xFF : ((0xFF << (8 - lastBits)) & 0xFF);

        if (wBytes == 1) {
            int one = firstMask;
            if (end < 8) one &= ((0xFF << (8 - end)) & 0xFF);
            firstMask = one;
            lastMask  = one;
        }

        int srcOfs = y0 * BPR + xByte;

        bltCopyBytes(srcOfs, saveBase,
                wBytes, h,
                (BPR - wBytes), 0,
                planeMask, ROP_COPY, 0,
                firstMask, lastMask,
                false, false);

        out.valid = true;
        out.dstXByte = xByte;
        out.dstY = y0;
        out.wBytes = wBytes;
        out.hRows = h;
        out.firstMask = firstMask;
        out.lastMask = lastMask;
    }

    // =====================================================================
    //  Blitter wrappers
    // =====================================================================

    private void bltRectFill(int dstBase, int pitchBytes,
                             int x, int y, int w, int h,
                             int colorBits, int planeMask) {
        if (w <= 0 || h <= 0) return;

        mmio(REG_STATUS, STATUS_BLT); // clear stale completion

        mmio16(BLT_DST_L, dstBase & 0x3FFF);
        mmio16(BLT_DST_PITCH_L, pitchBytes & 0xFFFF);
        mmio16(BLT_X_L,   x & 0xFFFF);
        mmio16(BLT_Y_L,   y & 0xFFFF);
        mmio16(BLT_WPX_L, w & 0xFFFF);
        mmio(BLT_HPX,     h & 0xFF);
        mmio(BLT_FILL,       colorBits & 0xFF);
        mmio(BLT_PLANE_MASK, planeMask & 0xFF);
        mmio(BLT_ROP,        ROP_RECTFILL);
        mmio(BLT_FIRST_MASK, 0xFF);
        mmio(BLT_LAST_MASK,  0xFF);
        mmio(BLT_CTRL, BC_START);
        bltWaitDone();
    }

    private void bltRectBlit(int dstBase, int pitchBytes,
                             int dstX, int dstY, int dstW, int dstH,
                             int srcBank, int srcOfs, int srcPitch,
                             int srcX, int srcY,
                             boolean transparent, int planeMask) {
        if (dstW <= 0 || dstH <= 0) return;

        mmio(REG_STATUS, STATUS_BLT);

        mmio16(BLT_DST_L, dstBase & 0x3FFF);
        mmio16(BLT_DST_PITCH_L, pitchBytes & 0xFFFF);
        mmio16(BLT_X_L,   dstX & 0xFFFF);
        mmio16(BLT_Y_L,   dstY & 0xFFFF);
        mmio16(BLT_WPX_L, dstW & 0xFFFF);
        mmio(BLT_HPX,     dstH & 0xFF);

        mmio(BLT_SRCBANK,      srcBank & 0xFF);
        mmio16(BLT_SRCOFS_L,   srcOfs & 0x3FFF);
        mmio16(BLT_SX_L,       srcX & 0xFFFF);
        mmio16(BLT_SY_L,       srcY & 0xFFFF);
        mmio16(BLT_PSRC_PITCH_L, srcPitch & 0xFFFF);

        mmio16(BLT_DXSTEP_L, 0x0100);
        mmio16(BLT_DYSTEP_L, 0x0100);
        mmio16(BLT_SXSTEP_L, 0x0100);
        mmio16(BLT_SYSTEP_L, 0x0100);

        mmio(BLT_PLANE_MASK, planeMask & 0xFF);
        mmio(BLT_FIRST_MASK, 0xFF);
        mmio(BLT_LAST_MASK,  0xFF);
        mmio(BLT_ROP, ROP_RECTBLIT);

        mmio(BLT_CTRL, BC_START | (transparent ? BC_TRANS : 0));
        bltWaitDone();
    }

    private void bltCopyBytes(int srcOfs, int dstOfs,
                              int wBytes, int hRows,
                              int srcPitchDelta, int dstPitchDelta,
                              int planeMask, int rop, int shift,
                              int firstMask, int lastMask,
                              boolean trans, boolean forceBack) {
        if (wBytes <= 0 || hRows <= 0) return;

        mmio(REG_STATUS, STATUS_BLT);

        mmio16(BLT_SRC_L,       srcOfs & 0x3FFF);
        mmio16(BLT_DST_L,       dstOfs & 0x3FFF);
        mmio16(BLT_W_L,         wBytes & 0xFFFF);
        mmio(BLT_H,             hRows & 0xFF);
        mmio16(BLT_SRC_PITCH_L, srcPitchDelta & 0xFFFF);
        mmio16(BLT_DST_PITCH_L, dstPitchDelta & 0xFFFF);
        mmio(BLT_PLANE_MASK,    planeMask & 0xFF);
        mmio(BLT_ROP,           rop & 0x07);
        mmio(BLT_SHIFT,         shift & 0x07);
        mmio(BLT_FIRST_MASK,    firstMask & 0xFF);
        mmio(BLT_LAST_MASK,     lastMask & 0xFF);
        mmio(BLT_FILL,          0x00);
        mmio(BLT_CTRL, BC_START | (trans ? BC_TRANS : 0) | (forceBack ? BC_DIR : 0));
        bltWaitDone();
    }

    private void bltWaitDone() {
        for (int guard = 0; guard < 250_000; guard++) {
            int st = vpu.readMmio(REG_STATUS) & 0xFF;
            if ((st & STATUS_BLT) != 0) {
                mmio(REG_STATUS, STATUS_BLT);
                return;
            }
            tickBudget(128);
        }
        throw new IllegalStateException("Blitter timeout (STATUS_BLT never set)");
    }

    // =====================================================================
    //  Low-level VPU helpers
    // =====================================================================

    private void mmio(int reg, int val) {
        vpu.writeMmio(reg, (byte)(val & 0xFF));
    }

    private void mmio16(int regLo, int val) {
        mmio(regLo,     val & 0xFF);
        mmio(regLo + 1, (val >>> 8) & 0xFF);
    }

    private void tickBudget(int cycles) {
        if (cycles <= 0) return;
        vpu.tick(cycles);
        frameCycles += cycles;
    }

    private void setPal(int idx, int rgb24) {
        int v = toRgb565(rgb24);
        int ofs = PAL_BASE + idx * 2;
        mmio(ofs,     v & 0xFF);
        mmio(ofs + 1, (v >>> 8) & 0xFF);
    }

    private void setGroup(int gi, int planeStart, int planeCount, int palBase,
                          int scrollX, int scrollY, int priority, int flags, int bplOfs) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 0, planeStart);
        mmio(base + 1, planeCount);
        mmio(base + 2, palBase);
        mmio(base + 3, scrollX & 0xFF);
        mmio(base + 4, (scrollX >>> 8) & 0xFF);
        mmio(base + 5, scrollY & 0xFF);
        mmio(base + 6, priority);
        mmio(base + 7, flags);
        mmio(base + 8, bplOfs & 0xFF);
        mmio(base + 9, (bplOfs >>> 8) & 0xFF);
    }

    private void setGroupScroll(int gi, int scrollX, int scrollY) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 3, scrollX & 0xFF);
        mmio(base + 4, (scrollX >>> 8) & 0xFF);
        mmio(base + 5, scrollY & 0xFF);
    }

    private void setPixelGroup(int planeStart, int planeEnd, int x, int y, int color) {
        if (x < 0 || x >= LW || y < 0 || y >= LH) return;
        int byteOfs = y * BPR + (x >> 3);
        int bit  = 1 << (7 - (x & 7));
        int nbit = ~bit & 0xFF;
        for (int p = planeStart; p <= planeEnd; p++) {
            byte cur = vpu.readVramPlane(BANK_PLANE0 + p, byteOfs);
            int cv = cur & 0xFF;
            cv = (((color >> (p - planeStart)) & 1) != 0) ? (cv | bit) : (cv & nbit);
            vpu.writeVramPlane(BANK_PLANE0 + p, byteOfs, (byte) cv);
        }
    }

    private void sprPx4(int dataOfs, int pitch, int x, int y, int color) {
        int ofs = dataOfs + y * pitch + (x >>> 1);
        byte cur = vpu.readVramPlane(BANK_SPRITES0, ofs);
        int cv = cur & 0xFF;
        if ((x & 1) == 0) cv = (cv & 0x0F) | ((color & 0x0F) << 4);
        else               cv = (cv & 0xF0) | (color & 0x0F);
        vpu.writeVramPlane(BANK_SPRITES0, ofs, (byte) cv);
    }

    private void sprPxSmall(int dataOfs, int x, int y, int color) {
        sprPx4(dataOfs, 8, x, y, color);
    }

    private void oamWord(int sprIdx, int fieldOfs, int val) {
        int ofs = OAM_BASE + sprIdx * 32 + fieldOfs;
        mmio(ofs,     val & 0xFF);
        mmio(ofs + 1, (val >> 8) & 0xFF);
    }

    private void oamByte(int sprIdx, int fieldOfs, int val) {
        mmio(OAM_BASE + sprIdx * 32 + fieldOfs, val & 0xFF);
    }

    private void writeOAM(int idx, int x, int y, int w, int h, int dataWord,
                          int attr, int palBase, int colId, int priority,
                          int xstep, int ystep) {
        oamWord(idx, 0,  x);
        oamWord(idx, 2,  y);
        oamByte(idx, 4,  w);
        oamByte(idx, 5,  h);
        oamWord(idx, 6,  dataWord);
        oamByte(idx, 8,  attr);
        oamByte(idx, 9,  palBase);
        oamByte(idx, 10, colId);
        oamByte(idx, 11, priority);
        oamWord(idx, 12, xstep);
        oamWord(idx, 14, ystep);
    }

    private void copEntry(int idx, int scanline, int reg, int valLo, int valHi, int flags) {
        int base = TBL_COP_BASE + idx * 8;
        vpu.writeVramPlane(BANK_TABLES, base,     (byte)(scanline & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 1, (byte)((scanline >> 8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 2, (byte)(reg & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 3, (byte)((reg >> 8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 4, (byte)(valLo & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 5, (byte)(valHi & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 6, (byte)(flags & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 7, (byte) 0);
    }

    private void putChar(int row, int col, int ch, int attr) {
        int ofs = TEXT_BASE + (row * 80 + col) * 2;
        mmio(ofs,     ch & 0xFF);
        mmio(ofs + 1, attr & 0xFF);
    }

    private void putString(int row, int col, String s, int attr) {
        for (int i = 0; i < s.length() && col + i < 80; i++)
            putChar(row, col + i, s.charAt(i), attr);
    }

    // =====================================================================
    //  Misc helpers
    // =====================================================================

    private static int msin(int x, int freq, int phaseDeg, int amp) {
        return (int) Math.round(amp * Math.sin(x * Math.PI * 2.0 * freq / LW + Math.toRadians(phaseDeg)));
    }

    private void fillColumn(int ps, int pe, int x, int yTop, int yBot, int peakCol, int fillCol) {
        yTop = clamp(yTop, 0, LH);
        yBot = Math.min(yBot, LH);
        if (yTop >= yBot) return;
        setPixelGroup(ps, pe, x, yTop, peakCol);
        for (int y = yTop + 1; y < yBot; y++) setPixelGroup(ps, pe, x, y, fillCol);
    }

    private static boolean isWindow(int lx, int ly, int bw, int bh, long seed) {
        if (ly < 4 || lx < 2 || lx >= bw - 2) return false;
        int wx = (lx - 2) % 4, wy = (ly - 4) % 6;
        if (wx >= 2 || wy >= 3) return false;
        return ((seed ^ (lx * 7919L + ly * 104729L)) & 0xFFFFL) > 0x4000;
    }

    private static long lcg(long s) { return s * 6364136223846793005L + 1442695040888963407L; }

    private static int toRgb565(int rgb24) {
        int r = (rgb24 >> 16) & 0xFF, g = (rgb24 >> 8) & 0xFF, b = rgb24 & 0xFF;
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >> 3);
    }

    private static int lerpRgb(int c0, int c1, float t) {
        t = clamp(t, 0f, 1f);
        int r = (int)((c0 >> 16 & 0xFF) + ((c1 >> 16 & 0xFF) - (c0 >> 16 & 0xFF)) * t);
        int g = (int)((c0 >>  8 & 0xFF) + ((c1 >>  8 & 0xFF) - (c0 >>  8 & 0xFF)) * t);
        int b = (int)((c0       & 0xFF) + ((c1       & 0xFF) - (c0       & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}