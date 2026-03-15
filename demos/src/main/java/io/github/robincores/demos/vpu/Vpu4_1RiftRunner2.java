package io.github.robincores.r8.demos.vpu;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU_v4_1;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>RIFT RUNNER — Blitter + Double-Buffer Edition</h1>
 *
 * <p>A dual-layer side-scrolling shmup on VPU v4.1 with full blitter-driven rendering.</p>
 *
 * <h2>Double-buffer scheme</h2>
 * <p>Group 1 (near layer, planes 4–7) is double-buffered via {@code grpBplOfs}:
 * buffer A at offset 0x0000, buffer B at offset 0x2000.  Each frame the back buffer
 * is cleared and redrawn entirely with the blitter, then swapped into view after
 * the VPU finishes rendering the current frame — tear-free.</p>
 *
 * <h2>Blitter operations per frame</h2>
 * <ol>
 *   <li><b>RECTFILL</b> — clear near-layer back buffer (planes 4–7)</li>
 *   <li><b>RECTFILL ×4</b> — atmospheric terrain bands (sky, horizon, ground)</li>
 *   <li><b>RECTFILL ×~30</b> — procedural scrolling asteroid field</li>
 *   <li><b>RECTFILL</b> — boss HP bar (when active)</li>
 *   <li><b>RECTFILL</b> — rift cooldown meter</li>
 *   <li><b>RECTFILL</b> — weapon level indicator</li>
 *   <li><b>RECTFILL ×3</b> — rift flash wipe bars (during dimension shift)</li>
 *   <li><b>COPY+XOR</b> — rift distortion overlay on far layer (planes 0–1)</li>
 * </ol>
 *
 * <h2>Controls</h2>
 * <ul>
 *   <li><b>Arrows / WASD</b> — move</li>
 *   <li><b>Z / Space</b> — fire</li>
 *   <li><b>X / Shift</b> — RIFT SHIFT (flip between near &amp; far layer)</li>
 *   <li><b>Enter</b> — start / restart</li>
 * </ul>
 */
public class Vpu4_1RiftRunner2 extends Application {

    // =====================================================================
    //  Platform
    // =====================================================================

    private static final DisplayConfig CFG = new DisplayConfig(640, 400, 640, 400, 400, 449, 400);
    private static final int CPF = CFG.cyclesPerFrame();

    private static final int LW = 320, LH = 200, BPR = 40;

    // =====================================================================
    //  Banks
    // =====================================================================

    private static final int BANK_PLANE0   = 0x00;
    private static final int BANK_TABLES   = 0x08;
    private static final int BANK_SPRITES0 = 0x09;

    // =====================================================================
    //  MMIO
    // =====================================================================

    private static final int REG_CTRL    = 0x0000;
    private static final int REG_STATUS  = 0x0001;
    private static final int TX_CTRL     = 0x0006;
    private static final int TX_CMD      = 0x0030;
    private static final int SPR_CTRL    = 0x0014;
    private static final int COP_CTRL    = 0x0020;
    private static final int COP_LEN_L   = 0x0021, COP_LEN_H = 0x0022;
    private static final int COP_OFS_L   = 0x0023, COP_OFS_H = 0x0024;
    private static final int GROUP_BASE  = 0x0400;
    private static final int PAL_BASE    = 0x0100;
    private static final int TEXT_BASE   = 0x2000;
    private static final int OAM_BASE    = 0x1000;

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

    private static final int BC_START = 0x01;
    private static final int BC_DIR   = 0x08;
    private static final int BC_TRANS = 0x10;

    private static final int ROP_COPY     = 0;
    private static final int ROP_XOR      = 3;
    private static final int ROP_RECTFILL = 4;

    private static final int STATUS_BLT = 0x10;

    // =====================================================================
    //  Copper / Group / Sprite constants
    // =====================================================================

    private static final int COP_W16 = 0x01, COP_END = 0x80;
    private static final int TBL_COP_BASE = 0x0000;
    private static final int GF_LORES = 0x01, GF_WRAP_X = 0x02, GF_WRAP_Y = 0x04;
    private static final int SA_EN = 0x01;

    // =====================================================================
    //  Double-buffer layout (group 1, planes 4–7)
    //
    //  Each plane is 16 384 bytes.
    //  Buffer A: offsets 0x0000 – 0x1F3F  (200 rows × 40 bytes = 8000)
    //  Buffer B: offsets 0x2000 – 0x3F3F
    //  grpBplOfs selects which buffer the renderer reads.
    // =====================================================================

    private static final int BUF_A = 0x0000;
    private static final int BUF_B = 0x2000;

    // Far-layer hidden area (for XOR source pattern, stored in plane 0 above visible region)
    private static final int XOR_SRC_BASE = 0x2000;
    private static final int XOR_SRC_W    = BPR; // 40 bytes = full width
    private static final int XOR_SRC_H    = 8;   // 8 rows of pattern (repeated via blit)

    // =====================================================================
    //  Sine table
    // =====================================================================

    private static final int[] SIN = new int[256];
    static {
        for (int i = 0; i < 256; i++)
            SIN[i] = (int) Math.round(127.0 * Math.sin(i * Math.PI * 2.0 / 256.0));
    }
    private static int sin256(int i) { return SIN[i & 0xFF]; }

    // =====================================================================
    //  Sprite pattern offsets (16×16 4bpp = 0x80 each, in BANK_SPRITES0)
    // =====================================================================

    private static final int PAT_PLAYER     = 0x0000;
    private static final int PAT_PLAYER_THR = 0x0080;
    private static final int PAT_PBULLET    = 0x0100;
    private static final int PAT_ENEMY_A    = 0x0180;
    private static final int PAT_ENEMY_B    = 0x0200;
    private static final int PAT_ENEMY_C    = 0x0280;
    private static final int PAT_EBULLET    = 0x0300;
    private static final int PAT_EXPL0      = 0x0380;
    private static final int PAT_EXPL1      = 0x0400;
    private static final int PAT_EXPL2      = 0x0480;
    private static final int PAT_EXPL3      = 0x0500;
    private static final int PAT_POWERUP    = 0x0580;
    private static final int PAT_BOSS       = 0x0600;
    private static final int PAT_BOSS_ARM   = 0x0680;

    // =====================================================================
    //  OAM slot allocation
    // =====================================================================

    private static final int OAM_PLAYER       = 0;
    private static final int OAM_PBULLET_BASE = 1;
    private static final int MAX_PBULLETS     = 12;
    private static final int OAM_ENEMY_BASE   = 13;
    private static final int MAX_ENEMIES      = 24;
    private static final int OAM_EBULLET_BASE = 37;
    private static final int MAX_EBULLETS     = 16;
    private static final int OAM_EXPL_BASE    = 53;
    private static final int MAX_EXPLS        = 10;
    private static final int OAM_POWER_BASE   = 63;
    private static final int MAX_POWERS       = 4;
    private static final int OAM_BOSS_BASE    = 67;

    // =====================================================================
    //  Procedural asteroid field constants
    //  Virtual field is 512 px wide, wraps.  ~50 asteroids seeded by LCG.
    // =====================================================================

    private static final int FIELD_W     = 512;
    private static final int NUM_ROCKS   = 50;
    private final int[] rockX   = new int[NUM_ROCKS];
    private final int[] rockY   = new int[NUM_ROCKS];
    private final int[] rockW   = new int[NUM_ROCKS];
    private final int[] rockH   = new int[NUM_ROCKS];
    private final int[] rockCol = new int[NUM_ROCKS];
    private final int[] rockHiY = new int[NUM_ROCKS]; // highlight Y offset

    // =====================================================================
    //  Game state
    // =====================================================================

    private enum State { TITLE, PLAYING, GAME_OVER }

    private VPU_v4_1 vpu;
    private volatile int frame;
    private int frameCycles;
    private final Set<KeyCode> keys = ConcurrentHashMap.newKeySet();

    private State state = State.TITLE;
    private int stateTimer;

    // Double buffer
    private int backOfs  = BUF_B;  // where blitter draws
    private int frontOfs = BUF_A;  // what the VPU renders

    // Player
    private int px, py, playerLayer, lives, score;
    private int fireTimer, iframes, riftCooldown, riftFlashTimer, weaponLevel;
    private boolean playerAlive;

    // Player bullets
    private final int[] pbx = new int[MAX_PBULLETS], pby = new int[MAX_PBULLETS];
    private final boolean[] pbActive = new boolean[MAX_PBULLETS];
    private final int[] pbLayer = new int[MAX_PBULLETS];

    // Enemies
    private final int[] ex = new int[MAX_ENEMIES], ey = new int[MAX_ENEMIES];
    private final int[] eType = new int[MAX_ENEMIES], eHp = new int[MAX_ENEMIES];
    private final int[] eLayer = new int[MAX_ENEMIES], eTimer = new int[MAX_ENEMIES];
    private final boolean[] eActive = new boolean[MAX_ENEMIES];

    // Enemy bullets
    private final int[] ebx = new int[MAX_EBULLETS], eby = new int[MAX_EBULLETS];
    private final int[] ebdx = new int[MAX_EBULLETS], ebdy = new int[MAX_EBULLETS];
    private final int[] ebLayer = new int[MAX_EBULLETS];
    private final boolean[] ebActive = new boolean[MAX_EBULLETS];

    // Explosions
    private final int[] xpx = new int[MAX_EXPLS], xpy = new int[MAX_EXPLS];
    private final int[] xpTimer = new int[MAX_EXPLS];
    private final boolean[] xpActive = new boolean[MAX_EXPLS];

    // Powerups
    private final int[] pwx = new int[MAX_POWERS], pwy = new int[MAX_POWERS];
    private final boolean[] pwActive = new boolean[MAX_POWERS];

    // Wave / boss
    private int waveNum, waveTimer, spawnAccum, totalKills;
    private boolean bossActive;
    private int bossX, bossY, bossHp, bossMaxHp, bossTimer;

    // Scroll
    private int scrollFar, scrollNear;

    // =====================================================================
    //  JavaFX lifecycle
    // =====================================================================

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas();
        vpu = new VPU_v4_1(CFG, bit -> {}, 0, canvas);

        initVPU();
        showTitle();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, CFG.canvasWidth(), CFG.canvasHeight());
        scene.getRoot().setStyle("-fx-background-color: black;");
        scene.setOnKeyPressed(e -> { keys.add(e.getCode()); e.consume(); });
        scene.setOnKeyReleased(e -> { keys.remove(e.getCode()); e.consume(); });

        stage.setTitle("RIFT RUNNER \u2014 Blitter + Double-Buffer Edition");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
        canvas.requestFocus();

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
        }, "rift-emu");
        emu.setDaemon(true);
        emu.start();

        new AnimationTimer() {
            @Override public void handle(long now) { vpu.fxPulse(); }
        }.start();

        stage.setOnCloseRequest(e -> { emu.interrupt(); Platform.exit(); });
    }

    public static void main(String[] args) { launch(args); }

    // =====================================================================
    //  FRAME RUNNER — tick-budget with blitter + double-buffer swap
    //
    //  Sequence per frame:
    //    1. Game logic + MMIO updates (no ticks needed)
    //    2. Blitter ops draw to back buffer (ticked to completion)
    //    3. Tick remaining cycles (VPU renders from FRONT buffer)
    //    4. Swap buffers (takes effect next frame — tear-free)
    // =====================================================================

    private void runOneFrame() {
        frameCycles = 0;

        // 1. Game logic (updates state, OAM, copper, text — no VPU ticks)
        gameLoop();

        // 2. Blitter-driven rendering to back buffer
        renderFrame();

        // 3. Tick remaining cycles — VPU renders the frame from frontOfs
        int remaining = CPF - frameCycles;
        if (remaining > 0) tickBudget(remaining);

        // 4. Swap: back buffer becomes front (displayed next frame)
        swapBuffers();
    }

    private void swapBuffers() {
        int tmp = backOfs;
        backOfs = frontOfs;
        frontOfs = tmp;
        setGroupBplOfs(1, frontOfs);
    }

    // =====================================================================
    //  BLITTER RENDERING — draws the near layer each frame
    // =====================================================================

    private void renderFrame() {
        // ── Near layer (group 1, planes 4-7): fully redrawn via blitter ──

        // 1. Clear entire back buffer to black
        bltRectFill(backOfs, BPR, 0, 0, LW, LH, 0x00, 0xF0);

        // 2. Atmospheric terrain bands (vary by layer and boss state)
        drawTerrainBands();

        // 3. Procedural scrolling asteroid field
        drawAsteroidField();

        // 4. HUD bars drawn directly into the bitplane layer
        if (state == State.PLAYING) drawHUDBars();

        // 5. Rift flash wipe effect (during dimension shift)
        if (riftFlashTimer > 0) drawRiftFlash();

        // ── Far layer (group 0, planes 0-1): XOR rift distortion ──
        if (riftFlashTimer > 6) drawRiftXorDistortion();
    }

    /** Four terrain bands with colours that shift based on layer and boss state. */
    private void drawTerrainBands() {
        boolean nearLayer = (state == State.PLAYING) && playerLayer == 1;
        boolean bossMode  = bossActive;
        int f = frame;

        if (nearLayer) {
            // Near: warm orange-brown terrain
            int sky   = bossMode ? ((3 + ((f >> 2) & 1)) & 0x0F) : 2;  // flicker during boss
            int horiz = bossMode ? ((5 + ((f >> 3) & 1)) & 0x0F) : 4;
            int mid   = 6;
            int ground= 8;

            bltRectFill(backOfs, BPR, 0,   0,   LW, 100, (sky    << 4), 0xF0);
            bltRectFill(backOfs, BPR, 0, 100,   LW,  30, (horiz  << 4), 0xF0);
            bltRectFill(backOfs, BPR, 0, 130,   LW,  30, (mid    << 4), 0xF0);
            bltRectFill(backOfs, BPR, 0, 160,   LW,  40, (ground << 4), 0xF0);
        } else {
            // Far: cool blue-purple space dust
            int sky   = bossMode ? ((1 + ((f >> 2) & 1)) & 0x0F) : 1;
            int mid   = 3;
            int ground= 5;

            bltRectFill(backOfs, BPR, 0,   0,   LW, 120, (sky    << 4), 0xF0);
            bltRectFill(backOfs, BPR, 0, 120,   LW,  40, (mid    << 4), 0xF0);
            bltRectFill(backOfs, BPR, 0, 160,   LW,  40, (ground << 4), 0xF0);
        }
    }

    /**
     * Draw procedural asteroid rectangles.  Positions are deterministic
     * from the seed table and scroll offset, so they look consistent
     * frame-to-frame while scrolling smoothly.  Each rock = body rect +
     * 1-pixel highlight strip = 2 RECTFILL calls.
     */
    private void drawAsteroidField() {
        int scroll = scrollNear;
        for (int i = 0; i < NUM_ROCKS; i++) {
            // Screen-space X from virtual field X minus scroll (wraps at FIELD_W)
            int sx = ((rockX[i] - (scroll & (FIELD_W - 1))) + FIELD_W) % FIELD_W;
            if (sx >= LW) continue; // off screen right

            int rw = rockW[i], rh = rockH[i];
            int sy = rockY[i];

            // Clamp to screen edges
            int x0 = Math.max(0, sx);
            int y0 = Math.max(0, sy);
            int x1 = Math.min(LW, sx + rw);
            int y1 = Math.min(LH, sy + rh);
            if (x0 >= x1 || y0 >= y1) continue;

            // Body
            int col = rockCol[i] & 0x0F;
            bltRectFill(backOfs, BPR, x0, y0, x1 - x0, y1 - y0, (col << 4), 0xF0);

            // Highlight strip (1 pixel lighter, top edge)
            int hiCol = Math.min(15, col + 2);
            int hiy = y0 + rockHiY[i];
            if (hiy >= y0 && hiy < y1) {
                bltRectFill(backOfs, BPR, x0, hiy, x1 - x0, 1, (hiCol << 4), 0xF0);
            }
        }
    }

    /** HUD bars: boss HP, rift cooldown, weapon level — drawn as coloured rects. */
    private void drawHUDBars() {
        int barY = 190; // near bottom of screen

        // Boss HP bar (red, shrinks as HP drops)
        if (bossActive && bossMaxHp > 0) {
            int barW = Math.max(0, bossHp * 120 / bossMaxHp);
            if (barW > 0) {
                // Background (dark) — full length
                bltRectFill(backOfs, BPR, 8, barY, 120, 4, (8 << 4), 0xF0);
                // Foreground (bright red)
                int col = 12; // palette 28 = bright red-ish; or use a neon color
                bltRectFill(backOfs, BPR, 8, barY, barW, 4, (col << 4), 0xF0);
            }
        }

        // Rift cooldown meter (blue-cyan, refills over time)
        if (riftCooldown > 0) {
            int barW = riftCooldown * 40 / 20; // max cooldown = 20 frames
            if (barW > 0) {
                bltRectFill(backOfs, BPR, 200, barY, barW, 3, (7 << 4), 0xF0);
            }
        }

        // Weapon level indicator (3 small squares, green tones)
        for (int i = 0; i < 3; i++) {
            int col = (i < weaponLevel + 1) ? 10 : 1; // bright vs dim
            bltRectFill(backOfs, BPR, 270 + i * 12, barY, 8, 4, (col << 4), 0xF0);
        }
    }

    /** Rift flash: bright horizontal bars sweep across the near layer. */
    private void drawRiftFlash() {
        int intensity = riftFlashTimer; // 12 → 0
        int barCount = Math.min(intensity, 6);

        for (int b = 0; b < barCount; b++) {
            int y = (b * LH / barCount + frame * 7) % LH;
            int h = 2 + (intensity / 4);
            if (y + h > LH) h = LH - y;
            int col = (intensity > 8) ? 15 : (intensity > 4) ? 13 : 9; // bright → dim
            bltRectFill(backOfs, BPR, 0, y, LW, h, (col << 4), 0xF0);
        }
    }

    /**
     * COPY+XOR: brief visual distortion on the far layer (planes 0-1)
     * during the rift shift.  XORs a stripe pattern from hidden VRAM
     * onto visible plane 0.  Self-inverting: un-XOR on the next frame.
     */
    private void drawRiftXorDistortion() {
        // XOR the 8-row pattern across the top half of visible plane 0.
        // Source: XOR_SRC_BASE in plane 0 (hidden area), 40 bytes × 8 rows.
        // Dest: visible plane 0 at row 0, repeated by setting srcPitchDelta
        // to rewind after each 8-row stripe.
        int stripeH = XOR_SRC_H;
        int rows = 80; // distort top 80 rows
        int dstOfs = 0; // visible area row 0

        bltCopyBytes(XOR_SRC_BASE, dstOfs,
                BPR, rows,
                /*srcPitchDelta*/ -(BPR * (stripeH - 1)), // rewind src every stripeH rows
                /*dstPitchDelta*/ 0,
                0x01, ROP_XOR, 0,
                0xFF, 0xFF,
                false, false);
    }

    // =====================================================================
    //  Game loop (runs every frame, no VPU ticks)
    // =====================================================================

    private void gameLoop() {
        switch (state) {
            case TITLE -> {
                updateTitle();
                updateCopper();
                pushAllSprites();
            }
            case PLAYING -> {
                updatePlayer();
                updatePlayerBullets();
                updateEnemies();
                updateEnemyBullets();
                updateExplosions();
                updatePowerups();
                updateBoss();
                updateWaveSpawner();
                checkCollisions();
                updateScroll();
                updateCopper();
                pushAllSprites();
                updateHUD();
            }
            case GAME_OVER -> {
                updateGameOver();
                updateCopper();
                pushAllSprites();
            }
        }
    }

    // =====================================================================
    //  Input
    // =====================================================================

    private boolean keyHeld(KeyCode... codes) {
        for (KeyCode c : codes) if (keys.contains(c)) return true;
        return false;
    }

    // =====================================================================
    //  Title / Game Over
    // =====================================================================

    private void showTitle() {
        state = State.TITLE;
        stateTimer = 0;
        clearAllEntities();
        clearText();
        putStr(3,  10, "\u00db\u00db\u00db RIFT RUNNER \u00db\u00db\u00db", 0x0F);
        putStr(5,  14, "DUAL-LAYER SHMUP", 0x0B);
        putStr(8,  12, "ARROWS / WASD  MOVE", 0x07);
        putStr(9,  12, "Z / SPACE      FIRE", 0x07);
        putStr(10, 12, "X / SHIFT     RIFT SHIFT", 0x07);
        putStr(12, 12, "ENTER         START", 0x0E);
        putStr(16, 6,  "SHIFT BETWEEN LAYERS TO FIGHT", 0x0A);
        putStr(17, 6,  "ENEMIES ON YOUR LAYER.", 0x0A);
        putStr(19, 6,  "GHOSTED ENEMIES CAN'T HURT YOU!", 0x02);
        putStr(22, 8,  "BLITTER-DRIVEN \u00b7 DOUBLE-BUFFERED \u00b7 TEAR-FREE", 0x08);
        putStr(23, 8,  "RECTFILL \u00b7 COPY+XOR \u00b7 8 BITPLANES \u00b7 COPPER", 0x08);
    }

    private void updateTitle() {
        stateTimer++;
        scrollFar  += 1;
        scrollNear += 2;
        setGroupScroll(0, scrollFar, 0);
        if (keyHeld(KeyCode.ENTER)) startGame();
    }

    private void startGame() {
        state = State.PLAYING;
        stateTimer = 0;
        px = 40; py = 100;
        playerLayer = 1;
        lives = 3; score = 0;
        fireTimer = 0; iframes = 90;
        riftCooldown = 0; riftFlashTimer = 0;
        weaponLevel = 0; playerAlive = true;
        waveNum = 1; waveTimer = 0; spawnAccum = 0; totalKills = 0;
        bossActive = false;
        clearAllEntities();
        clearText();
        updateHUD();
    }

    private void showGameOver() {
        state = State.GAME_OVER;
        stateTimer = 0;
        putStr(10, 30, "GAME OVER", 0x0C);
        putStr(12, 24, String.format("SCORE: %07d", score), 0x0E);
        putStr(13, 24, String.format("WAVE:  %d", waveNum), 0x0E);
        putStr(16, 24, "PRESS ENTER TO RETRY", 0x07);
    }

    private void updateGameOver() {
        stateTimer++;
        scrollFar  += 1;
        scrollNear += 1;
        setGroupScroll(0, scrollFar, 0);
        if (stateTimer > 60 && keyHeld(KeyCode.ENTER)) showTitle();
    }

    // =====================================================================
    //  Player
    // =====================================================================

    private void updatePlayer() {
        if (!playerAlive) return;

        int spd = 3;
        if (keyHeld(KeyCode.LEFT, KeyCode.A))  px -= spd;
        if (keyHeld(KeyCode.RIGHT, KeyCode.D)) px += spd;
        if (keyHeld(KeyCode.UP, KeyCode.W))    py -= spd;
        if (keyHeld(KeyCode.DOWN, KeyCode.S))  py += spd;
        px = clamp(px, 4, LW - 20);
        py = clamp(py, 4, LH - 20);

        if (fireTimer > 0) fireTimer--;
        if (keyHeld(KeyCode.Z, KeyCode.SPACE) && fireTimer == 0) {
            fireBullet(px + 14, py + 6);
            if (weaponLevel >= 1) { fireBullet(px + 12, py + 2); fireBullet(px + 12, py + 10); }
            if (weaponLevel >= 2) { fireBullet(px + 10, py - 2); fireBullet(px + 10, py + 14); }
            fireTimer = (weaponLevel >= 2) ? 4 : (weaponLevel >= 1) ? 5 : 6;
        }

        if (riftCooldown > 0) riftCooldown--;
        if (keyHeld(KeyCode.X, KeyCode.SHIFT) && riftCooldown == 0) {
            playerLayer ^= 1;
            riftCooldown = 20;
            riftFlashTimer = 12;
            for (int i = 0; i < MAX_PBULLETS; i++)
                if (pbActive[i]) pbLayer[i] = playerLayer;
        }

        if (iframes > 0) iframes--;
        if (riftFlashTimer > 0) riftFlashTimer--;
    }

    private void fireBullet(int bx, int by) {
        for (int i = 0; i < MAX_PBULLETS; i++) {
            if (!pbActive[i]) {
                pbx[i] = bx; pby[i] = by;
                pbActive[i] = true; pbLayer[i] = playerLayer;
                return;
            }
        }
    }

    private void updatePlayerBullets() {
        for (int i = 0; i < MAX_PBULLETS; i++) {
            if (!pbActive[i]) continue;
            pbx[i] += 6;
            if (pbx[i] > LW + 8) pbActive[i] = false;
        }
    }

    // =====================================================================
    //  Enemies
    // =====================================================================

    private void spawnEnemy(int type, int layer, int startX, int startY) {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!eActive[i]) {
                eActive[i] = true; eType[i] = type; eLayer[i] = layer;
                ex[i] = startX; ey[i] = startY; eTimer[i] = 0;
                eHp[i] = (type == 0) ? 1 : (type == 1) ? 3 : 2;
                return;
            }
        }
    }

    private void updateEnemies() {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!eActive[i]) continue;
            eTimer[i]++;
            switch (eType[i]) {
                case 0 -> { ex[i] -= 2; ey[i] += sin256(eTimer[i] * 4) / 64; }
                case 1 -> { ex[i] -= 1; if (eTimer[i] % 50 == 30) fireEnemyBullet(ex[i], ey[i] + 6, eLayer[i]); }
                case 2 -> { ex[i] -= 3; ey[i] += sin256(eTimer[i] * 6) / 32; }
            }
            ey[i] = clamp(ey[i], 2, LH - 18);
            if (ex[i] < -20) eActive[i] = false;
        }
    }

    private void fireEnemyBullet(int bx, int by, int layer) {
        for (int i = 0; i < MAX_EBULLETS; i++) {
            if (!ebActive[i]) {
                ebx[i] = bx; eby[i] = by;
                int dx = px - bx, dy = py - by;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1) len = 1;
                ebdx[i] = Math.round(dx * 3 / len);
                ebdy[i] = Math.round(dy * 3 / len);
                if (ebdx[i] == 0 && ebdy[i] == 0) ebdx[i] = -3;
                ebLayer[i] = layer; ebActive[i] = true;
                return;
            }
        }
    }

    private void updateEnemyBullets() {
        for (int i = 0; i < MAX_EBULLETS; i++) {
            if (!ebActive[i]) continue;
            ebx[i] += ebdx[i]; eby[i] += ebdy[i];
            if (ebx[i] < -8 || ebx[i] > LW + 8 || eby[i] < -8 || eby[i] > LH + 8)
                ebActive[i] = false;
        }
    }

    // =====================================================================
    //  Explosions / Powerups
    // =====================================================================

    private void spawnExplosion(int x, int y) {
        for (int i = 0; i < MAX_EXPLS; i++) {
            if (!xpActive[i]) {
                xpx[i] = x - 4; xpy[i] = y - 4;
                xpTimer[i] = 0; xpActive[i] = true;
                return;
            }
        }
    }

    private void updateExplosions() {
        for (int i = 0; i < MAX_EXPLS; i++) {
            if (!xpActive[i]) continue;
            xpTimer[i]++;
            if (xpTimer[i] > 20) xpActive[i] = false;
        }
    }

    private void spawnPowerup(int x, int y) {
        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) {
                pwx[i] = x; pwy[i] = y; pwActive[i] = true;
                return;
            }
        }
    }

    private void updatePowerups() {
        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) continue;
            pwx[i] -= 1;
            pwy[i] += sin256(frame * 3 + i * 64) / 128;
            if (pwx[i] < -16) pwActive[i] = false;
        }
    }

    // =====================================================================
    //  Boss
    // =====================================================================

    private void spawnBoss() {
        bossActive = true;
        bossX = LW + 20; bossY = LH / 2 - 16;
        bossHp = 30 + waveNum * 10; bossMaxHp = bossHp;
        bossTimer = 0;
    }

    private void updateBoss() {
        if (!bossActive) return;
        bossTimer++;
        if (bossX > LW - 50) { bossX -= 1; return; }
        bossY = LH / 2 - 16 + sin256(bossTimer * 2) * 40 / 127;
        bossY = clamp(bossY, 4, LH - 36);
        if (bossTimer % 25 == 0) for (int d = -2; d <= 2; d++) fireEnemyBullet(bossX, bossY + 8, 1);
        if (bossTimer % 40 == 20) fireEnemyBullet(bossX, bossY + 8, 0);
    }

    // =====================================================================
    //  Wave spawner
    // =====================================================================

    private void updateWaveSpawner() {
        if (bossActive) return;
        waveTimer++;
        int spawnRate = Math.max(15, 50 - waveNum * 5);
        if (waveTimer % spawnRate == 0) {
            int type = (waveTimer / spawnRate) % 3;
            int layer = ((spawnAccum & 1) == 0) ? 1 : 0;
            int sy = 20 + (int)(lcg(frame + spawnAccum) & 0x7F) % 160;
            spawnEnemy(type, layer, LW + 8, sy);
            spawnAccum++;
        }
        if (waveTimer % 200 == 100) {
            int burstLayer = waveNum & 1;
            for (int b = 0; b < 3 + waveNum; b++)
                spawnEnemy(2, burstLayer, LW + 8 + b * 15, 30 + b * 20);
        }
        if (totalKills > 0 && totalKills % (15 + waveNum * 5) == 0 && !bossActive) spawnBoss();
        if (waveTimer > 700 + waveNum * 100) { waveNum++; waveTimer = 0; }
    }

    // =====================================================================
    //  Collisions
    // =====================================================================

    private void checkCollisions() {
        if (!playerAlive) return;

        for (int bi = 0; bi < MAX_PBULLETS; bi++) {
            if (!pbActive[bi]) continue;
            for (int ei = 0; ei < MAX_ENEMIES; ei++) {
                if (!eActive[ei] || pbLayer[bi] != eLayer[ei]) continue;
                if (boxHit(pbx[bi], pby[bi], 4, 4, ex[ei], ey[ei], 14, 14)) {
                    pbActive[bi] = false;
                    if (--eHp[ei] <= 0) {
                        eActive[ei] = false;
                        spawnExplosion(ex[ei] + 4, ey[ei] + 4);
                        score += (eType[ei] + 1) * 100;
                        totalKills++;
                        if ((lcg(frame + ei) & 0x0F) == 0) spawnPowerup(ex[ei], ey[ei]);
                    }
                    break;
                }
            }
        }

        if (bossActive) {
            for (int bi = 0; bi < MAX_PBULLETS; bi++) {
                if (!pbActive[bi]) continue;
                if (boxHit(pbx[bi], pby[bi], 4, 4, bossX, bossY, 28, 28)) {
                    pbActive[bi] = false;
                    if (--bossHp <= 0) {
                        bossActive = false;
                        spawnExplosion(bossX + 8, bossY + 8);
                        spawnExplosion(bossX + 16, bossY);
                        spawnExplosion(bossX, bossY + 16);
                        score += 5000; waveNum++; waveTimer = 0;
                    }
                }
            }
        }

        if (iframes == 0) {
            for (int ei = 0; ei < MAX_ENEMIES; ei++) {
                if (!eActive[ei] || eLayer[ei] != playerLayer) continue;
                if (boxHit(px, py, 12, 12, ex[ei], ey[ei], 14, 14)) {
                    hitPlayer(); eActive[ei] = false; spawnExplosion(ex[ei], ey[ei]);
                    break;
                }
            }
        }
        if (iframes == 0) {
            for (int bi = 0; bi < MAX_EBULLETS; bi++) {
                if (!ebActive[bi] || ebLayer[bi] != playerLayer) continue;
                if (boxHit(px + 2, py + 2, 10, 10, ebx[bi], eby[bi], 4, 4)) {
                    hitPlayer(); ebActive[bi] = false; break;
                }
            }
        }
        if (bossActive && iframes == 0 && boxHit(px, py, 12, 12, bossX, bossY, 28, 28))
            hitPlayer();

        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) continue;
            if (boxHit(px, py, 14, 14, pwx[i], pwy[i], 10, 10)) {
                pwActive[i] = false;
                if (weaponLevel < 2) weaponLevel++; else score += 500;
            }
        }
    }

    private void hitPlayer() {
        lives--;
        iframes = 90;
        weaponLevel = Math.max(0, weaponLevel - 1);
        spawnExplosion(px + 4, py + 4);
        if (lives <= 0) { playerAlive = false; showGameOver(); }
    }

    private static boolean boxHit(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    // =====================================================================
    //  Scroll
    // =====================================================================

    private void updateScroll() {
        scrollFar  += 1;
        scrollNear += 2;
        setGroupScroll(0, scrollFar, 0);
        // Group 1 scroll handled by blitter redraw — no scroll register needed
    }

    // =====================================================================
    //  Copper
    // =====================================================================

    private void updateCopper() {
        int idx = 0;
        boolean onNear = (state == State.PLAYING) && playerLayer == 1;
        for (int ly = 0; ly < LH; ly++) {
            int scan = ly * 2;
            int rgb = skyGrad(ly, onNear);
            if (riftFlashTimer > 0) {
                int flash = riftFlashTimer * 20;
                rgb = blendRgb(rgb, 0xFFFFFF, Math.min(255, flash));
            }
            int v = toRgb565(rgb);
            copEntry(idx++, scan, PAL_BASE, v & 0xFF, (v >> 8) & 0xFF, COP_W16);
        }
        copEntry(idx++, 0, 0, 0, 0, COP_END);
        int bytes = idx * 8;
        mmio(COP_LEN_L, bytes & 0xFF);
        mmio(COP_LEN_H, (bytes >> 8) & 0xFF);
    }

    private int skyGrad(int ly, boolean nearLayer) {
        float t = ly / 200f;
        if (nearLayer) {
            int top = 0x0A0828, mid = 0x401838, bot = 0x100808;
            return t < 0.5f ? lerpRgb(top, mid, t * 2) : lerpRgb(mid, bot, (t - 0.5f) * 2);
        } else {
            int top = 0x000818, mid = 0x082048, bot = 0x040410;
            return t < 0.5f ? lerpRgb(top, mid, t * 2) : lerpRgb(mid, bot, (t - 0.5f) * 2);
        }
    }

    // =====================================================================
    //  Sprites → OAM
    // =====================================================================

    private void pushAllSprites() {
        for (int i = 0; i < 128; i++) disableSprite(i);
        if (state != State.PLAYING) return;

        if (playerAlive && (iframes == 0 || (frame & 2) != 0)) {
            boolean thrust = keyHeld(KeyCode.RIGHT, KeyCode.D);
            setSpriteOAM(OAM_PLAYER, px, py, 16, 16,
                    thrust ? PAT_PLAYER_THR : PAT_PLAYER, SA_EN, 2, playerLayer + 1, 0x0100, 0x0100);
        }

        for (int i = 0; i < MAX_PBULLETS; i++)
            if (pbActive[i])
                setSpriteOAM(OAM_PBULLET_BASE + i, pbx[i], pby[i], 16, 16,
                        PAT_PBULLET, SA_EN, 3, pbLayer[i] + 1, 0x0100, 0x0100);

        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!eActive[i]) continue;
            int pat = switch (eType[i]) { case 1 -> PAT_ENEMY_B; case 2 -> PAT_ENEMY_C; default -> PAT_ENEMY_A; };
            boolean ghosted = eLayer[i] != playerLayer;
            setSpriteOAM(OAM_ENEMY_BASE + i, ex[i], ey[i], 16, 16, pat,
                    SA_EN, ghosted ? 5 : 4, eLayer[i] + 1, ghosted ? 0x0140 : 0x0100, ghosted ? 0x0140 : 0x0100);
        }

        for (int i = 0; i < MAX_EBULLETS; i++)
            if (ebActive[i])
                setSpriteOAM(OAM_EBULLET_BASE + i, ebx[i], eby[i], 16, 16,
                        PAT_EBULLET, SA_EN, ebLayer[i] != playerLayer ? 5 : 6, ebLayer[i] + 1, 0x0100, 0x0100);

        for (int i = 0; i < MAX_EXPLS; i++) {
            if (!xpActive[i]) continue;
            int fr = Math.min(3, xpTimer[i] / 5);
            int scale = Math.max(0x40, 0x0100 - xpTimer[i] * 6);
            setSpriteOAM(OAM_EXPL_BASE + i, xpx[i], xpy[i], 16, 16,
                    PAT_EXPL0 + fr * 0x80, SA_EN, 7, 2, scale, scale);
        }

        for (int i = 0; i < MAX_POWERS; i++)
            if (pwActive[i]) {
                int pulse = 0x0100 + sin256(frame * 4 + i * 64) * 0x30 / 127;
                setSpriteOAM(OAM_POWER_BASE + i, pwx[i], pwy[i], 16, 16,
                        PAT_POWERUP, SA_EN, 8, 2, pulse, pulse);
            }

        if (bossActive) {
            int pulse = 0x0080 + sin256(bossTimer * 2) * 0x10 / 127;
            setSpriteOAM(OAM_BOSS_BASE, bossX, bossY, 16, 16, PAT_BOSS, SA_EN, 9, 2, pulse, pulse);
            for (int a = 0; a < 2; a++) {
                int armY = bossY + (a == 0 ? -10 : 22) + sin256(bossTimer * 3 + a * 128) * 4 / 127;
                setSpriteOAM(OAM_BOSS_BASE + 1 + a, bossX + 4, armY, 16, 16,
                        PAT_BOSS_ARM, SA_EN, 9, 2, 0x00C0, 0x00C0);
            }
        }
    }

    // =====================================================================
    //  HUD (text overlay)
    // =====================================================================

    private void updateHUD() {
        putStr(0, 1, String.format("SCORE:%07d", score), 0x0F);
        putStr(0, 22, String.format("LIVES:%d", lives), 0x0C);
        putStr(0, 35, String.format("WAVE:%d", waveNum), 0x0E);

        String layerStr = playerLayer == 1 ? " NEAR " : "  FAR ";
        int layerCol = playerLayer == 1 ? 0x0E : 0x09;
        putStr(0, 60, "LAYER:", 0x07);
        putStr(0, 66, layerStr, layerCol);

        if (bossActive) {
            int barLen = 20;
            int filled = bossMaxHp > 0 ? bossHp * barLen / bossMaxHp : 0;
            putStr(1, 1, "BOSS:", 0x0C);
            for (int i = 0; i < barLen; i++)
                putChar(1, 6 + i, i < filled ? 0xDB : 0xB0, i < filled ? 0x0C : 0x08);
        } else {
            for (int i = 0; i < 30; i++) putChar(1, i, ' ', 0x00);
        }

        putStr(1, 60, "WEAPON:", 0x07);
        String wepStr = switch (weaponLevel) { case 1 -> "DOUBLE"; case 2 -> "TRIPLE"; default -> "SINGLE"; };
        int wepCol = switch (weaponLevel) { case 1 -> 0x0A; case 2 -> 0x0D; default -> 0x07; };
        putStr(1, 67, wepStr + " ", wepCol);
    }

    // =====================================================================
    //  VPU init
    // =====================================================================

    private void initVPU() {
        setupPalette();
        drawStarfield();
        seedAsteroidTable();
        seedXorPattern();
        drawSpritePatterns();
        configureGroups();

        // Text overlay transparent
        mmio(TX_CTRL, 0x01 | 0x04);
        mmio(TX_CMD, 0x04);

        // Copper at tables bank offset 0
        mmio(COP_OFS_L, 0);
        mmio(COP_OFS_H, 0);
        buildInitCopper();

        // Enable
        mmio(REG_CTRL, 0x01);
        mmio(COP_CTRL, 0x01);
        mmio(SPR_CTRL, 0x01);
    }

    // =====================================================================
    //  Palette
    // =====================================================================

    private void setupPalette() {
        setPal(0, 0x000000);

        // Group 0 (far): blue-violet starfield
        setPal(1,  0x404080); setPal(2,  0x6060B0); setPal(3,  0x8080E0);
        setPal(4,  0xA0A0FF); setPal(5,  0x303060); setPal(6,  0x202040);
        setPal(7,  0x504880); setPal(8,  0x6858A0); setPal(9,  0x181830);
        setPal(10, 0x282850); setPal(11, 0xC0B0FF); setPal(12, 0xE0D0FF);
        setPal(13, 0x383068); setPal(14, 0x100818); setPal(15, 0x584888);

        // Group 1 (near): warm earth tones for asteroids / terrain
        setPal(16, 0x000000);
        setPal(17, 0x604830); setPal(18, 0x806040); setPal(19, 0xA08050);
        setPal(20, 0x483020); setPal(21, 0x705838); setPal(22, 0x907048);
        setPal(23, 0xB89060); setPal(24, 0x382010); setPal(25, 0x584028);
        setPal(26, 0xC0A870); setPal(27, 0xD8C088); setPal(28, 0xFF4040); // boss red
        setPal(29, 0x786048); setPal(30, 0x988068); setPal(31, 0xE0D0A0);

        // Sprite palettes (32..155) — same as original
        // Player (pal idx 2)
        setPal(32, 0x000000); setPal(33, 0x2040FF); setPal(34, 0x4070FF);
        setPal(35, 0x60A0FF); setPal(36, 0x80D0FF); setPal(37, 0xA0E0FF);
        setPal(38, 0xFFFFFF); setPal(39, 0x1020A0); setPal(40, 0xFF8020);
        setPal(41, 0xFFD060); setPal(42, 0x103080); setPal(43, 0x205090);
        setPal(44, 0x6090C0); setPal(45, 0xC0E0FF); setPal(46, 0xFF4020);
        setPal(47, 0x182050);

        // Bullet (pal idx 3)
        setPal(48, 0x000000); setPal(49, 0xFFFF00); setPal(50, 0xFFFF80);
        setPal(51, 0xFFFFFF); setPal(52, 0xFFD000); setPal(53, 0xFF8000);
        setPal(54, 0xFFE040); setPal(55, 0x804000); setPal(56, 0xFFA020);
        setPal(57, 0xFFE060); setPal(58, 0xC0A000); setPal(59, 0xE0C020);
        setPal(60, 0x604000); setPal(61, 0xA08000); setPal(62, 0xFFFF40);
        setPal(63, 0x302000);

        // Enemy (pal idx 4)
        setPal(64, 0x000000); setPal(65, 0xFF2020); setPal(66, 0xFF6040);
        setPal(67, 0xFF8060); setPal(68, 0xFFA080); setPal(69, 0xC01010);
        setPal(70, 0x800808); setPal(71, 0x400404); setPal(72, 0xFFB090);
        setPal(73, 0xE04030); setPal(74, 0xB02020); setPal(75, 0xD03828);
        setPal(76, 0x601010); setPal(77, 0xA01818); setPal(78, 0xFFC0A0);
        setPal(79, 0x200404);

        // Ghosted (pal idx 5)
        setPal(80, 0x000000); setPal(81, 0x401010); setPal(82, 0x501818);
        setPal(83, 0x602020); setPal(84, 0x682828); setPal(85, 0x380808);
        setPal(86, 0x280404); setPal(87, 0x180202); setPal(88, 0x703030);
        setPal(89, 0x481414); setPal(90, 0x380C0C); setPal(91, 0x401010);
        setPal(92, 0x200808); setPal(93, 0x300C0C); setPal(94, 0x783838);
        setPal(95, 0x100202);

        // Enemy bullet (pal idx 6)
        setPal(96, 0x000000);  setPal(97, 0xFF4040); setPal(98, 0xFF8080);
        setPal(99, 0xFFB0B0); setPal(100,0xFFFFFF); setPal(101,0xC02020);
        setPal(102,0x801010); setPal(103,0xFF6060); setPal(104,0xE03030);

        // Explosion (pal idx 7)
        setPal(112,0x000000); setPal(113,0xFFFF00); setPal(114,0xFFD000);
        setPal(115,0xFF8000); setPal(116,0xFF4000); setPal(117,0xFF0000);
        setPal(118,0xFFFFFF); setPal(119,0xFFA040); setPal(120,0xC06020);
        setPal(121,0x804010); setPal(122,0x402008); setPal(123,0xFF6000);

        // Powerup (pal idx 8)
        setPal(128,0x000000); setPal(129,0x00FF00); setPal(130,0x40FF40);
        setPal(131,0x80FF80); setPal(132,0xC0FFC0); setPal(133,0xFFFFFF);
        setPal(134,0x008800); setPal(135,0x00CC00); setPal(136,0x20DD20);

        // Boss (pal idx 9)
        setPal(144,0x000000); setPal(145,0x800080); setPal(146,0xA020A0);
        setPal(147,0xC040C0); setPal(148,0xE060E0); setPal(149,0xFF80FF);
        setPal(150,0xFFB0FF); setPal(151,0xFFFFFF); setPal(152,0x600060);
        setPal(153,0x400040); setPal(154,0xD050D0); setPal(155,0xB030B0);
    }

    // =====================================================================
    //  Static far-layer starfield (planes 0–3, single-buffered)
    // =====================================================================

    private void drawStarfield() {
        long seed = 12345;
        for (int i = 0; i < 400; i++) {
            seed = lcg(seed);
            int sx = (int)((seed >>> 16) & 0x1FF) % LW;
            seed = lcg(seed);
            int sy = (int)((seed >>> 16) & 0xFF) % LH;
            seed = lcg(seed);
            int col = 1 + (int)((seed >>> 8) & 0x03);
            setPixelGroup(0, 3, sx, sy, col);
            if (col >= 3 && sx + 1 < LW) setPixelGroup(0, 3, sx + 1, sy, col - 1);
        }

        // Nebula band
        for (int y = 60; y < 120; y++) {
            for (int x = 0; x < LW; x++) {
                double val = Math.sin(x * 0.03 + y * 0.08) * Math.cos(y * 0.05 - x * 0.02);
                if (val > 0.6) setPixelGroup(0, 3, x, y, 7);
                else if (val > 0.3) setPixelGroup(0, 3, x, y, 5);
            }
        }
    }

    // =====================================================================
    //  Procedural asteroid table (positions/sizes seeded once, scrolled dynamically)
    // =====================================================================

    private void seedAsteroidTable() {
        long seed = 99999;
        for (int i = 0; i < NUM_ROCKS; i++) {
            seed = lcg(seed);
            rockX[i] = (int)((seed >>> 8) & 0x1FF) % FIELD_W;
            seed = lcg(seed);
            rockY[i] = 20 + (int)((seed >>> 8) & 0xFF) % (LH - 40);
            seed = lcg(seed);
            rockW[i] = 6 + (int)(seed & 0x0F);
            seed = lcg(seed);
            rockH[i] = 6 + (int)(seed & 0x0F);
            seed = lcg(seed);
            rockCol[i] = 1 + (int)(seed & 0x07); // group 1 palette index 1-8
            rockHiY[i] = 0; // highlight at top
        }
    }

    // =====================================================================
    //  XOR distortion pattern (hidden area in plane 0 at XOR_SRC_BASE)
    // =====================================================================

    private void seedXorPattern() {
        for (int y = 0; y < XOR_SRC_H; y++) {
            int ofs = XOR_SRC_BASE + y * XOR_SRC_W;
            for (int bx = 0; bx < XOR_SRC_W; bx++) {
                int v = ((y + bx * 3) & 3) < 2 ? 0xAA : 0x55;
                vpu.writeVramPlane(BANK_PLANE0, ofs + bx, (byte) v);
            }
        }
    }

    // =====================================================================
    //  Sprite patterns (same as original)
    // =====================================================================

    private void drawSpritePatterns() {
        drawShipPattern(PAT_PLAYER, false);
        drawShipPattern(PAT_PLAYER_THR, true);

        for (int y = 5; y <= 10; y++)
            for (int x = 2; x <= 14; x++) {
                int d = Math.abs(y - 7);
                sprPx(PAT_PBULLET, x, y, d == 0 ? 3 : d == 1 ? 2 : 1);
            }

        drawEnemyA(PAT_ENEMY_A);
        drawBallShape(PAT_ENEMY_B, 7);
        drawEnemyC(PAT_ENEMY_C);

        for (int y = 5; y <= 10; y++)
            for (int x = 5; x <= 10; x++) {
                int d = Math.abs(x - 7) + Math.abs(y - 7);
                if (d < 4) sprPx(PAT_EBULLET, x, y, d < 2 ? 4 : d < 3 ? 2 : 1);
            }

        for (int fr = 0; fr < 4; fr++) drawExplosionFrame(PAT_EXPL0 + fr * 0x80, fr);
        drawBallShape(PAT_POWERUP, 6);
        drawBossPattern(PAT_BOSS);
        drawBossArmPattern(PAT_BOSS_ARM);
    }

    private void drawShipPattern(int ofs, boolean thrust) {
        int[][] ship = {
                {0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0},
                {0,0,0,0,0,0,0,0,1,1,2,1,0,0,0,0},
                {0,0,0,0,0,0,1,1,2,3,3,2,1,0,0,0},
                {0,0,7,7,1,1,2,3,4,5,5,4,3,2,0,0},
                {0,7,1,1,2,3,4,5,5,6,6,5,5,4,3,1},
                {7,1,2,3,4,5,5,6,6,6,6,6,5,4,3,2},
                {0,7,1,1,2,3,4,5,5,6,6,5,5,4,3,1},
                {0,0,7,7,1,1,2,3,4,5,5,4,3,2,0,0},
                {0,0,0,0,0,0,1,1,2,3,3,2,1,0,0,0},
                {0,0,0,0,0,0,0,0,1,1,2,1,0,0,0,0},
                {0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0},
        };
        for (int y = 0; y < ship.length; y++)
            for (int x = 0; x < 16; x++)
                if (x < ship[y].length && ship[y][x] != 0)
                    sprPx(ofs, x, y + 3, ship[y][x]);
        if (thrust) {
            sprPx(ofs, 0, 7, 8); sprPx(ofs, 0, 8, 9);
            sprPx(ofs, 1, 7, 9); sprPx(ofs, 1, 8, 8);
        }
    }

    private void drawEnemyA(int ofs) {
        for (int y = 0; y < 16; y++) {
            int halfH = 7 - Math.abs(y - 7);
            for (int x = 0; x < 16; x++) {
                int fromTip = 15 - x;
                if (fromTip <= halfH * 2 && fromTip >= 0)
                    sprPx(ofs, x, y, Math.min(6, fromTip / 2) + 1);
            }
        }
    }

    private void drawEnemyC(int ofs) {
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                float dx = x - 8f, dy = y - 8f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float angle = (float) Math.atan2(dy, dx);
                if (dist > 3 && dist < 7.5 && angle > -2.0 && angle < 2.0)
                    sprPx(ofs, x, y, 1 + (int)(dist - 3));
            }
    }

    private void drawBallShape(int ofs, int radius) {
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < radius) {
                    float ld = (float) Math.sqrt((dx + 3) * (dx + 3) + (dy + 3) * (dy + 3));
                    sprPx(ofs, x, y, clamp((int)(ld / 1.8f), 1, 6));
                }
            }
    }

    private void drawExplosionFrame(int ofs, int fr) {
        java.util.Random rng = new java.util.Random(fr * 7 + 42);
        float spread = 1.0f + fr * 1.5f;
        int particles = 30 - fr * 4;
        for (int i = 0; i < particles; i++) {
            float angle = rng.nextFloat() * (float)(Math.PI * 2);
            float dist = rng.nextFloat() * spread * 3;
            int spx = 8 + (int)(Math.cos(angle) * dist);
            int spy = 8 + (int)(Math.sin(angle) * dist);
            if (spx >= 0 && spx < 16 && spy >= 0 && spy < 16)
                sprPx(ofs, spx, spy, dist < spread ? (fr < 2 ? 6 : 1) : (fr < 3 ? 3 : 5));
        }
    }

    private void drawBossPattern(int ofs) {
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                float d = (float) Math.sqrt((x - 7.5f) * (x - 7.5f) + (y - 7.5f) * (y - 7.5f));
                if (d < 7.5)
                    sprPx(ofs, x, y, d < 2 ? 7 : d < 4 ? 6 : d < 5.5 ? (((x+y)&1)==0?3:4) : 1+((int)d%3));
            }
    }

    private void drawBossArmPattern(int ofs) {
        for (int y = 2; y < 14; y++)
            for (int x = 1; x < 15; x++) {
                int core = Math.abs(y - 8);
                if (core < 5) sprPx(ofs, x, y, core < 2 ? 5 : core < 4 ? 3 : 1);
            }
    }

    // =====================================================================
    //  Group config
    // =====================================================================

    private void configureGroups() {
        // Group 0: far layer (planes 0-3), single-buffered, scroll via registers
        setGroup(0, 0, 4, 0,  0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        // Group 1: near layer (planes 4-7), double-buffered, starts showing buffer A
        setGroup(1, 4, 4, 16, 0, 0, 1, GF_LORES | GF_WRAP_X | GF_WRAP_Y, BUF_A);
        // Unused
        setGroup(2, 0, 0, 0, 0, 0, 2, 0, 0);
        setGroup(3, 0, 0, 0, 0, 0, 3, 0, 0);
    }

    private void buildInitCopper() {
        int idx = 0;
        for (int ly = 0; ly < LH; ly++) {
            int rgb = skyGrad(ly, true);
            int v = toRgb565(rgb);
            copEntry(idx++, ly * 2, PAL_BASE, v & 0xFF, (v >>> 8) & 0xFF, COP_W16);
        }
        copEntry(idx++, 0, 0, 0, 0, COP_END);
        int bytes = idx * 8;
        mmio(COP_LEN_L, bytes & 0xFF);
        mmio(COP_LEN_H, (bytes >>> 8) & 0xFF);
    }

    private void clearAllEntities() {
        Arrays.fill(pbActive, false);
        Arrays.fill(eActive, false);
        Arrays.fill(ebActive, false);
        Arrays.fill(xpActive, false);
        Arrays.fill(pwActive, false);
        bossActive = false;
    }

    // =====================================================================
    //  Blitter wrappers
    // =====================================================================

    /**
     * RECTFILL: pixel-coordinate rectangle fill into planar VRAM.
     * {@code dstBase} is the base offset within each plane (0x0000 or 0x2000 for double buffer).
     * {@code pitchBytes} is bytes-per-row (NOT delta).
     * {@code colorBits} maps bit N → plane N.
     */
    private void bltRectFill(int dstBase, int pitchBytes,
                             int x, int y, int w, int h,
                             int colorBits, int planeMask) {
        if (w <= 0 || h <= 0) return;
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

    /**
     * Byte-addressed COPY / XOR blit with shift and edge masks.
     * Pitches are signed deltas added after each W-byte row.
     */
    private void bltCopyBytes(int srcOfs, int dstOfs,
                              int wBytes, int hRows,
                              int srcPitchDelta, int dstPitchDelta,
                              int planeMask, int rop, int shift,
                              int firstMask, int lastMask,
                              boolean trans, boolean forceBack) {
        if (wBytes <= 0 || hRows <= 0) return;
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

    /** Spin-tick until blitter signals done (STATUS_BLT), then W1C-clear it. */
    private void bltWaitDone() {
        for (int guard = 0; guard < 200_000; guard++) {
            int st = vpu.readMmio(REG_STATUS) & 0xFF;
            if ((st & STATUS_BLT) != 0) {
                mmio(REG_STATUS, STATUS_BLT);
                return;
            }
            vpu.tick(128);
            frameCycles += 128;
        }
    }

    // =====================================================================
    //  Low-level VPU helpers
    // =====================================================================

    private void mmio(int reg, int val) { vpu.writeMmio(reg, (byte)(val & 0xFF)); }

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
        mmio(ofs, v & 0xFF);
        mmio(ofs + 1, (v >>> 8) & 0xFF);
    }

    private void setPixelGroup(int ps, int pe, int x, int y, int col) {
        if (x < 0 || x >= LW || y < 0 || y >= LH) return;
        int byteOfs = y * BPR + (x >>> 3);
        int bit = 1 << (7 - (x & 7)), nbit = ~bit & 0xFF;
        for (int p = ps; p <= pe; p++) {
            byte cur = vpu.readVramPlane(BANK_PLANE0 + p, byteOfs);
            int cv = cur & 0xFF;
            cv = ((col >>> (p - ps)) & 1) != 0 ? (cv | bit) : (cv & nbit);
            vpu.writeVramPlane(BANK_PLANE0 + p, byteOfs, (byte) cv);
        }
    }

    private void sprPx(int dataOfs, int x, int y, int col) {
        int ofs = dataOfs + y * 8 + (x >>> 1);
        byte cur = vpu.readVramPlane(BANK_SPRITES0, ofs);
        int cv = cur & 0xFF;
        cv = (x & 1) == 0
                ? (cv & 0x0F) | ((col & 0x0F) << 4)
                : (cv & 0xF0) | (col & 0x0F);
        vpu.writeVramPlane(BANK_SPRITES0, ofs, (byte) cv);
    }

    private void oamByte(int si, int field, int val) {
        mmio(OAM_BASE + si * 32 + field, val);
    }

    private void oamWord(int si, int field, int val) {
        oamByte(si, field, val & 0xFF);
        oamByte(si, field + 1, (val >>> 8) & 0xFF);
    }

    private void setSpriteOAM(int si, int x, int y, int w, int h, int patOfs,
                              int attrBase, int palIdx, int pri, int xstep, int ystep) {
        int bank = BANK_SPRITES0 & 0x0F;
        int attr = (attrBase & 0x3F) | (((bank >>> 2) & 0x03) << 6);
        int dataWord = ((bank & 0x03) << 14) | (patOfs & 0x3FFF);
        oamWord(si, 0, x);
        oamWord(si, 2, y);
        oamByte(si, 4, w); oamByte(si, 5, h);
        oamWord(si, 6, dataWord);
        oamByte(si, 8, attr);
        oamByte(si, 9, (palIdx & 0x0F) << 4);
        oamByte(si, 10, 0); oamByte(si, 11, pri);
        oamWord(si, 12, xstep); oamWord(si, 14, ystep);
    }

    private void disableSprite(int si) { oamByte(si, 8, 0); }

    private void setGroup(int gi, int ps, int pc, int pal, int sx, int sy, int pri, int fl, int bpo) {
        int b = GROUP_BASE + gi * 0x10;
        mmio(b, ps); mmio(b+1, pc); mmio(b+2, pal);
        mmio(b+3, sx & 0xFF); mmio(b+4, (sx>>>8) & 0xFF);
        mmio(b+5, sy & 0xFF); mmio(b+6, pri); mmio(b+7, fl);
        mmio(b+8, bpo & 0xFF); mmio(b+9, (bpo>>>8) & 0xFF);
    }

    private void setGroupScroll(int gi, int sx, int sy) {
        int b = GROUP_BASE + gi * 0x10;
        mmio(b+3, sx & 0xFF); mmio(b+4, (sx>>>8) & 0xFF);
        mmio(b+5, sy & 0xFF);
    }

    private void setGroupBplOfs(int gi, int bpo) {
        int b = GROUP_BASE + gi * 0x10;
        mmio(b+8, bpo & 0xFF); mmio(b+9, (bpo>>>8) & 0xFF);
    }

    private void copEntry(int idx, int scan, int reg, int vLo, int vHi, int flags) {
        int b = TBL_COP_BASE + idx * 8;
        vpu.writeVramPlane(BANK_TABLES, b,     (byte)(scan & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 1, (byte)((scan>>>8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 2, (byte)(reg & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 3, (byte)((reg>>>8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 4, (byte)(vLo & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 5, (byte)(vHi & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 6, (byte)(flags & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 7, (byte) 0);
    }

    private void putChar(int row, int col, int ch, int attr) {
        int ofs = TEXT_BASE + (row * 80 + col) * 2;
        mmio(ofs, ch & 0xFF); mmio(ofs + 1, attr & 0xFF);
    }

    private void putStr(int row, int col, String s, int attr) {
        for (int i = 0; i < s.length() && col + i < 80; i++) putChar(row, col + i, s.charAt(i), attr);
    }

    private void clearText() { mmio(TX_CMD, 0x04); }

    // =====================================================================
    //  Utilities
    // =====================================================================

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int toRgb565(int c) {
        return ((c >> 16 & 0xF8) << 8) | ((c >> 8 & 0xFC) << 3) | ((c & 0xF8) >> 3);
    }

    private static int lerpRgb(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int)((a>>16&0xFF) + ((b>>16&0xFF) - (a>>16&0xFF)) * t);
        int g = (int)((a>>8 &0xFF) + ((b>>8 &0xFF) - (a>>8 &0xFF)) * t);
        int bl= (int)((a    &0xFF) + ((b    &0xFF) - (a    &0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int blendRgb(int a, int b, int alpha) {
        int ia = 255 - alpha;
        return (((a>>16&0xFF)*ia + (b>>16&0xFF)*alpha) / 255 << 16)
                | (((a>>8 &0xFF)*ia + (b>>8 &0xFF)*alpha) / 255 << 8)
                | (((a    &0xFF)*ia + (b    &0xFF)*alpha) / 255);
    }

    private static long lcg(long s) { return s * 6364136223846793005L + 1442695040888963407L; }
}