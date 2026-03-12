package io.github.robincores.r8;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * <h1>VPU v4 — Demoscene Showcase</h1>
 *
 * <p>Standalone JavaFX application that drives a {@link VPU_v4} directly (no CPU)
 * to demonstrate every major subsystem:</p>
 * <ul>
 *   <li><b>Copper</b> — per-scanline sky gradient + 4 animated raster bars</li>
 *   <li><b>Dual-Playfield Parallax</b> — 3 mountain ranges (group 0) + cityscape (group 1)</li>
 *   <li><b>Hardware Sprites</b> — 16 bouncing balls with sine-wave motion &amp; Lynx-style scaling</li>
 *   <li><b>Text Overlay</b> — transparent credits scroller over graphics</li>
 *   <li><b>Palette</b> — full 256-color display with per-scanline copper cycling</li>
 * </ul>
 *
 * <p>Run: {@code java --module-path <fx> --add-modules javafx.controls,javafx.graphics io.github.robincores.r8.VpuShowcase}</p>
 */
public class Vpu4Showcase extends Application {

    // =====================================================================
    //  Platform constants (mirrors R816System)
    // =====================================================================

    private static final DisplayConfig CFG = new DisplayConfig(640, 400, 640, 400, 400, 449, 400);
    private static final int CPF = CFG.cyclesPerFrame(); // cycles per frame

    // Logical resolution (LORES groups)
    private static final int LW = 320, LH = 200, BPR = LW / 8; // 40 bytes/row

    // MMIO addresses
    private static final int CTRL         = 0x0000, STATUS     = 0x0001;
    private static final int TX_CTRL      = 0x0006, TX_ATTR    = 0x000F;
    private static final int TX_CMD       = 0x0030, TX_PORT    = 0x0031;
    private static final int TX_CUR_X     = 0x0007, TX_CUR_Y   = 0x0008;
    private static final int COP_CTRL     = 0x0020;
    private static final int COP_LEN_L    = 0x0021, COP_LEN_H  = 0x0022;
    private static final int SPR_CTRL     = 0x0014;
    private static final int GROUP_BASE   = 0x0400;
    private static final int TEXT_BASE    = 0x2000;
    private static final int PAL_BASE     = 0x0100;

    // Copper flags
    private static final int COP_W16 = 0x01, COP_END = 0x80;

    // Group flags
    private static final int GF_LORES = 0x01, GF_WRAP_X = 0x02, GF_WRAP_Y = 0x04;

    // Sprite OAM
    private static final int SA_EN = 0x01;

    // =====================================================================
    //  Sine table (256 entries, amplitude ±127)
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
            0x040820,   // 0   deep space
            0x081848,   // 40  dark navy
            0x184888,   // 80  mid blue
            0x50A0D0,   // 120 light cyan
            0xE0C050,   // 150 golden horizon
            0xC04020,   // 170 sunset red
            0x301018,   // 190 dark ground
            0x100808,   // 200 black ground
    };
    private static final int[] SKY_POS = { 0, 40, 80, 120, 150, 170, 190, 200 };

    // =====================================================================
    //  Raster bar colors (4 bars, 10 lines each)
    // =====================================================================

    private static final int[][] BAR_COLORS = {
            { 0xFF4040, 0xFF8080, 0xFFD0D0, 0xFFFFFF, 0xFFD0D0, 0xFF8080, 0xFF4040 },  // red
            { 0x40FF40, 0x80FF80, 0xD0FFD0, 0xFFFFFF, 0xD0FFD0, 0x80FF80, 0x40FF40 },  // green
            { 0x4080FF, 0x80B0FF, 0xD0E0FF, 0xFFFFFF, 0xD0E0FF, 0x80B0FF, 0x4080FF },  // blue
            { 0xFF40FF, 0xFF80FF, 0xFFD0FF, 0xFFFFFF, 0xFFD0FF, 0xFF80FF, 0xFF40FF },  // magenta
    };
    private static final int BAR_H = 7;
    private static final int NUM_BARS = 4;

    // =====================================================================
    //  Scroll message
    // =====================================================================

    private static final String SCROLL_MSG =
            "          *** VPU v4 SHOWCASE ***    " +
                    "COPPER RASTER BARS \u00b7 DUAL-PLAYFIELD PARALLAX \u00b7 " +
                    "128 HARDWARE SPRITES WITH LYNX-STYLE SCALING \u00b7 " +
                    "8 BITPLANES / 256 COLORS \u00b7 " +
                    "HARDWARE BLITTER \u00b7 VGA WRITE MODES \u00b7 " +
                    "80x25 TEXT OVERLAY WITH FINE-SCROLL \u00b7 " +
                    "CODED IN JAVA ON THE R816 PLATFORM \u00b7 " +
                    "GREETINGS TO ALL RETRO ENTHUSIASTS! \u00b7 " +
                    "          ";

    // =====================================================================
    //  State
    // =====================================================================

    private VPU_v4 vpu;
    private volatile int frame;

    // Sprite animation (16 sprites)
    private static final int N_SPR = 16;
    private final int[] sprCX   = new int[N_SPR]; // center X (display coords)
    private final int[] sprCY   = new int[N_SPR]; // center Y (logical coords)
    private final int[] sprAmpX = new int[N_SPR];
    private final int[] sprAmpY = new int[N_SPR];
    private final int[] sprPhase= new int[N_SPR];
    private final int[] sprSpd  = new int[N_SPR];
    private final int[] sprScl  = new int[N_SPR]; // xstep/ystep (8.8)

    private int scrollPos;

    // =====================================================================
    //  JavaFX lifecycle
    // =====================================================================

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas();
        vpu = new VPU_v4(CFG, bit -> {}, 0, canvas);

        initDemo();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, CFG.canvasWidth(), CFG.canvasHeight());
        scene.getRoot().setStyle("-fx-background-color: black;");
        stage.setTitle("VPU v4 \u2014 Demoscene Showcase");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        // Emulation thread — tick VPU at ~70 Hz
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
                    if (next < now) next = now; // catch up after stall
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "vpu-demo");
        emu.setDaemon(true);
        emu.start();

        // FX thread — present frames
        AnimationTimer animator = new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        };
        animator.start();

        stage.setOnCloseRequest(e -> { emu.interrupt(); Platform.exit(); });
    }

    public static void main(String[] args) { launch(args); }

    // =====================================================================
    //  Init — set up the entire demo scene
    // =====================================================================

    private void initDemo() {
        // 1. Palette
        setupPalette();

        // 2. Bitplane art
        drawMountains();
        drawCityscape();

        // 3. Groups
        configureGroups();

        // 4. Sprites
        drawSpritePatterns();
        configureSpriteOAM();

        // 5. Copper (initial gradient)
        buildCopper(0);

        // 6. Text
        setupText();

        // 7. Enable everything
        mmio(CTRL,     0x01);       // VPU enable
        mmio(COP_CTRL, 0x01);       // Copper enable
        mmio(SPR_CTRL, 0x01);       // Sprites enable
    }

    // =====================================================================
    //  Palette setup — 256 colors
    // =====================================================================

    private void setupPalette() {
        // Index 0: background (copper-driven, init to black)
        setPal(0, 0x000000);

        // Group 0 palette (indices 0-15): mountain/landscape tones
        setPal(1,  0x8090B0);  // distant peak (blue-gray)
        setPal(2,  0x607090);  // distant body
        setPal(3,  0x509060);  // mid peak (sage)
        setPal(4,  0x307040);  // mid body (forest)
        setPal(5,  0x205830);  // near peak (dark forest)
        setPal(6,  0x184020);  // near body (deep green)
        setPal(7,  0x604830);  // ground (brown)
        setPal(8,  0x483020);  // dark ground
        setPal(9,  0x80A060);  // grass highlight
        setPal(10, 0xA0B880);  // light foliage
        setPal(11, 0x382818);  // rock shadow
        setPal(12, 0xC0D0A0);  // sunlit grass
        setPal(13, 0x986830);  // warm brown
        setPal(14, 0x706050);  // neutral gray
        setPal(15, 0xD0E0B0);  // bright highlight

        // Group 1 palette (indices 16-31): cityscape
        setPal(16, 0x000000);  // transparent (same as bg)
        setPal(17, 0x181828);  // dark building
        setPal(18, 0x282838);  // medium building
        setPal(19, 0x404058);  // light building
        setPal(20, 0xFFD040);  // warm window
        setPal(21, 0xFFE880);  // bright window
        setPal(22, 0x505068);  // building edge
        setPal(23, 0x60C0FF);  // cold window (blue)
        setPal(24, 0xFF8020);  // neon sign
        setPal(25, 0x303040);  // shadow
        setPal(26, 0x686880);  // roof
        setPal(27, 0x101018);  // deepest shadow
        setPal(28, 0xA0A0B0);  // concrete
        setPal(29, 0xFF4040);  // red light
        setPal(30, 0x40FF40);  // green light
        setPal(31, 0x202030);  // night

        // Sprite palette (indices 32-47): vibrant ball colors
        setPal(32, 0x000000);  // transparent
        setPal(33, 0xFF2020);  // red core
        setPal(34, 0xFF6060);  // red mid
        setPal(35, 0xFFA0A0);  // red highlight
        setPal(36, 0xFFD0D0);  // red bright
        setPal(37, 0x20FF20);  // green core
        setPal(38, 0x60FF60);  // green mid
        setPal(39, 0xA0FFA0);  // green highlight
        setPal(40, 0x2060FF);  // blue core
        setPal(41, 0x6090FF);  // blue mid
        setPal(42, 0xA0C0FF);  // blue highlight
        setPal(43, 0xFFFF20);  // yellow
        setPal(44, 0xFF20FF);  // magenta
        setPal(45, 0x20FFFF);  // cyan
        setPal(46, 0xFFFFFF);  // white
        setPal(47, 0x808080);  // gray

        // Fill 48-255 with a color cube for general use
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
        // 3 mountain ranges, each with composite-sine height profile.
        // Range 1 (far): colors 1-2
        for (int x = 0; x < LW; x++) {
            int h = 65 + msin(x, 1, 0, 25) + msin(x, 3, 80, 12) + msin(x, 7, 200, 5);
            fillColumn(0, 3, x, h, LH, 1, 2);
        }
        // Range 2 (mid): colors 3-4
        for (int x = 0; x < LW; x++) {
            int h = 95 + msin(x, 2, 40, 28) + msin(x, 5, 150, 14) + msin(x, 11, 60, 4);
            fillColumn(0, 3, x, h, LH, 3, 4);
        }
        // Range 3 (near): colors 5-6
        for (int x = 0; x < LW; x++) {
            int h = 125 + msin(x, 3, 100, 22) + msin(x, 8, 220, 10) + msin(x, 13, 30, 3);
            fillColumn(0, 3, x, h, LH, 5, 6);
        }
        // Ground fill (color 7) from y=160 to bottom
        for (int x = 0; x < LW; x++) fillColumn(0, 3, x, 160, LH, 7, 7);
    }

    /** Periodic sine: amplitude * sin(x * 2π * freq / 320 + phase_deg) */
    private static int msin(int x, int freq, int phaseDeg, int amp) {
        double angle = x * Math.PI * 2.0 * freq / LW + Math.toRadians(phaseDeg);
        return (int) Math.round(amp * Math.sin(angle));
    }

    /** Fill column x from yTop to yBot with peakColor at top row, fillColor below. */
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
        // Generate building columns
        int bx = 0;
        while (bx < LW) {
            seed = lcg(seed);
            int bw = 6 + (int)(seed & 0x07);             // width 6-13
            seed = lcg(seed);
            int bh = 25 + (int)((seed & 0x3F));           // height 25-88
            seed = lcg(seed);
            int shade = 17 + (int)(seed & 0x03);           // color 17-20

            int by = LH - bh;

            for (int y = by; y < LH; y++) {
                for (int cx = bx; cx < bx + bw && cx < LW; cx++) {
                    int color;
                    if (cx == bx || cx == bx + bw - 1) {
                        color = 22; // edge
                    } else if (y == by) {
                        color = 26; // roof
                    } else if (isWindow(cx - bx, y - by, bw, bh, seed)) {
                        seed = lcg(seed);
                        color = ((seed & 3) == 0) ? 23 : ((seed & 1) == 0 ? 20 : 21); // varied windows
                    } else {
                        color = shade;
                    }
                    setPixelGroup(4, 7, cx, y, color - 16); // palBase=16, so raw index is color-16
                }
            }

            seed = lcg(seed);
            bx += bw + 1 + (int)(seed & 0x03); // gap 1-4
        }
    }

    private static boolean isWindow(int lx, int ly, int bw, int bh, long seed) {
        if (ly < 4 || lx < 2 || lx >= bw - 2) return false;
        // Grid of 2×3 windows every 4×6 pixels
        int wx = (lx - 2) % 4, wy = (ly - 4) % 6;
        if (wx >= 2 || wy >= 3) return false;
        // Random dark windows
        long h = (seed ^ (lx * 7919L + ly * 104729L)) & 0xFFFFL;
        return h > 0x4000; // ~75% lit
    }

    private static long lcg(long s) { return (s * 6364136223846793005L + 1442695040888963407L); }

    // =====================================================================
    //  Sprite patterns (bank 8, 4bpp packed)
    // =====================================================================

    private void drawSpritePatterns() {
        // Pattern 0 @ offset 0x0000: 16×16 shaded ball (warm)
        drawBall(0x0000, new int[]{0, 1, 2, 3, 4, 3, 2, 1}); // palette: 0=trans, 1-4=shading

        // Pattern 1 @ offset 0x0080: 16×16 shaded ball (cool)
        drawBall(0x0080, new int[]{0, 8, 9, 10, 11, 10, 9, 8}); // blue shading via remap

        // Pattern 2 @ offset 0x0100: 16×16 diamond
        drawDiamond(0x0100);

        // Pattern 3 @ offset 0x0180: 16×16 star
        drawStar(0x0180);
    }

    private void drawBall(int offset, int[] shadeMap) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < 7.0f) {
                    // Shade: highlight at upper-left
                    float lightDist = (float) Math.sqrt((dx + 3) * (dx + 3) + (dy + 3) * (dy + 3));
                    int shade = Math.min(6, Math.max(0, (int)(lightDist / 2.2f)));
                    int color = (shade < shadeMap.length) ? shadeMap[shade] : shadeMap[shadeMap.length - 1];
                    if (color == 0) color = 1; // never transparent inside ball
                    sprPx(offset, x, y, 8, color);
                } // else: 0 = transparent (already cleared)
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

    // =====================================================================
    //  Sprite OAM setup
    // =====================================================================

    private void configureSpriteOAM() {
        java.util.Random rng = new java.util.Random(7);

        for (int i = 0; i < N_SPR; i++) {
            int patternIdx = i % 4;
            int dataOfs    = patternIdx * 0x80;
            int palIdx     = 2;                  // palette subgroup 2 (sprite colors at 32-47)

            // Motion parameters
            sprCX[i]   = 80 + rng.nextInt(480);  // center X (display coords 0-639)
            sprCY[i]   = 30 + rng.nextInt(120);   // center Y (logical 0-199)
            sprAmpX[i] = 40 + rng.nextInt(100);
            sprAmpY[i] = 15 + rng.nextInt(50);
            sprPhase[i]= rng.nextInt(256);
            sprSpd[i]  = 1 + rng.nextInt(3);

            // Scaling: alternate between 1x, 1.5x, 2x
            int[] scales = { 0x0100, 0x00C0, 0x0080, 0x00A0 };
            sprScl[i] = scales[i & 3];

            writeOAM(i, sprCX[i], sprCY[i], 16, 16, dataOfs,
                    SA_EN, palIdx, 0, (i < 8) ? 1 : 2, sprScl[i], sprScl[i]);
        }
    }

    // =====================================================================
    //  Group configuration
    // =====================================================================

    private void configureGroups() {
        // Group 0: planes 0-3 (16 colors), mountains, palBase=0, priority 0
        setGroup(0, 0, 4, 0, 0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);

        // Group 1: planes 4-7 (16 colors), city, palBase=16, priority 1
        setGroup(1, 4, 4, 16, 0, 0, 1, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);

        // Groups 2-3: disabled
        setGroup(2, 0, 0, 0, 0, 0, 2, 0, 0);
        setGroup(3, 0, 0, 0, 0, 0, 3, 0, 0);
    }

    // =====================================================================
    //  Text setup
    // =====================================================================

    private void setupText() {
        // Transparent background text overlay
        mmio(TX_CTRL, 0x01 | 0x04); // TX_EN | TX_TRANSPARENT_BG

        // Clear screen
        mmio(TX_CMD, 0x04); // TXCMD_CLR_SCREEN

        // Title (row 0, centered)
        String title = "\u00db\u00db VPU v4 \u00db\u00db";
        int tx = (80 - title.length()) / 2;
        putString(0, tx, title, 0x0F); // bright white on transparent

        // Subtitle (row 1)
        String sub = "DEMOSCENE SHOWCASE";
        putString(1, (80 - sub.length()) / 2, sub, 0x0B); // cyan

        // Feature list (row 3)
        putString(3, 2, "COPPER \u00b7 PARALLAX \u00b7 SPRITES \u00b7 8 BITPLANES \u00b7 256 COLORS", 0x0E);

        // Initial scroll text (row 24)
        updateTextScroller(0);
    }

    // =====================================================================
    //  Copper — sky gradient + raster bars
    // =====================================================================

    private void buildCopper(int frameNum) {
        // Compute raster bar Y positions (logical coords 0-199, sine motion)
        int[] barY = new int[NUM_BARS];
        for (int b = 0; b < NUM_BARS; b++) {
            barY[b] = 50 + sin256(frameNum * 2 + b * 64) * 45 / 127;
        }

        int idx = 0;
        for (int ly = 0; ly < LH; ly++) {
            int displayScan = ly * 2; // each logical line = 2 display lines

            // Base sky gradient
            int skyRgb = skyGradient(ly, frameNum);

            // Overlay raster bars
            for (int b = 0; b < NUM_BARS; b++) {
                int dist = ly - barY[b];
                if (dist >= 0 && dist < BAR_H) {
                    skyRgb = BAR_COLORS[b][dist];
                }
            }

            int rgb565 = toRgb565(skyRgb);
            copEntry(idx++, displayScan, PAL_BASE, rgb565 & 0xFF, (rgb565 >> 8) & 0xFF, COP_W16);
        }

        // Also animate city window palette via copper at specific scanlines
        int windowPulse = 128 + sin256(frameNum * 4) * 60 / 127;
        int warmWindow = toRgb565((windowPulse << 16) | ((windowPulse * 3 / 4) << 8) | 0x00);
        copEntry(idx++, 280, PAL_BASE + 20 * 2, warmWindow & 0xFF, (warmWindow >> 8) & 0xFF, COP_W16);

        // End marker
        copEntry(idx, 0, 0, 0, 0, COP_END);
        idx++;

        // Set copper length
        int totalBytes = idx * 8;
        mmio(COP_LEN_L, totalBytes & 0xFF);
        mmio(COP_LEN_H, (totalBytes >> 8) & 0xFF);
    }

    private int skyGradient(int ly, int frameNum) {
        // Animated breathing offset
        int breathe = sin256(frameNum) * 8 / 127;
        int adjY = Math.max(0, Math.min(ly + breathe, LH - 1));

        // Find the two keypoints to lerp between
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

        // 1. Parallax scrolling (group 0 slow, group 1 fast)
        int scroll0 = f / 2;          // slow mountains
        int scroll1 = f;              // fast city
        setGroupScroll(0, scroll0, 0);
        setGroupScroll(1, scroll1, 0);

        // 2. Sprite motion (sine-wave bouncing)
        for (int i = 0; i < N_SPR; i++) {
            int phase = (f * sprSpd[i] + sprPhase[i]) & 0xFF;
            int sx = sprCX[i] + sin256(phase) * sprAmpX[i] / 127;
            int sy = sprCY[i] + sin256(phase + 64) * sprAmpY[i] / 127;

            // Wrap into visible area
            sx = ((sx % 640) + 640) % 640;
            sy = ((sy % 180) + 180) % 180 + 10;

            // Pulsing scale effect on some sprites
            int scale = sprScl[i];
            if ((i & 1) == 0) {
                int pulse = 256 + sin256(f * 3 + i * 30) * 80 / 127;
                scale = Math.max(0x40, Math.min(0x0200, sprScl[i] * pulse / 256));
            }

            oamWord(i, 0, sx);         // X
            oamWord(i, 2, sy);         // Y
            oamWord(i, 12, scale);     // XSTEP
            oamWord(i, 14, scale);     // YSTEP
        }

        // 3. Copper animation (rebuild gradient + moving bars)
        buildCopper(f);

        // 4. Text scroller (advance every 3 frames)
        if (f % 3 == 0) updateTextScroller(f / 3);
    }

    private void updateTextScroller(int pos) {
        int len = SCROLL_MSG.length();
        for (int col = 0; col < 80; col++) {
            int ci = (pos + col) % len;
            char ch = SCROLL_MSG.charAt(ci);

            // Cycle text color across columns for rainbow effect
            int colIdx = (pos + col) & 0x0F;
            int attr = (colIdx < 1) ? 0x0F : (0x09 + (colIdx % 7));

            putChar(24, col, ch, attr);
        }
    }

    // =====================================================================
    //  VPU access helpers
    // =====================================================================

    /** Write a byte to an MMIO register. */
    private void mmio(int reg, int val) {
        vpu.writeMmio(reg, (byte)(val & 0xFF));
    }

    /** Set palette entry (RGB24 -> RGB565 -> MMIO). */
    private void setPal(int idx, int rgb24) {
        int v = toRgb565(rgb24);
        int ofs = PAL_BASE + idx * 2;
        mmio(ofs,     v & 0xFF);
        mmio(ofs + 1, (v >> 8) & 0xFF);
    }

    /** Set pixel in bitplanes [planeStart..planeEnd] for the given color index. */
    private void setPixelGroup(int planeStart, int planeEnd, int x, int y, int color) {
        if (x < 0 || x >= LW || y < 0 || y >= LH) return;
        int byteOfs = y * BPR + (x >> 3);
        int bit = 1 << (7 - (x & 7));
        int nbit = ~bit & 0xFF;
        for (int p = planeStart; p <= planeEnd; p++) {
            byte cur = vpu.readVramPlane(p, byteOfs);
            int cv = cur & 0xFF;
            int planeBit = (color >> (p - planeStart)) & 1;
            cv = planeBit != 0 ? (cv | bit) : (cv & nbit);
            vpu.writeVramPlane(p, byteOfs, (byte) cv);
        }
    }

    /** Set 4bpp sprite pixel in the sprite bank. */
    private void sprPx(int dataOfs, int x, int y, int rowStride, int color) {
        int ofs = dataOfs + y * rowStride + (x >> 1);
        byte cur = vpu.readVramPlane(8, ofs);
        int cv = cur & 0xFF;
        cv = (x & 1) == 0
                ? (cv & 0x0F) | ((color & 0x0F) << 4)
                : (cv & 0xF0) | (color & 0x0F);
        vpu.writeVramPlane(8, ofs, (byte) cv);
    }

    /** Write a 16-bit value to OAM. */
    private void oamWord(int sprIdx, int fieldOfs, int val) {
        int ofs = sprIdx * 32 + fieldOfs;
        vpu.writeVramPlane(9, ofs,     (byte)(val & 0xFF));
        vpu.writeVramPlane(9, ofs + 1, (byte)((val >> 8) & 0xFF));
    }

    /** Configure a full OAM entry. */
    private void writeOAM(int idx, int x, int y, int w, int h, int dataOfs,
                          int attr, int palIdx, int colId, int priority,
                          int xstep, int ystep) {
        oamWord(idx, 0,  x);
        oamWord(idx, 2,  y);
        vpu.writeVramPlane(9, idx * 32 + 4, (byte) w);
        vpu.writeVramPlane(9, idx * 32 + 5, (byte) h);
        oamWord(idx, 6,  dataOfs);
        vpu.writeVramPlane(9, idx * 32 + 8,  (byte) attr);
        vpu.writeVramPlane(9, idx * 32 + 9,  (byte) palIdx);
        vpu.writeVramPlane(9, idx * 32 + 10, (byte) colId);
        vpu.writeVramPlane(9, idx * 32 + 11, (byte) priority);
        oamWord(idx, 12, xstep);
        oamWord(idx, 14, ystep);
    }

    /** Write a copper entry to the tables bank. */
    private void copEntry(int idx, int scanline, int reg, int valLo, int valHi, int flags) {
        int base = 0x1000 + idx * 8; // COP_OFS_DFLT = 0x1000
        vpu.writeVramPlane(9, base,     (byte)(scanline & 0xFF));
        vpu.writeVramPlane(9, base + 1, (byte)((scanline >> 8) & 0xFF));
        vpu.writeVramPlane(9, base + 2, (byte)(reg & 0xFF));
        vpu.writeVramPlane(9, base + 3, (byte)((reg >> 8) & 0xFF));
        vpu.writeVramPlane(9, base + 4, (byte)(valLo & 0xFF));
        vpu.writeVramPlane(9, base + 5, (byte)(valHi & 0xFF));
        vpu.writeVramPlane(9, base + 6, (byte)(flags & 0xFF));
        vpu.writeVramPlane(9, base + 7, (byte) 0);
    }

    /** Configure a group descriptor in the group table. */
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

    /** Update group scroll registers. */
    private void setGroupScroll(int gi, int scrollX, int scrollY) {
        int base = GROUP_BASE + gi * 0x10;
        mmio(base + 3, scrollX & 0xFF);
        mmio(base + 4, (scrollX >> 8) & 0xFF);
        mmio(base + 5, scrollY & 0xFF);
    }

    /** Write a character + attribute to the text layer at (row, col). */
    private void putChar(int row, int col, int ch, int attr) {
        int ofs = TEXT_BASE + (row * 80 + col) * 2;
        mmio(ofs,     ch & 0xFF);
        mmio(ofs + 1, attr & 0xFF);
    }

    /** Write a string to the text layer. */
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