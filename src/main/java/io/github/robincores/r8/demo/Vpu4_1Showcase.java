package io.github.robincores.r8.demo;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU;
import io.github.robincores.r8.device.VPU_v4_1;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * <h1>VPU v4.1 — Demoscene Showcase</h1>
 *
 * Banked v4.1 layout (per VPU_v4_1 implementation):
 * <ul>
 *   <li>VBANK 0x0..0x7: bitplanes 0..7</li>
 *   <li>VBANK 0x8: tables bank (copper program)</li>
 *   <li>VBANK 0x9..0xF: sprite pattern banks (4bpp packed)</li>
 *   <li>OAM: MMIO 0x1000..0x1FFF</li>
 * </ul>
 */
public class Vpu4_1Showcase extends Application {

    // =====================================================================
    //  Platform constants
    // =====================================================================

    private static final DisplayConfig CFG = new DisplayConfig(640, 400, 640, 400, 400, 449, 400);
    private static final int CPF = CFG.cyclesPerFrame();

    // Logical resolution (LORES groups)
    private static final int LW = 320, LH = 200, BPR = LW / 8; // 40 bytes/row

    // --- VideoWindow banks (v4.1 implementation) ---
    private static final int BANK_PLANE0   = 0x00; // 0..7 = planes 0..7
    private static final int BANK_TABLES   = 0x08; // 8 = tables (copper)
    private static final int BANK_SPRITES0 = 0x09; // 9..F = sprite pattern banks (we use 9)

    // OAM is MMIO now
    private static final int OAM_MMIO_BASE = 0x1000;

    // Copper program lives in tables bank (VBANK=8), start at 0x0000.
    private static final int TBL_COPPER_BASE = 0x0000;

    // MMIO addresses
    private static final int CTRL = 0x0000;
    private static final int TX_CTRL = 0x0006;
    private static final int TX_CMD = 0x0030;
    private static final int COP_CTRL = 0x0020;
    private static final int COP_LEN_L = 0x0021, COP_LEN_H = 0x0022;
    private static final int COP_OFS_L = 0x0023, COP_OFS_H = 0x0024;
    private static final int SPR_CTRL = 0x0014;
    private static final int GROUP_BASE = 0x0400;
    private static final int TEXT_BASE = 0x2000;
    private static final int PAL_BASE = 0x0100;

    // Copper flags
    private static final int COP_W16 = 0x01, COP_END = 0x80;

    // Group flags
    private static final int GF_LORES = 0x01, GF_WRAP_X = 0x02, GF_WRAP_Y = 0x04;

    // Sprite OAM
    private static final int SA_EN = 0x01;

    // =====================================================================
    //  Sine table
    // =====================================================================

    private static final int[] SIN = new int[256];
    static {
        for (int i = 0; i < 256; i++)
            SIN[i] = (int) Math.round(Math.sin(i * Math.PI * 2.0 / 256.0) * 127.0);
    }
    private static int sin256(int idx) { return SIN[idx & 0xFF]; }

    // =====================================================================
    //  Sky gradient keypoints (RGB24)
    // =====================================================================

    private static final int[] SKY_KEYS = {
            0x040820,
            0x081848,
            0x184888,
            0x50A0D0,
            0xE0C050,
            0xC04020,
            0x301018,
            0x100808,
    };
    private static final int[] SKY_POS = { 0, 40, 80, 120, 150, 170, 190, 200 };

    // =====================================================================
    //  Raster bar colors
    // =====================================================================

    private static final int[][] BAR_COLORS = {
            { 0xFF4040, 0xFF8080, 0xFFD0D0, 0xFFFFFF, 0xFFD0D0, 0xFF8080, 0xFF4040 },
            { 0x40FF40, 0x80FF80, 0xD0FFD0, 0xFFFFFF, 0xD0FFD0, 0x80FF80, 0x40FF40 },
            { 0x4080FF, 0x80B0FF, 0xD0E0FF, 0xFFFFFF, 0xD0E0FF, 0x80B0FF, 0x4080FF },
            { 0xFF40FF, 0xFF80FF, 0xFFD0FF, 0xFFFFFF, 0xFFD0FF, 0xFF80FF, 0xFF40FF },
    };
    private static final int BAR_H = 7;
    private static final int NUM_BARS = 4;

    // =====================================================================
    //  Scroll message
    // =====================================================================

    private static final String SCROLL_MSG =
            "          *** VPU v4.1 SHOWCASE ***    " +
                    "COPPER RASTER BARS \u00b7 DUAL-PLAYFIELD PARALLAX \u00b7 " +
                    "HARDWARE SPRITES WITH LYNX-STYLE SCALING \u00b7 " +
                    "8 BITPLANES / 256 COLORS \u00b7 " +
                    "80x25 TEXT OVERLAY WITH FINE-SCROLL \u00b7 " +
                    "CODED IN JAVA ON THE R816 PLATFORM \u00b7 " +
                    "GREETINGS TO ALL RETRO ENTHUSIASTS! \u00b7 " +
                    "          ";

    // =====================================================================
    //  State
    // =====================================================================

    private VPU vpu;
    private volatile int frame;

    private static final int N_SPR = 16;
    private final int[] sprCX   = new int[N_SPR];
    private final int[] sprCY   = new int[N_SPR];
    private final int[] sprAmpX = new int[N_SPR];
    private final int[] sprAmpY = new int[N_SPR];
    private final int[] sprPhase= new int[N_SPR];
    private final int[] sprSpd  = new int[N_SPR];
    private final int[] sprScl  = new int[N_SPR];

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas();
        vpu = new VPU_v4_1(CFG, bit -> {}, 0, canvas);

        initDemo();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, CFG.canvasWidth(), CFG.canvasHeight());
        scene.getRoot().setStyle("-fx-background-color: black;");
        stage.setTitle("VPU v4.1 — Demoscene Showcase");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        Thread emu = new Thread(() -> {
            final long FRAME_NS = 14_285_714L; // ~70 Hz
            long next = System.nanoTime();
            while (!Thread.interrupted()) {
                long now = System.nanoTime();
                if (now >= next) {
                    updateFrame();
                    vpu.tick(CPF);
                    frame++;
                    next += FRAME_NS;
                    if (next < now) next = now;
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "vpu-demo");
        emu.setDaemon(true);
        emu.start();

        AnimationTimer animator = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        animator.start();

        stage.setOnCloseRequest(e -> { emu.interrupt(); Platform.exit(); });
    }

    public static void main(String[] args) { launch(args); }

    private void initDemo() {
        setupPalette();
        drawMountains();
        drawCityscape();
        configureGroups();
        drawSpritePatterns();
        configureSpriteOAM();

        // Copper reads from tables bank (VBANK=8) at COP_OFS.
        mmio(COP_OFS_L, TBL_COPPER_BASE & 0xFF);
        mmio(COP_OFS_H, (TBL_COPPER_BASE >> 8) & 0xFF);
        buildCopper(0);

        setupText();

        mmio(CTRL,     0x01);
        mmio(COP_CTRL, 0x01);
        mmio(SPR_CTRL, 0x01);
    }

    // =====================================================================
    //  Palette setup
    // =====================================================================

    private void setupPalette() {
        setPal(0, 0x000000);

        // Group 0 palette (0-15)
        setPal(1,  0x8090B0);
        setPal(2,  0x607090);
        setPal(3,  0x509060);
        setPal(4,  0x307040);
        setPal(5,  0x205830);
        setPal(6,  0x184020);
        setPal(7,  0x604830);
        setPal(8,  0x483020);
        setPal(9,  0x80A060);
        setPal(10, 0xA0B880);
        setPal(11, 0x382818);
        setPal(12, 0xC0D0A0);
        setPal(13, 0x986830);
        setPal(14, 0x706050);
        setPal(15, 0xD0E0B0);

        // Group 1 palette (16-31)
        setPal(16, 0x000000);
        setPal(17, 0x181828);
        setPal(18, 0x282838);
        setPal(19, 0x404058);
        setPal(20, 0xFFD040);
        setPal(21, 0xFFE880);
        setPal(22, 0x505068);
        setPal(23, 0x60C0FF);
        setPal(24, 0xFF8020);
        setPal(25, 0x303040);
        setPal(26, 0x686880);
        setPal(27, 0x101018);
        setPal(28, 0xA0A0B0);
        setPal(29, 0xFF4040);
        setPal(30, 0x40FF40);
        setPal(31, 0x202030);

        // Sprite palette (32-47)
        setPal(32, 0x000000);
        setPal(33, 0xFF2020);
        setPal(34, 0xFF6060);
        setPal(35, 0xFFA0A0);
        setPal(36, 0xFFD0D0);
        setPal(37, 0x20FF20);
        setPal(38, 0x60FF60);
        setPal(39, 0xA0FFA0);
        setPal(40, 0x2060FF);
        setPal(41, 0x6090FF);
        setPal(42, 0xA0C0FF);
        setPal(43, 0xFFFF20);
        setPal(44, 0xFF20FF);
        setPal(45, 0x20FFFF);
        setPal(46, 0xFFFFFF);
        setPal(47, 0x808080);

        for (int i = 48; i < 256; i++) {
            int r = ((i - 48) * 6 / 208) * 51;
            int g = (((i - 48) * 36 / 208) % 6) * 51;
            int b = ((i - 48) % 6) * 51;
            setPal(i, (r << 16) | (g << 8) | b);
        }
    }

    // =====================================================================
    //  Bitplane art — mountains (Group 0, planes 0-3)
    // =====================================================================

    private void drawMountains() {
        for (int x = 0; x < LW; x++) {
            int h = 65 + msin(x, 1, 0, 25) + msin(x, 3, 80, 12) + msin(x, 7, 200, 5);
            fillColumn(0, 3, x, h, LH, 1, 2);
        }
        for (int x = 0; x < LW; x++) {
            int h = 95 + msin(x, 2, 40, 28) + msin(x, 5, 150, 14) + msin(x, 11, 60, 4);
            fillColumn(0, 3, x, h, LH, 3, 4);
        }
        for (int x = 0; x < LW; x++) {
            int h = 125 + msin(x, 3, 100, 22) + msin(x, 8, 220, 10) + msin(x, 13, 30, 3);
            fillColumn(0, 3, x, h, LH, 5, 6);
        }
        for (int x = 0; x < LW; x++) fillColumn(0, 3, x, 160, LH, 7, 7);
    }

    private static int msin(int x, int freq, int phaseDeg, int amp) {
        double angle = x * Math.PI * 2.0 * freq / LW + Math.toRadians(phaseDeg);
        return (int) Math.round(amp * Math.sin(angle));
    }

    private void fillColumn(int planeStart, int planeEnd, int x, int yTop, int yBot, int peakCol, int fillCol) {
        yTop = Math.max(0, Math.min(yTop, LH));
        yBot = Math.min(yBot, LH);
        if (yTop >= yBot) return;
        setPixelGroup(planeStart, planeEnd, x, yTop, peakCol);
        for (int y = yTop + 1; y < yBot; y++)
            setPixelGroup(planeStart, planeEnd, x, y, fillCol);
    }

    // =====================================================================
    //  Bitplane art — cityscape (Group 1, planes 4-7)
    // =====================================================================

    private void drawCityscape() {
        long seed = 42;
        int bx = 0;
        while (bx < LW) {
            seed = lcg(seed);
            int bw = 6 + (int)(seed & 0x07);
            seed = lcg(seed);
            int bh = 25 + (int)((seed & 0x3F));
            seed = lcg(seed);
            int shade = 17 + (int)(seed & 0x03);

            int by = LH - bh;

            for (int y = by; y < LH; y++) {
                for (int cx = bx; cx < bx + bw && cx < LW; cx++) {
                    int color;
                    if (cx == bx || cx == bx + bw - 1) color = 22;
                    else if (y == by) color = 26;
                    else if (isWindow(cx - bx, y - by, bw, bh, seed)) {
                        seed = lcg(seed);
                        color = ((seed & 3) == 0) ? 23 : ((seed & 1) == 0 ? 20 : 21);
                    } else {
                        color = shade;
                    }
                    setPixelGroup(4, 7, cx, y, color - 16);
                }
            }

            seed = lcg(seed);
            bx += bw + 1 + (int)(seed & 0x03);
        }
    }

    private static boolean isWindow(int lx, int ly, int bw, int bh, long seed) {
        if (ly < 4 || lx < 2 || lx >= bw - 2) return false;
        int wx = (lx - 2) % 4, wy = (ly - 4) % 6;
        if (wx >= 2 || wy >= 3) return false;
        long h = (seed ^ (lx * 7919L + ly * 104729L)) & 0xFFFFL;
        return h > 0x4000;
    }

    private static long lcg(long s) { return (s * 6364136223846793005L + 1442695040888963407L); }

    // =====================================================================
    //  Sprite patterns (bank 0x9, 4bpp packed)
    // =====================================================================

    private void drawSpritePatterns() {
        drawBall(0x0000, new int[]{0, 1, 2, 3, 4, 3, 2, 1});
        drawBall(0x0080, new int[]{0, 8, 9, 10, 11, 10, 9, 8});
        drawDiamond(0x0100);
        drawStar(0x0180);
    }

    private void drawBall(int offset, int[] shadeMap) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < 7.0f) {
                    float lightDist = (float) Math.sqrt((dx + 3) * (dx + 3) + (dy + 3) * (dy + 3));
                    int shade = Math.min(6, Math.max(0, (int)(lightDist / 2.2f)));
                    int color = (shade < shadeMap.length) ? shadeMap[shade] : shadeMap[shadeMap.length - 1];
                    if (color == 0) color = 1;
                    sprPx(offset, x, y, 8, color);
                }
            }
        }
    }

    private void drawDiamond(int offset) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int dist = Math.abs(x - 8) + Math.abs(y - 8);
                if (dist < 8) {
                    int color = 1 + Math.min(3, dist / 2);
                    sprPx(offset, x, y, 8, color);
                }
            }
        }
    }

    private void drawStar(int offset) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float angle = (float) Math.atan2(dy, dx);
                float arm = (float) (3.5 + 3.5 * Math.cos(angle * 5));
                if (dist < arm && dist < 7.5f) {
                    int color = dist < arm * 0.4f ? 4 : dist < arm * 0.7f ? 3 : 2;
                    sprPx(offset, x, y, 8, color);
                }
            }
        }
    }

    private void configureSpriteOAM() {
        java.util.Random rng = new java.util.Random(7);

        int bank = BANK_SPRITES0 & 0x0F; // 0x9
        int bankLo = bank & 0x03;        // DATA[15..14]
        int bankHi = (bank >>> 2) & 0x03;// ATTR[7..6]

        for (int i = 0; i < N_SPR; i++) {
            int patternIdx = i % 4;
            int dataOfs = patternIdx * 0x80;
            int palBase = 32;

            sprCX[i]   = 80 + rng.nextInt(480);
            sprCY[i]   = 30 + rng.nextInt(120);
            sprAmpX[i] = 40 + rng.nextInt(100);
            sprAmpY[i] = 15 + rng.nextInt(50);
            sprPhase[i]= rng.nextInt(256);
            sprSpd[i]  = 1 + rng.nextInt(3);

            int[] scales = { 0x0100, 0x00C0, 0x0080, 0x00A0 };
            sprScl[i] = scales[i & 3];

            int attr = SA_EN | (bankHi << 6);
            int dataWord = (bankLo << 14) | (dataOfs & 0x3FFF);

            writeOAM(i, sprCX[i], sprCY[i], 16, 16, dataWord,
                    attr, palBase, 0, (i < 8) ? 0 : 1, sprScl[i], sprScl[i]);
        }
    }

    // =====================================================================
    //  Groups
    // =====================================================================

    private void configureGroups() {
        setGroup(0, 0, 4, 0, 0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        setGroup(1, 4, 4, 16, 0, 0, 1, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        setGroup(2, 0, 0, 0, 0, 0, 2, 0, 0);
        setGroup(3, 0, 0, 0, 0, 0, 3, 0, 0);
    }

    // =====================================================================
    //  Text
    // =====================================================================

    private void setupText() {
        mmio(TX_CTRL, 0x01 | 0x04);
        mmio(TX_CMD, 0x04);

        String title = "\u00db\u00db VPU v4.1 \u00db\u00db";
        int tx = (80 - title.length()) / 2;
        putString(0, tx, title, 0x0F);

        String sub = "DEMOSCENE SHOWCASE";
        putString(1, (80 - sub.length()) / 2, sub, 0x0B);

        putString(3, 2, "COPPER \u00b7 PARALLAX \u00b7 SPRITES \u00b7 8 BITPLANES \u00b7 256 COLORS", 0x0E);

        updateTextScroller(0);
    }

    // =====================================================================
    //  Copper
    // =====================================================================

    private void buildCopper(int frameNum) {
        int[] barY = new int[NUM_BARS];
        for (int b = 0; b < NUM_BARS; b++) {
            barY[b] = 50 + sin256(frameNum * 2 + b * 64) * 45 / 127;
        }

        int idx = 0;
        for (int ly = 0; ly < LH; ly++) {
            int displayScan = ly * 2;
            int skyRgb = skyGradient(ly, frameNum);

            for (int b = 0; b < NUM_BARS; b++) {
                int dist = ly - barY[b];
                if (dist >= 0 && dist < BAR_H) skyRgb = BAR_COLORS[b][dist];
            }

            int rgb565 = toRgb565(skyRgb);
            copEntry(idx++, displayScan, PAL_BASE, rgb565 & 0xFF, (rgb565 >> 8) & 0xFF, COP_W16);
        }

        int windowPulse = 128 + sin256(frameNum * 4) * 60 / 127;
        int warmWindow = toRgb565((windowPulse << 16) | ((windowPulse * 3 / 4) << 8) | 0x00);
        copEntry(idx++, 280, PAL_BASE + 20 * 2, warmWindow & 0xFF, (warmWindow >> 8) & 0xFF, COP_W16);

        copEntry(idx++, 0, 0, 0, 0, COP_END);

        int totalBytes = idx * 8;
        mmio(COP_LEN_L, totalBytes & 0xFF);
        mmio(COP_LEN_H, (totalBytes >> 8) & 0xFF);
    }

    private int skyGradient(int ly, int frameNum) {
        int breathe = sin256(frameNum) * 8 / 127;
        int adjY = Math.max(0, Math.min(ly + breathe, LH - 1));

        for (int k = 0; k < SKY_POS.length - 1; k++) {
            if (adjY >= SKY_POS[k] && adjY < SKY_POS[k + 1]) {
                float t = (float)(adjY - SKY_POS[k]) / (SKY_POS[k + 1] - SKY_POS[k]);
                return lerpRgb(SKY_KEYS[k], SKY_KEYS[k + 1], t);
            }
        }
        return SKY_KEYS[SKY_KEYS.length - 1];
    }

    // =====================================================================
    //  Per-frame update
    // =====================================================================

    private void updateFrame() {
        int f = frame;

        int scroll0 = f / 2;
        int scroll1 = f;
        setGroupScroll(0, scroll0, 0);
        setGroupScroll(1, scroll1, 0);

        for (int i = 0; i < N_SPR; i++) {
            int phase = (f * sprSpd[i] + sprPhase[i]) & 0xFF;
            int sx = sprCX[i] + sin256(phase) * sprAmpX[i] / 127;
            int sy = sprCY[i] + sin256(phase + 64) * sprAmpY[i] / 127;

            sx = ((sx % 640) + 640) % 640;
            sy = ((sy % 180) + 180) % 180 + 10; // y is in ySrc space (0..199)

            int scale = sprScl[i];
            if ((i & 1) == 0) {
                int pulse = 256 + sin256(f * 3 + i * 30) * 80 / 127;
                scale = Math.max(0x40, Math.min(0x0200, sprScl[i] * pulse / 256));
            }

            oamWord(i, 0, sx);
            oamWord(i, 2, sy);
            oamWord(i, 12, scale);
            oamWord(i, 14, scale);
        }

        buildCopper(f);
        if (f % 3 == 0) updateTextScroller(f / 3);
    }

    private void updateTextScroller(int pos) {
        int len = SCROLL_MSG.length();
        for (int col = 0; col < 80; col++) {
            int ci = (pos + col) % len;
            char ch = SCROLL_MSG.charAt(ci);
            int colIdx = (pos + col) & 0x0F;
            int attr = (colIdx < 1) ? 0x0F : (0x09 + (colIdx % 7));
            putChar(24, col, ch, attr);
        }
    }

    // =====================================================================
    //  VPU access helpers
    // =====================================================================

    private void mmio(int reg, int val) {
        vpu.writeMmio(reg, (byte)(val & 0xFF));
    }

    private void setPal(int idx, int rgb24) {
        int v = toRgb565(rgb24);
        int ofs = PAL_BASE + idx * 2;
        mmio(ofs,     v & 0xFF);
        mmio(ofs + 1, (v >> 8) & 0xFF);
    }

    private void setPixelGroup(int planeStart, int planeEnd, int x, int y, int color) {
        if (x < 0 || x >= LW || y < 0 || y >= LH) return;
        int byteOfs = y * BPR + (x >> 3);
        int bit = 1 << (7 - (x & 7));
        int nbit = ~bit & 0xFF;

        for (int p = planeStart; p <= planeEnd; p++) {
            int bank = BANK_PLANE0 + p; // plane 0..7 => bank 0..7
            byte cur = vpu.readVramPlane(bank, byteOfs);
            int cv = cur & 0xFF;
            int planeBit = (color >> (p - planeStart)) & 1;
            cv = (planeBit != 0) ? (cv | bit) : (cv & nbit);
            vpu.writeVramPlane(bank, byteOfs, (byte) cv);
        }
    }

    private void sprPx(int dataOfs, int x, int y, int rowStride, int color) {
        int ofs = dataOfs + y * rowStride + (x >> 1);
        byte cur = vpu.readVramPlane(BANK_SPRITES0, ofs);
        int cv = cur & 0xFF;
        cv = (x & 1) == 0
                ? (cv & 0x0F) | ((color & 0x0F) << 4)
                : (cv & 0xF0) | (color & 0x0F);
        vpu.writeVramPlane(BANK_SPRITES0, ofs, (byte) cv);
    }

    private void oamWord(int sprIdx, int fieldOfs, int val) {
        int ofs = OAM_MMIO_BASE + sprIdx * 32 + fieldOfs;
        mmio(ofs,     val & 0xFF);
        mmio(ofs + 1, (val >> 8) & 0xFF);
    }

    private void oamByte(int sprIdx, int fieldOfs, int val) {
        int ofs = OAM_MMIO_BASE + sprIdx * 32 + fieldOfs;
        mmio(ofs, val & 0xFF);
    }

    private void writeOAM(int idx, int x, int y, int w, int h, int dataWord,
                          int attr, int palBase, int colId, int priority,
                          int xstep, int ystep) {
        oamWord(idx, 0,  x);
        oamWord(idx, 2,  y);
        oamByte(idx, 4,  w);
        oamByte(idx, 5,  h);
        oamWord(idx, 6,  dataWord & 0xFFFF);
        oamByte(idx, 8,  attr);
        oamByte(idx, 9,  palBase);
        oamByte(idx, 10, colId);
        oamByte(idx, 11, priority);
        oamWord(idx, 12, xstep);
        oamWord(idx, 14, ystep);
    }

    private void copEntry(int idx, int scanline, int reg, int valLo, int valHi, int flags) {
        int base = TBL_COPPER_BASE + idx * 8;
        vpu.writeVramPlane(BANK_TABLES, base,     (byte)(scanline & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 1, (byte)((scanline >> 8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 2, (byte)(reg & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 3, (byte)((reg >> 8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 4, (byte)(valLo & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 5, (byte)(valHi & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 6, (byte)(flags & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, base + 7, (byte) 0);
    }

    private void setGroup(int gi, int planeStart, int planeCount, int palBase,
                          int scrollX, int scrollY, int priority, int flags, int bplOfs) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 0, planeStart);
        mmio(base + 1, planeCount);
        mmio(base + 2, palBase);
        mmio(base + 3, scrollX & 0xFF);
        mmio(base + 4, (scrollX >> 8) & 0xFF);
        mmio(base + 5, scrollY & 0xFF);
        mmio(base + 6, priority);
        mmio(base + 7, flags);
        mmio(base + 8, bplOfs & 0xFF);
        mmio(base + 9, (bplOfs >> 8) & 0xFF);
    }

    private void setGroupScroll(int gi, int scrollX, int scrollY) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 3, scrollX & 0xFF);
        mmio(base + 4, (scrollX >> 8) & 0xFF);
        mmio(base + 5, scrollY & 0xFF);
    }

    private void putChar(int row, int col, int ch, int attr) {
        int ofs = TEXT_BASE + (row * 80 + col) * 2;
        mmio(ofs,     ch & 0xFF);
        mmio(ofs + 1, attr & 0xFF);
    }

    private void putString(int row, int col, String s, int attr) {
        for (int i = 0; i < s.length() && col + i < 80; i++) {
            putChar(row, col + i, s.charAt(i), attr);
        }
    }

    // =====================================================================
    //  Color utilities
    // =====================================================================

    private static int toRgb565(int rgb24) {
        int r = (rgb24 >> 16) & 0xFF, g = (rgb24 >> 8) & 0xFF, b = rgb24 & 0xFF;
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xF8) >> 3);
    }

    private static int lerpRgb(int c0, int c1, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int)((c0 >> 16 & 0xFF) + ((c1 >> 16 & 0xFF) - (c0 >> 16 & 0xFF)) * t);
        int g = (int)((c0 >> 8  & 0xFF) + ((c1 >> 8  & 0xFF) - (c0 >> 8  & 0xFF)) * t);
        int b = (int)((c0       & 0xFF) + ((c1       & 0xFF) - (c0       & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}