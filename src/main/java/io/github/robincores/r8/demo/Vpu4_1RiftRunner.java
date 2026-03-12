package io.github.robincores.r8.demo;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.device.VPU;
import io.github.robincores.r8.device.VPU_v4_1;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>RIFT RUNNER</h1>
 * <p>A dual-layer side-scrolling shmup ported to the VPU v4.1 banked layout.</p>
 *
 * <p><b>VPU v4.1 banked layout (per VPU_v4_1):</b></p>
 * <ul>
 *   <li>VBANK 0x0..0x7: bitplanes 0..7 (1bpp each)</li>
 *   <li>VBANK 0x8: tables bank (copper program)</li>
 *   <li>VBANK 0x9..0xF: sprite pattern banks (4bpp packed)</li>
 *   <li>OAM: MMIO 0x1000..0x1FFF</li>
 * </ul>
 *
 * <h3>Controls</h3>
 * <ul>
 *   <li><b>Arrows / WASD</b> — move</li>
 *   <li><b>Z / Space</b> — fire</li>
 *   <li><b>X / Shift</b> — RIFT SHIFT (flip between near &amp; far layer)</li>
 *   <li><b>Enter</b> — start / restart</li>
 * </ul>
 *
 * <h3>VPU features exercised</h3>
 * <ul>
 *   <li><b>Dual playfield</b> — Group 0 (far stars/nebula) + Group 1 (near asteroid field)</li>
 *   <li><b>Layer mechanic</b> — enemies exist on one layer; flip to engage or dodge</li>
 *   <li><b>Copper</b> — per-scanline atmosphere gradient, layer-dependent vibe + flash</li>
 *   <li><b>Sprites</b> — player, bullets, enemies, explosions with Lynx-style scaling</li>
 *   <li><b>Text overlay</b> — transparent HUD (score, lives, layer indicator, wave)</li>
 * </ul>
 */
public class Vpu4_1RiftRunner extends Application {

    // =====================================================================
    //  Platform
    // =====================================================================

    private static final DisplayConfig CFG = new DisplayConfig(640, 400, 640, 400, 400, 449, 400);
    private static final int CPF = CFG.cyclesPerFrame();

    // Logical coords (LORES 320×200, displayed 640×400)
    private static final int LW = 320, LH = 200, BPR = 40;

    // =====================================================================
    //  v4.1 banks
    // =====================================================================

    private static final int BANK_TABLES   = 0x08; // copper program
    private static final int BANK_SPRITES0 = 0x09; // sprite patterns live in 0x9..0xF (we use 0x9)
    private static final int TBL_COPPER_BASE = 0x0000;
    private static final int OAM_MMIO_BASE = 0x1000;

    // =====================================================================
    //  MMIO addresses (v4.1)
    // =====================================================================

    private static final int CTRL       = 0x0000;

    private static final int TX_CTRL    = 0x0006;
    private static final int TX_CMD     = 0x0030;

    private static final int SPR_CTRL   = 0x0014;

    private static final int COP_CTRL   = 0x0020;
    private static final int COP_LEN_L  = 0x0021, COP_LEN_H = 0x0022;
    private static final int COP_OFS_L  = 0x0023, COP_OFS_H = 0x0024;

    private static final int GROUP_BASE = 0x0400;
    private static final int PAL_BASE   = 0x0100;
    private static final int TEXT_BASE  = 0x2000;

    private static final int COP_W16 = 0x01, COP_END = 0x80;
    private static final int GF_LORES = 0x01, GF_WRAP_X = 0x02, GF_WRAP_Y = 0x04;

    // Sprite ATTR bits (v4.1)
    private static final int SA_EN = 0x01;

    // =====================================================================
    //  Sprite pattern offsets (16×16 4bpp = 0x80 each)
    //  These are OFFSETS inside the selected sprite bank (BANK_SPRITES0).
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
    private static final int PAT_RIFT_ICON  = 0x0700; // (unused for now)

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
    private static final int MAX_BOSS_PARTS   = 6; // (unused for now)

    // =====================================================================
    //  Sine table
    // =====================================================================

    private static final int[] SIN = new int[256];
    static {
        for (int i = 0; i < 256; i++) SIN[i] = (int) Math.round(127.0 * Math.sin(i * Math.PI * 2.0 / 256.0));
    }
    private static int sin256(int i) { return SIN[i & 0xFF]; }

    // =====================================================================
    //  Game state
    // =====================================================================

    private enum State { TITLE, PLAYING, GAME_OVER }

    private VPU vpu;
    private volatile int frame;
    private final Set<KeyCode> keys = ConcurrentHashMap.newKeySet();

    private State state = State.TITLE;
    private int stateTimer;

    // Player
    private int px, py;             // logical coords (0-319, 0-199)
    private int playerLayer;        // 0=far, 1=near
    private int lives;
    private int score;
    private int fireTimer;
    private int iframes;            // invincibility frames after hit
    private int riftCooldown;       // layer-flip cooldown
    private int riftFlashTimer;     // visual effect on flip
    private int weaponLevel;        // 0=single, 1=double, 2=triple
    private boolean playerAlive;

    // Player bullets
    private final int[] pbx = new int[MAX_PBULLETS], pby = new int[MAX_PBULLETS];
    private final boolean[] pbActive = new boolean[MAX_PBULLETS];
    private final int[] pbLayer = new int[MAX_PBULLETS]; // which layer

    // Enemies
    private final int[] ex = new int[MAX_ENEMIES], ey = new int[MAX_ENEMIES];
    private final int[] eType = new int[MAX_ENEMIES]; // 0=A, 1=B, 2=C
    private final int[] eHp = new int[MAX_ENEMIES];
    private final int[] eLayer = new int[MAX_ENEMIES];
    private final int[] eTimer = new int[MAX_ENEMIES];
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

    // Wave spawning
    private int waveNum;
    private int waveTimer;
    private int spawnAccum;
    private int totalKills;

    // Boss
    private boolean bossActive;
    private int bossX, bossY, bossHp, bossMaxHp, bossTimer, bossPhase;

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

        stage.setTitle("RIFT RUNNER — VPU v4.1");
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
                    gameLoop();
                    vpu.tick(CPF);
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
    //  Main game loop (called at ~70Hz)
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
    //  Input helpers
    // =====================================================================

    private boolean keyHeld(KeyCode... codes) {
        for (KeyCode c : codes) if (keys.contains(c)) return true;
        return false;
    }

    // =====================================================================
    //  State: TITLE
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
        putStr(22, 10, "VPU v4.1  ·  8 BITPLANES  ·  COPPER", 0x08);
        putStr(23, 10, "OAM MMIO  ·  BANKED SPRITES  ·  DUAL PF", 0x08);
    }

    private void updateTitle() {
        stateTimer++;
        scrollFar  += 1;
        scrollNear += 2;
        setGroupScroll(0, scrollFar, 0);
        setGroupScroll(1, scrollNear, 0);

        if (keyHeld(KeyCode.ENTER)) startGame();
    }

    // =====================================================================
    //  State: PLAYING — init
    // =====================================================================

    private void startGame() {
        state = State.PLAYING;
        stateTimer = 0;

        px = 40; py = 100;
        playerLayer = 1; // start on near layer
        lives = 3;
        score = 0;
        fireTimer = 0;
        iframes = 90; // brief invuln at start
        riftCooldown = 0;
        riftFlashTimer = 0;
        weaponLevel = 0;
        playerAlive = true;

        waveNum = 1;
        waveTimer = 0;
        spawnAccum = 0;
        totalKills = 0;

        bossActive = false;

        clearAllEntities();
        clearText();
        updateHUD();
    }

    // =====================================================================
    //  State: GAME OVER
    // =====================================================================

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
        setGroupScroll(1, scrollNear, 0);

        if (stateTimer > 60 && keyHeld(KeyCode.ENTER)) showTitle();
    }

    // =====================================================================
    //  Player update
    // =====================================================================

    private void updatePlayer() {
        if (!playerAlive) return;

        // Movement (3 px/frame in logical coords)
        int spd = 3;
        if (keyHeld(KeyCode.LEFT, KeyCode.A))  px -= spd;
        if (keyHeld(KeyCode.RIGHT, KeyCode.D)) px += spd;
        if (keyHeld(KeyCode.UP, KeyCode.W))    py -= spd;
        if (keyHeld(KeyCode.DOWN, KeyCode.S))  py += spd;
        px = Math.max(4, Math.min(LW - 20, px));
        py = Math.max(4, Math.min(LH - 20, py));

        // Fire
        if (fireTimer > 0) fireTimer--;
        if (keyHeld(KeyCode.Z, KeyCode.SPACE) && fireTimer == 0) {
            fireBullet(px + 14, py + 6); // center of ship
            if (weaponLevel >= 1) fireBullet(px + 12, py + 2);
            if (weaponLevel >= 1) fireBullet(px + 12, py + 10);
            if (weaponLevel >= 2) fireBullet(px + 10, py - 2);
            if (weaponLevel >= 2) fireBullet(px + 10, py + 14);
            fireTimer = (weaponLevel >= 2) ? 4 : (weaponLevel >= 1) ? 5 : 6;
        }

        // Rift shift
        if (riftCooldown > 0) riftCooldown--;
        if (keyHeld(KeyCode.X, KeyCode.SHIFT) && riftCooldown == 0) {
            playerLayer ^= 1;
            riftCooldown = 20; // ~0.3s cooldown
            riftFlashTimer = 12;

            // Shift all active player bullets to new layer (your weapon follows you)
            for (int i = 0; i < MAX_PBULLETS; i++) {
                if (pbActive[i]) pbLayer[i] = playerLayer;
            }
        }

        if (iframes > 0) iframes--;
        if (riftFlashTimer > 0) riftFlashTimer--;
    }

    private void fireBullet(int bx, int by) {
        for (int i = 0; i < MAX_PBULLETS; i++) {
            if (!pbActive[i]) {
                pbx[i] = bx;
                pby[i] = by;
                pbActive[i] = true;
                pbLayer[i] = playerLayer;
                return;
            }
        }
    }

    // =====================================================================
    //  Player bullets
    // =====================================================================

    private void updatePlayerBullets() {
        for (int i = 0; i < MAX_PBULLETS; i++) {
            if (!pbActive[i]) continue;
            pbx[i] += 6; // fast rightward
            if (pbx[i] > LW + 8) pbActive[i] = false;
        }
    }

    // =====================================================================
    //  Enemies
    // =====================================================================

    private void spawnEnemy(int type, int layer, int startX, int startY) {
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!eActive[i]) {
                eActive[i] = true;
                eType[i] = type;
                eLayer[i] = layer;
                ex[i] = startX;
                ey[i] = startY;
                eTimer[i] = 0;
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
                case 0 -> { // Fighter: straight left
                    ex[i] -= 2;
                    ey[i] += sin256(eTimer[i] * 4) / 64; // slight wave
                }
                case 1 -> { // Heavy: slow, shoots
                    ex[i] -= 1;
                    if (eTimer[i] % 50 == 30) fireEnemyBullet(ex[i], ey[i] + 6, eLayer[i]);
                }
                case 2 -> { // Swooper: sine dive
                    ex[i] -= 3;
                    ey[i] += sin256(eTimer[i] * 6) / 32;
                }
            }

            ey[i] = Math.max(2, Math.min(LH - 18, ey[i]));
            if (ex[i] < -20) eActive[i] = false;
        }
    }

    private void fireEnemyBullet(int bx, int by, int layer) {
        for (int i = 0; i < MAX_EBULLETS; i++) {
            if (!ebActive[i]) {
                ebx[i] = bx;
                eby[i] = by;

                // Aim roughly at player
                int dx = px - bx, dy = py - by;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1) len = 1;
                ebdx[i] = Math.round(dx * 3 / len);
                ebdy[i] = Math.round(dy * 3 / len);
                if (ebdx[i] == 0 && ebdy[i] == 0) ebdx[i] = -3;

                ebLayer[i] = layer;
                ebActive[i] = true;
                return;
            }
        }
    }

    // =====================================================================
    //  Enemy bullets
    // =====================================================================

    private void updateEnemyBullets() {
        for (int i = 0; i < MAX_EBULLETS; i++) {
            if (!ebActive[i]) continue;
            ebx[i] += ebdx[i];
            eby[i] += ebdy[i];
            if (ebx[i] < -8 || ebx[i] > LW + 8 || eby[i] < -8 || eby[i] > LH + 8)
                ebActive[i] = false;
        }
    }

    // =====================================================================
    //  Explosions
    // =====================================================================

    private void spawnExplosion(int x, int y) {
        for (int i = 0; i < MAX_EXPLS; i++) {
            if (!xpActive[i]) {
                xpx[i] = x - 4; // center on target
                xpy[i] = y - 4;
                xpTimer[i] = 0;
                xpActive[i] = true;
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

    // =====================================================================
    //  Powerups
    // =====================================================================

    private void spawnPowerup(int x, int y) {
        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) {
                pwx[i] = x;
                pwy[i] = y;
                pwActive[i] = true;
                return;
            }
        }
    }

    private void updatePowerups() {
        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) continue;
            pwx[i] -= 1; // drift left
            pwy[i] += sin256(frame * 3 + i * 64) / 128; // gentle bob
            if (pwx[i] < -16) pwActive[i] = false;
        }
    }

    // =====================================================================
    //  Boss
    // =====================================================================

    private void spawnBoss() {
        bossActive = true;
        bossX = LW + 20;
        bossY = LH / 2 - 16;
        bossHp = 30 + waveNum * 10;
        bossMaxHp = bossHp;
        bossTimer = 0;
        bossPhase = 0;
    }

    private void updateBoss() {
        if (!bossActive) return;
        bossTimer++;

        // Entry phase
        if (bossX > LW - 50) { bossX -= 1; return; }

        // Bobbing
        bossY = LH / 2 - 16 + sin256(bossTimer * 2) * 40 / 127;
        bossY = Math.max(4, Math.min(LH - 36, bossY));

        // Fire patterns (simple)
        if (bossTimer % 25 == 0) {
            // near-layer spray (we keep your original loop; it still "feels" like a burst)
            for (int d = -2; d <= 2; d++) {
                fireEnemyBullet(bossX, bossY + 8, 1);
            }
        }
        if (bossTimer % 40 == 20) {
            // far-layer shot (cross-layer threat)
            fireEnemyBullet(bossX, bossY + 8, 0);
        }
    }

    // =====================================================================
    //  Wave spawner
    // =====================================================================

    private void updateWaveSpawner() {
        if (bossActive) return;

        waveTimer++;

        // Spawn enemies periodically, increasing with wave number
        int spawnRate = Math.max(15, 50 - waveNum * 5);
        if (waveTimer % spawnRate == 0) {
            int type = (waveTimer / spawnRate) % 3;
            int layer = ((spawnAccum & 1) == 0) ? 1 : 0; // alternate layers
            int sy = 20 + (int) (lcg(frame + spawnAccum) & 0x7F) % 160;
            spawnEnemy(type, layer, LW + 8, sy);
            spawnAccum++;
        }

        // Burst waves
        if (waveTimer % 200 == 100) {
            int burstLayer = (waveNum & 1);
            for (int b = 0; b < 3 + waveNum; b++) {
                spawnEnemy(2, burstLayer, LW + 8 + b * 15, 30 + b * 20);
            }
        }

        // Boss trigger
        if (totalKills > 0 && totalKills % (15 + waveNum * 5) == 0 && !bossActive) {
            spawnBoss();
        }

        // Wave progression
        if (waveTimer > 700 + waveNum * 100) {
            waveNum++;
            waveTimer = 0;
        }
    }

    // =====================================================================
    //  Collision detection
    // =====================================================================

    private void checkCollisions() {
        if (!playerAlive) return;

        // Player bullets vs enemies (same layer only)
        for (int bi = 0; bi < MAX_PBULLETS; bi++) {
            if (!pbActive[bi]) continue;
            for (int ei = 0; ei < MAX_ENEMIES; ei++) {
                if (!eActive[ei]) continue;
                if (pbLayer[bi] != eLayer[ei]) continue;
                if (boxHit(pbx[bi], pby[bi], 4, 4, ex[ei], ey[ei], 14, 14)) {
                    pbActive[bi] = false;
                    eHp[ei]--;
                    if (eHp[ei] <= 0) {
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

        // Player bullets vs boss
        if (bossActive) {
            for (int bi = 0; bi < MAX_PBULLETS; bi++) {
                if (!pbActive[bi]) continue;
                if (boxHit(pbx[bi], pby[bi], 4, 4, bossX, bossY, 28, 28)) {
                    pbActive[bi] = false;
                    bossHp--;
                    if (bossHp <= 0) {
                        bossActive = false;
                        spawnExplosion(bossX + 8, bossY + 8);
                        spawnExplosion(bossX + 16, bossY);
                        spawnExplosion(bossX, bossY + 16);
                        score += 5000;
                        waveNum++;
                        waveTimer = 0;
                    }
                }
            }
        }

        // Enemies vs player (same layer, respect iframes)
        if (iframes == 0) {
            for (int ei = 0; ei < MAX_ENEMIES; ei++) {
                if (!eActive[ei] || eLayer[ei] != playerLayer) continue;
                if (boxHit(px, py, 12, 12, ex[ei], ey[ei], 14, 14)) {
                    hitPlayer();
                    eActive[ei] = false;
                    spawnExplosion(ex[ei], ey[ei]);
                    break;
                }
            }
        }

        // Enemy bullets vs player (same layer)
        if (iframes == 0) {
            for (int bi = 0; bi < MAX_EBULLETS; bi++) {
                if (!ebActive[bi] || ebLayer[bi] != playerLayer) continue;
                if (boxHit(px + 2, py + 2, 10, 10, ebx[bi], eby[bi], 4, 4)) {
                    hitPlayer();
                    ebActive[bi] = false;
                    break;
                }
            }
        }

        // Boss vs player
        if (bossActive && iframes == 0) {
            if (boxHit(px, py, 12, 12, bossX, bossY, 28, 28)) hitPlayer();
        }

        // Player vs powerups (any layer)
        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) continue;
            if (boxHit(px, py, 14, 14, pwx[i], pwy[i], 10, 10)) {
                pwActive[i] = false;
                if (weaponLevel < 2) weaponLevel++;
                else score += 500;
            }
        }
    }

    private void hitPlayer() {
        lives--;
        iframes = 90;
        weaponLevel = Math.max(0, weaponLevel - 1);
        spawnExplosion(px + 4, py + 4);
        if (lives <= 0) {
            playerAlive = false;
            showGameOver();
        }
    }

    private static boolean boxHit(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    // =====================================================================
    //  Scrolling
    // =====================================================================

    private void updateScroll() {
        scrollFar  += 1;
        scrollNear += 2;
        setGroupScroll(0, scrollFar, 0);
        setGroupScroll(1, scrollNear, 0);
    }

    // =====================================================================
    //  Copper — atmosphere (v4.1 tables bank @ VBANK=8)
    // =====================================================================

    private void updateCopper() {
        int idx = 0;
        boolean onNear = (state == State.PLAYING) && playerLayer == 1;

        for (int ly = 0; ly < LH; ly++) {
            int scan = ly * 2;
            int rgb = skyGrad(ly, onNear);

            // Rift flash effect
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
            if (t < 0.5f) return lerpRgb(top, mid, t * 2);
            return lerpRgb(mid, bot, (t - 0.5f) * 2);
        } else {
            int top = 0x000818, mid = 0x082048, bot = 0x040410;
            if (t < 0.5f) return lerpRgb(top, mid, t * 2);
            return lerpRgb(mid, bot, (t - 0.5f) * 2);
        }
    }

    // =====================================================================
    //  Push all sprites to OAM (v4.1: OAM is MMIO 0x1000..)
    // =====================================================================

    private void pushAllSprites() {
        // Disable all sprites (avoid leftovers)
        for (int i = 0; i < 128; i++) disableSprite(i);

        if (state != State.PLAYING) return;

        // Player
        if (playerAlive && (iframes == 0 || (frame & 2) != 0)) {
            boolean thrust = keyHeld(KeyCode.RIGHT, KeyCode.D);
            int pat = thrust ? PAT_PLAYER_THR : PAT_PLAYER;
            setSpriteOAM(OAM_PLAYER, px, py, 16, 16, pat, SA_EN, 2, playerLayer + 1, 0x0100, 0x0100);
        }

        // Player bullets
        for (int i = 0; i < MAX_PBULLETS; i++) {
            if (!pbActive[i]) continue;
            int pri = pbLayer[i] + 1;
            setSpriteOAM(OAM_PBULLET_BASE + i, pbx[i], pby[i], 16, 16, PAT_PBULLET,
                    SA_EN, 3, pri, 0x0100, 0x0100);
        }

        // Enemies
        for (int i = 0; i < MAX_ENEMIES; i++) {
            if (!eActive[i]) continue;
            int pat = switch (eType[i]) { case 1 -> PAT_ENEMY_B; case 2 -> PAT_ENEMY_C; default -> PAT_ENEMY_A; };
            boolean ghosted = (eLayer[i] != playerLayer);
            int palIdx = ghosted ? 5 : 4; // dimmed palette for ghosted
            int pri = eLayer[i] + 1;
            int scale = ghosted ? 0x0140 : 0x0100; // depth illusion
            setSpriteOAM(OAM_ENEMY_BASE + i, ex[i], ey[i], 16, 16, pat,
                    SA_EN, palIdx, pri, scale, scale);
        }

        // Enemy bullets
        for (int i = 0; i < MAX_EBULLETS; i++) {
            if (!ebActive[i]) continue;
            boolean ghosted = (ebLayer[i] != playerLayer);
            int palIdx = ghosted ? 5 : 6;
            int pri = ebLayer[i] + 1;
            setSpriteOAM(OAM_EBULLET_BASE + i, ebx[i], eby[i], 16, 16, PAT_EBULLET,
                    SA_EN, palIdx, pri, 0x0100, 0x0100);
        }

        // Explosions (animated)
        for (int i = 0; i < MAX_EXPLS; i++) {
            if (!xpActive[i]) continue;
            int fr = Math.min(3, xpTimer[i] / 5);
            int pat = PAT_EXPL0 + fr * 0x80;
            int scale = 0x0100 - (xpTimer[i] * 6);
            if (scale < 0x40) scale = 0x40;
            setSpriteOAM(OAM_EXPL_BASE + i, xpx[i], xpy[i], 16, 16, pat,
                    SA_EN, 7, 2, scale, scale);
        }

        // Powerups (pulsing)
        for (int i = 0; i < MAX_POWERS; i++) {
            if (!pwActive[i]) continue;
            int pulse = 0x0100 + sin256(frame * 4 + i * 64) * 0x30 / 127;
            setSpriteOAM(OAM_POWER_BASE + i, pwx[i], pwy[i], 16, 16, PAT_POWERUP,
                    SA_EN, 8, 2, pulse, pulse);
        }

        // Boss
        if (bossActive) {
            int pulse = 0x0080 + sin256(bossTimer * 2) * 0x10 / 127;
            setSpriteOAM(OAM_BOSS_BASE, bossX, bossY, 16, 16, PAT_BOSS,
                    SA_EN, 9, 2, pulse, pulse);

            for (int a = 0; a < 2; a++) {
                int armY = bossY + (a == 0 ? -10 : 22) + sin256(bossTimer * 3 + a * 128) * 4 / 127;
                setSpriteOAM(OAM_BOSS_BASE + 1 + a, bossX + 4, armY, 16, 16, PAT_BOSS_ARM,
                        SA_EN, 9, 2, 0x00C0, 0x00C0);
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
            int filled = bossMaxHp > 0 ? (bossHp * barLen / bossMaxHp) : 0;
            putStr(1, 1, "BOSS:", 0x0C);
            for (int i = 0; i < barLen; i++) {
                putChar(1, 6 + i, i < filled ? 0xDB : 0xB0, i < filled ? 0x0C : 0x08);
            }
        } else {
            for (int i = 0; i < 30; i++) putChar(1, i, ' ', 0x00);
        }

        putStr(1, 60, "WEAPON:", 0x07);
        String wepStr = switch (weaponLevel) { case 1 -> "DOUBLE"; case 2 -> "TRIPLE"; default -> "SINGLE"; };
        int wepCol = switch (weaponLevel) { case 1 -> 0x0A; case 2 -> 0x0D; default -> 0x07; };
        putStr(1, 67, wepStr + " ", wepCol);
    }

    // =====================================================================
    //  VPU init — palette, patterns, groups, playfield art
    // =====================================================================

    private void initVPU() {
        setupPalette();
        drawStarfield();
        drawAsteroidField();
        drawSpritePatterns();
        configureGroups();

        // Text overlay: transparent
        mmio(TX_CTRL, 0x01 | 0x04);
        mmio(TX_CMD, 0x04);

        // Copper program location (tables bank, offset 0)
        mmio(COP_OFS_L, TBL_COPPER_BASE & 0xFF);
        mmio(COP_OFS_H, (TBL_COPPER_BASE >>> 8) & 0xFF);

        // Copper init
        buildInitCopper();

        // Enable
        mmio(CTRL,     0x01);
        mmio(COP_CTRL, 0x01);
        mmio(SPR_CTRL, 0x01);
    }

    // =====================================================================
    //  Palette
    // =====================================================================

    private void setupPalette() {
        setPal(0, 0x000000); // bg (copper-driven)

        // 1: Group 0 palette (far layer)
        setPal(1,  0x404080); setPal(2,  0x6060B0); setPal(3,  0x8080E0);
        setPal(4,  0xA0A0FF); setPal(5,  0x303060); setPal(6,  0x202040);
        setPal(7,  0x504880); setPal(8,  0x6858A0); setPal(9,  0x181830);
        setPal(10, 0x282850); setPal(11, 0xC0B0FF); setPal(12, 0xE0D0FF);
        setPal(13, 0x383068); setPal(14, 0x100818); setPal(15, 0x584888);

        // 16: Group 1 palette (near layer)
        setPal(16, 0x000000);
        setPal(17, 0x604830); setPal(18, 0x806040); setPal(19, 0xA08050);
        setPal(20, 0x483020); setPal(21, 0x705838); setPal(22, 0x907048);
        setPal(23, 0xB89060); setPal(24, 0x382010); setPal(25, 0x584028);
        setPal(26, 0xC0A870); setPal(27, 0xD8C088); setPal(28, 0x281808);
        setPal(29, 0x786048); setPal(30, 0x988068); setPal(31, 0xE0D0A0);

        // 32: Player sprite palette (pal idx 2)
        setPal(32, 0x000000); setPal(33, 0x2040FF); setPal(34, 0x4070FF);
        setPal(35, 0x60A0FF); setPal(36, 0x80D0FF); setPal(37, 0xA0E0FF);
        setPal(38, 0xFFFFFF); setPal(39, 0x1020A0); setPal(40, 0xFF8020);
        setPal(41, 0xFFD060); setPal(42, 0x103080); setPal(43, 0x205090);
        setPal(44, 0x6090C0); setPal(45, 0xC0E0FF); setPal(46, 0xFF4020);
        setPal(47, 0x182050);

        // 48: Player bullet palette (pal idx 3)
        setPal(48, 0x000000); setPal(49, 0xFFFF00); setPal(50, 0xFFFF80);
        setPal(51, 0xFFFFFF); setPal(52, 0xFFD000); setPal(53, 0xFF8000);
        setPal(54, 0xFFE040); setPal(55, 0x804000); setPal(56, 0xFFA020);
        setPal(57, 0xFFE060); setPal(58, 0xC0A000); setPal(59, 0xE0C020);
        setPal(60, 0x604000); setPal(61, 0xA08000); setPal(62, 0xFFFF40);
        setPal(63, 0x302000);

        // 64: Enemy palette (pal idx 4)
        setPal(64, 0x000000); setPal(65, 0xFF2020); setPal(66, 0xFF6040);
        setPal(67, 0xFF8060); setPal(68, 0xFFA080); setPal(69, 0xC01010);
        setPal(70, 0x800808); setPal(71, 0x400404); setPal(72, 0xFFB090);
        setPal(73, 0xE04030); setPal(74, 0xB02020); setPal(75, 0xD03828);
        setPal(76, 0x601010); setPal(77, 0xA01818); setPal(78, 0xFFC0A0);
        setPal(79, 0x200404);

        // 80: Ghosted enemy palette (pal idx 5)
        setPal(80, 0x000000); setPal(81, 0x401010); setPal(82, 0x501818);
        setPal(83, 0x602020); setPal(84, 0x682828); setPal(85, 0x380808);
        setPal(86, 0x280404); setPal(87, 0x180202); setPal(88, 0x703030);
        setPal(89, 0x481414); setPal(90, 0x380C0C); setPal(91, 0x401010);
        setPal(92, 0x200808); setPal(93, 0x300C0C); setPal(94, 0x783838);
        setPal(95, 0x100202);

        // 96: Enemy bullet (pal idx 6)
        setPal(96, 0x000000);  setPal(97, 0xFF4040); setPal(98, 0xFF8080);
        setPal(99, 0xFFB0B0); setPal(100,0xFFFFFF); setPal(101,0xC02020);
        setPal(102,0x801010); setPal(103,0xFF6060); setPal(104,0xE03030);

        // 112: Explosion (pal idx 7)
        setPal(112,0x000000); setPal(113,0xFFFF00); setPal(114,0xFFD000);
        setPal(115,0xFF8000); setPal(116,0xFF4000); setPal(117,0xFF0000);
        setPal(118,0xFFFFFF); setPal(119,0xFFA040); setPal(120,0xC06020);
        setPal(121,0x804010); setPal(122,0x402008); setPal(123,0xFF6000);

        // 128: Powerup (pal idx 8)
        setPal(128,0x000000); setPal(129,0x00FF00); setPal(130,0x40FF40);
        setPal(131,0x80FF80); setPal(132,0xC0FFC0); setPal(133,0xFFFFFF);
        setPal(134,0x008800); setPal(135,0x00CC00); setPal(136,0x20DD20);

        // 144: Boss (pal idx 9)
        setPal(144,0x000000); setPal(145,0x800080); setPal(146,0xA020A0);
        setPal(147,0xC040C0); setPal(148,0xE060E0); setPal(149,0xFF80FF);
        setPal(150,0xFFB0FF); setPal(151,0xFFFFFF); setPal(152,0x600060);
        setPal(153,0x400040); setPal(154,0xD050D0); setPal(155,0xB030B0);
    }

    // =====================================================================
    //  Playfield art
    // =====================================================================

    private void drawStarfield() {
        long seed = 12345;
        for (int i = 0; i < 400; i++) {
            seed = lcg(seed);
            int sx = (int) ((seed >>> 16) & 0x1FF) % LW;
            seed = lcg(seed);
            int sy = (int) ((seed >>> 16) & 0xFF) % LH;
            seed = lcg(seed);
            int col = 1 + (int) ((seed >>> 8) & 0x03); // 1-4
            setPixelGroup(0, 3, sx, sy, col);
            if (col >= 3 && sx + 1 < LW) setPixelGroup(0, 3, sx + 1, sy, col - 1);
        }

        for (int y = 60; y < 120; y++) {
            for (int x = 0; x < LW; x++) {
                double val = Math.sin(x * 0.03 + y * 0.08) * Math.cos(y * 0.05 - x * 0.02);
                if (val > 0.6) setPixelGroup(0, 3, x, y, 7);
                else if (val > 0.3) setPixelGroup(0, 3, x, y, 5);
            }
        }
    }

    private void drawAsteroidField() {
        long seed = 99999;
        for (int i = 0; i < 60; i++) {
            seed = lcg(seed);
            int rx = (int) ((seed >>> 8) & 0x1FF) % LW;
            seed = lcg(seed);
            int ry = (int) ((seed >>> 8) & 0xFF) % LH;
            seed = lcg(seed);
            int size = 2 + (int) (seed & 0x07);
            seed = lcg(seed);
            int col = 1 + (int) (seed & 0x07);
            drawRock(4, 7, rx, ry, size, col);
        }
    }

    private void drawRock(int ps, int pe, int cx, int cy, int r, int baseCol) {
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= r * r) {
                    int col = baseCol + (Math.abs(dx) + Math.abs(dy)) / (r + 1);
                    if (col > 15) col = 15;
                    setPixelGroup(ps, pe, cx + dx, cy + dy, col);
                }
            }
        }
    }

    // =====================================================================
    //  Sprite patterns (v4.1: write into BANK_SPRITES0)
    // =====================================================================

    private void drawSpritePatterns() {
        drawShipPattern(PAT_PLAYER, false);
        drawShipPattern(PAT_PLAYER_THR, true);

        for (int y = 5; y <= 10; y++) {
            for (int x = 2; x <= 14; x++) {
                int dist = Math.abs(y - 7);
                int col = dist == 0 ? 3 : dist == 1 ? 2 : 1;
                sprPx(PAT_PBULLET, x, y, col);
            }
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
                if (fromTip <= halfH * 2 && fromTip >= 0) {
                    int shade = Math.min(6, fromTip / 2) + 1;
                    sprPx(ofs, x, y, shade);
                }
            }
        }
    }

    private void drawEnemyC(int ofs) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 8f, dy = y - 8f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float angle = (float) Math.atan2(dy, dx);
                if (dist > 3 && dist < 7.5 && angle > -2.0 && angle < 2.0) {
                    sprPx(ofs, x, y, 1 + (int) (dist - 3));
                }
            }
        }
    }

    private void drawBallShape(int ofs, int radius) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < radius) {
                    float ld = (float) Math.sqrt((dx + 3) * (dx + 3) + (dy + 3) * (dy + 3));
                    int shade = Math.max(1, Math.min(6, (int) (ld / 1.8f)));
                    sprPx(ofs, x, y, shade);
                }
            }
        }
    }

    private void drawExplosionFrame(int ofs, int fr) {
        java.util.Random rng = new java.util.Random(fr * 7 + 42);
        float spread = 1.0f + fr * 1.5f;
        int particles = 30 - fr * 4;
        for (int i = 0; i < particles; i++) {
            float angle = rng.nextFloat() * (float) (Math.PI * 2);
            float dist = rng.nextFloat() * spread * 3;
            int px = 8 + (int) (Math.cos(angle) * dist);
            int py = 8 + (int) (Math.sin(angle) * dist);
            if (px >= 0 && px < 16 && py >= 0 && py < 16) {
                int col = dist < spread ? (fr < 2 ? 6 : 1) : (fr < 3 ? 3 : 5);
                sprPx(ofs, px, py, col);
            }
        }
    }

    private void drawBossPattern(int ofs) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = x - 7.5f, dy = y - 7.5f;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 7.5) {
                    int shade;
                    if (d < 2) shade = 7;
                    else if (d < 4) shade = 6;
                    else if (d < 5.5) shade = ((x + y) & 1) == 0 ? 3 : 4;
                    else shade = 1 + ((int) d % 3);
                    sprPx(ofs, x, y, shade);
                }
            }
        }
    }

    private void drawBossArmPattern(int ofs) {
        for (int y = 2; y < 14; y++) {
            for (int x = 1; x < 15; x++) {
                int core = Math.abs(y - 8);
                if (core < 5) {
                    sprPx(ofs, x, y, core < 2 ? 5 : core < 4 ? 3 : 1);
                }
            }
        }
    }

    // =====================================================================
    //  Group config
    // =====================================================================

    private void configureGroups() {
        setGroup(0, 0, 4, 0,  0, 0, 0, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        setGroup(1, 4, 4, 16, 0, 0, 1, GF_LORES | GF_WRAP_X | GF_WRAP_Y, 0);
        setGroup(2, 0, 0, 0,  0, 0, 2, 0, 0);
        setGroup(3, 0, 0, 0,  0, 0, 3, 0, 0);
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

    // =====================================================================
    //  Entity clear
    // =====================================================================

    private void clearAllEntities() {
        java.util.Arrays.fill(pbActive, false);
        java.util.Arrays.fill(eActive, false);
        java.util.Arrays.fill(ebActive, false);
        java.util.Arrays.fill(xpActive, false);
        java.util.Arrays.fill(pwActive, false);
        bossActive = false;
    }

    // =====================================================================
    //  VPU access helpers (v4.1 semantics)
    // =====================================================================

    private void mmio(int reg, int val) { vpu.writeMmio(reg, (byte) (val & 0xFF)); }

    private void setPal(int idx, int rgb24) {
        int v = toRgb565(rgb24);
        int ofs = PAL_BASE + idx * 2;
        mmio(ofs, v & 0xFF);
        mmio(ofs + 1, (v >>> 8) & 0xFF);
    }

    /**
     * Write a logical 0..15 color into planes [ps..pe] at (x,y).
     * v4.1: bitplanes are VBANK 0..7.
     */
    private void setPixelGroup(int ps, int pe, int x, int y, int col) {
        if (x < 0 || x >= LW || y < 0 || y >= LH) return;
        int byteOfs = y * BPR + (x >>> 3);
        int bit = 1 << (7 - (x & 7)), nbit = ~bit & 0xFF;

        for (int p = ps; p <= pe; p++) {
            byte cur = vpu.readVramPlane(p, byteOfs); // plane bank == p
            int cv = cur & 0xFF;
            cv = ((col >>> (p - ps)) & 1) != 0 ? (cv | bit) : (cv & nbit);
            vpu.writeVramPlane(p, byteOfs, (byte) cv);
        }
    }

    /**
     * Write 4bpp packed sprite pixel into BANK_SPRITES0.
     * 16x16 => 8 bytes per row.
     */
    private void sprPx(int dataOfs, int x, int y, int col) {
        int ofs = dataOfs + y * 8 + (x >>> 1);
        byte cur = vpu.readVramPlane(BANK_SPRITES0, ofs);
        int cv = cur & 0xFF;
        cv = (x & 1) == 0
                ? (cv & 0x0F) | ((col & 0x0F) << 4)
                : (cv & 0xF0) | (col & 0x0F);
        vpu.writeVramPlane(BANK_SPRITES0, ofs, (byte) cv);
    }

    // OAM is MMIO in v4.1 (0x1000..0x1FFF)
    private void oamByte(int si, int field, int val) {
        int ofs = OAM_MMIO_BASE + si * 32 + field;
        mmio(ofs, val);
    }
    private void oamWord(int si, int field, int val) {
        oamByte(si, field, val & 0xFF);
        oamByte(si, field + 1, (val >>> 8) & 0xFF);
    }

    /**
     * Copper entry goes into tables bank (VBANK=8) at TBL_COPPER_BASE.
     * Format is 8 bytes/entry:
     *   u16 scanline, u16 reg, u8 valLo, u8 valHi, u8 flags, u8 reserved
     */
    private void copEntry(int idx, int scan, int reg, int vLo, int vHi, int flags) {
        int b = TBL_COPPER_BASE + idx * 8;
        vpu.writeVramPlane(BANK_TABLES, b,     (byte) (scan & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 1, (byte) ((scan >>> 8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 2, (byte) (reg & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 3, (byte) ((reg >>> 8) & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 4, (byte) (vLo & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 5, (byte) (vHi & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 6, (byte) (flags & 0xFF));
        vpu.writeVramPlane(BANK_TABLES, b + 7, (byte) 0);
    }

    /**
     * v4.1 sprite bank select encoding:
     *   bank = [ATTR bits7..6 | DATA bits15..14]  (4-bit bank number 0..15)
     *   offset = DATA bits13..0                   (14-bit offset within 16K bank)
     *
     * palIdx here is the old "subpalette index" 0..15 (each 16 colors),
     * so convert to absolute palette base: palBase = palIdx * 16.
     *
     * pat is an OFFSET inside BANK_SPRITES0.
     */
    private void setSpriteOAM(int si, int x, int y, int w, int h, int patOfs,
                              int attrBase, int palIdx, int pri, int xstep, int ystep) {

        int bank = BANK_SPRITES0 & 0x0F;
        int bankLo = bank & 0x03;         // DATA[15..14]
        int bankHi = (bank >>> 2) & 0x03; // ATTR[7..6]

        int attr = (attrBase & 0x3F) | (bankHi << 6);
        int dataWord = (bankLo << 14) | (patOfs & 0x3FFF);

        int palBaseAbs = (palIdx & 0x0F) << 4;

        oamWord(si, 0, x);
        oamWord(si, 2, y);
        oamByte(si, 4, w);
        oamByte(si, 5, h);
        oamWord(si, 6, dataWord);
        oamByte(si, 8, attr);
        oamByte(si, 9, palBaseAbs);
        oamByte(si, 10, 0);      // colId (unused)
        oamByte(si, 11, pri);
        oamWord(si, 12, xstep);
        oamWord(si, 14, ystep);
    }

    private void disableSprite(int si) { oamByte(si, 8, 0); }

    private void setGroup(int gi, int ps, int pc, int pal, int sx, int sy, int pri, int fl, int bpo) {
        int b = GROUP_BASE + gi * 0x10;
        mmio(b, ps);
        mmio(b + 1, pc);
        mmio(b + 2, pal);
        mmio(b + 3, sx & 0xFF);
        mmio(b + 4, (sx >>> 8) & 0xFF);
        mmio(b + 5, sy & 0xFF);
        mmio(b + 6, pri);
        mmio(b + 7, fl);
        mmio(b + 8, bpo & 0xFF);
        mmio(b + 9, (bpo >>> 8) & 0xFF);
    }

    private void setGroupScroll(int gi, int sx, int sy) {
        int b = GROUP_BASE + gi * 0x10;
        mmio(b + 3, sx & 0xFF);
        mmio(b + 4, (sx >>> 8) & 0xFF);
        mmio(b + 5, sy & 0xFF);
    }

    private void putChar(int row, int col, int ch, int attr) {
        int ofs = TEXT_BASE + (row * 80 + col) * 2;
        mmio(ofs, ch & 0xFF);
        mmio(ofs + 1, attr & 0xFF);
    }

    private void putStr(int row, int col, String s, int attr) {
        for (int i = 0; i < s.length() && col + i < 80; i++) putChar(row, col + i, s.charAt(i), attr);
    }

    private void clearText() { mmio(TX_CMD, 0x04); }

    // =====================================================================
    //  Color utils
    // =====================================================================

    private static int toRgb565(int c) {
        return ((c >> 16 & 0xF8) << 8) | ((c >> 8 & 0xFC) << 3) | ((c & 0xF8) >> 3);
    }

    private static int lerpRgb(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = (int) ((a >> 8 & 0xFF)  + ((b >> 8 & 0xFF)  - (a >> 8 & 0xFF))  * t);
        int bl = (int) ((a & 0xFF)      + ((b & 0xFF)       - (a & 0xFF))       * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int blendRgb(int a, int b, int alpha) {
        int ia = 255 - alpha;
        return (((a >> 16 & 0xFF) * ia + (b >> 16 & 0xFF) * alpha) / 255 << 16)
                | (((a >> 8 & 0xFF) * ia + (b >> 8 & 0xFF) * alpha) / 255 << 8)
                | (((a & 0xFF) * ia + (b & 0xFF) * alpha) / 255);
    }

    private static long lcg(long s) { return s * 6364136223846793005L + 1442695040888963407L; }
}