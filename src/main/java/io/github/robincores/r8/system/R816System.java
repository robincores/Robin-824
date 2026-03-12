package io.github.robincores.r8.system;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.*;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;
import static io.github.robincores.r8.cpu.R8Core.TIMER_INTERRUPT_MASK;

/**
 * R816 System — 16-bit address space (64KB), CPC-style video window.
 *
 * <h3>Master clock model</h3>
 * <ul>
 *   <li><b>BUS_HZ</b> is the fixed system master clock.</li>
 *   <li>All peripherals (VPU/APU/PIT) advance in <b>BUS cycles</b>.</li>
 *   <li>The CPU may run at an integer multiple of BUS_HZ via {@code cpuMul} without changing HSYNC/VSYNC,
 *       because display timing is derived from BUS cycles.</li>
 * </ul>
 *
 * <h3>Where to configure the platform</h3>
 * All “platform constants” live in this file:
 * <ul>
 *   <li>Clocking: {@link #VGA_PIXEL_HZ}, {@link #BUS_HZ}, {@link #CPU_MUL_DEFAULT}</li>
 *   <li>Display: {@link #DISPLAY_CONFIG}</li>
 *   <li>Timer: PIT tick rate and MTIME width inside the constructor section</li>
 *   <li>Memory map constants: RAM/MMIO base addresses</li>
 * </ul>
 *
 * <h3>Memory Map (v1)</h3>
 * <pre>
 *   0x0000–0xBEFF   RAM                    (48K - 256 bytes)
 *   0xBF00–0xBFFF   MMIO                   (256 bytes)
 *      0xBF00–0xBF0F   Keyboard MMIO          (16 bytes)
 *      0xBF10–0xBF1F   PIT timer MMIO         (16 bytes)
 *      0xBF20–0xBFEF   APU MMIO               (208 bytes)
 *      0xBFF0–0xBFFF   System MMIO            (16 bytes)  [VIDWIN, ...]
 *   0xC000–0xFFFF   Video Window           (16K)       [VRAM plane or VPU MMIO]
 * </pre>
 */
public final class R816System extends AbstractSystem implements FxSystem {

    // ================================================================
    // Easy knobs: clocks + display timing
    // ================================================================

    /** Classic VGA pixel clock (25.175 MHz). */
    public static final long VGA_PIXEL_HZ = 25_175_000L;

    /**
     * Platform BUS clock (master timebase).
     *
     * <p><b>IMPORTANT:</b> The VPU timing model is expressed in <b>BUS cycles</b>
     * (see {@link #DISPLAY_CONFIG}). Therefore, changing {@code BUS_HZ} without
     * adjusting {@code DISPLAY_CONFIG} will change HSYNC/VSYNC and refresh rate.</p>
     *
     * <p><b>Default:</b> BUS_HZ = VGA_PIXEL_HZ / 2 (12.5875 MHz).</p>
     *
     * <p><b>If you set BUS_HZ = VGA_PIXEL_HZ:</b> to keep the same refresh rate you must
     * scale the display timing values expressed in BUS cycles (typically double
     * {@code cyclesPerScanline}).</p>
     *
     * <h4>What must change when BUS_HZ is doubled</h4>
     * <ul>
     *   <li><b>VPU timing:</b> update {@link #DISPLAY_CONFIG} so that
     *       {@code refreshHz = BUS_HZ / (cyclesPerScanline * scanlinesPerFrame)} stays constant.
     *       Typical change: {@code cyclesPerScanline} ×2 (e.g. 400 → 800).</li>
     *
     *   <li><b>APU timing:</b> no configuration change needed as long as APU derives
     *       {@code cyclesPerSample = BUS_HZ / SAMPLE_RATE} and similar internal rates from BUS_HZ.</li>
     *
     *   <li><b>PIT timing:</b> no configuration change needed as long as PIT uses
     *       fixed-point conversion from BUS cycles to {@code tickHz} (as implemented),
     *       and you pass the updated {@code BUS_HZ} into the PIT constructor.</li>
     *
     *   <li><b>CPU baseline speed:</b> with {@code cpuMul=1}, the CPU gets faster if BUS_HZ increases.
     *       Adjust {@code cpuMul} only if you want a different CPU-to-bus ratio.</li>
     * </ul>
     */
    public static final long BUS_HZ = VGA_PIXEL_HZ / 2;

    /** Default CPU multiplier (conceptually cpuHz = BUS_HZ * cpuMul). */
    public static final int CPU_MUL_DEFAULT = 1;

    /**
     * Display timing model expressed in BUS cycles (horizontal) and scanlines (vertical).
     *
     * <p><b>NOTE:</b> These values are in <b>BUS cycles</b>, not “pixels”.
     * If you change {@link #BUS_HZ}, update these so that
     * {@code refreshHz = BUS_HZ / (cyclesPerScanline * scanlinesPerFrame)} stays where you want it.</p>
     *
     * <p>Example: if you change BUS_HZ from VGA_PIXEL_HZ/2 to VGA_PIXEL_HZ (×2),
     * then change {@code cyclesPerScanline} from 400 to 800 to keep the same refresh rate.</p>
     */
    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // ================================================================
    // Memory map constants
    // ================================================================

    private static final int RAM_BASE = 0x0000;
    private static final int RAM_SIZE = 0xBF00;          // 0x0000..0xBEFF

    private static final int KBD_BASE = 0xBF00;          // 0xBF00–0xBF0F (16 bytes)
    private static final int PIT_BASE = 0xBF10;          // 0xBF10–0xBF1F
    private static final int APU_BASE = 0xBF20;          // 0xBF20–0xBFEF
    private static final int SYS_MMIO_BASE = 0xBFF0;     // 0xBFF0–0xBFFF
    private static final int VIDEO_WIN_BASE = 0xC000;    // 0xC000–0xFFFF

    // ================================================================
    // Devices
    // ================================================================

    private final Keyboard keyboard;
    private final VPU vpu;

    public R816System(Canvas canvas) {
        this(canvas, CPU_MUL_DEFAULT);
    }

    public R816System(Canvas canvas, int cpuMul) {
        validateConfig(cpuMul);

        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        // Fixed platform clocks (required).
        setBusHz(BUS_HZ);
        setCpuMul(cpuMul);

        // --- RAM ---
        RAM ram = new RAM(RAM_SIZE);
        bus.map(RAM_BASE, ram);

        // --- Keyboard ---
        keyboard = new Keyboard(cpu, EXTERNAL_INTERRUPT_MASK);
        bus.map(KBD_BASE, keyboard);

        // --- PIT ---
        // MTIME is 1 MHz regardless of BUS_HZ (PIT converts BUS cycles -> tickHz internally).
        final int mtimeBits = 32;
        final long pitTickHz = 1_000_000L;
        PIT pit = new PIT(cpu, TIMER_INTERRUPT_MASK, mtimeBits, BUS_HZ, pitTickHz);
        bus.map(PIT_BASE, pit);

        // --- System MMIO (VIDWIN, etc.) ---
        SysMMIO sys = new SysMMIO();
        bus.map(SYS_MMIO_BASE, sys);

        // --- APU ---
        // APU timing is derived from BUS_HZ (sample/frame sequencers are in BUS cycles).
        APU apu = new APU(ram, BUS_HZ, cpu, EXTERNAL_INTERRUPT_MASK);
        bus.map(APU_BASE, apu);

        // --- VPU + video window ---
        vpu = new VPU_v4_1(DISPLAY_CONFIG, cpu, EXTERNAL_INTERRUPT_MASK, canvas);
        bus.map(VIDEO_WIN_BASE, new VideoWindow(sys, vpu));

        // Wire into AbstractSystem
        init(cpu, bus);
        addTickable(pit);
        addTickable(apu);
        addTickable(vpu);
    }

    private static void validateConfig(int cpuMul) {
        if (BUS_HZ <= 0) throw new IllegalStateException("BUS_HZ must be > 0");
        if (cpuMul < 1 || cpuMul > 64) {
            throw new IllegalArgumentException("cpuMul out of range [1..64]: " + cpuMul);
        }
        if (DISPLAY_CONFIG.cyclesPerScanline() <= 0) {
            throw new IllegalStateException("DISPLAY_CONFIG.cyclesPerScanline must be > 0");
        }
        if (DISPLAY_CONFIG.scanlinesPerFrame() <= 0) {
            throw new IllegalStateException("DISPLAY_CONFIG.scanlinesPerFrame must be > 0");
        }
        if (DISPLAY_CONFIG.vblankStart() < 0 || DISPLAY_CONFIG.vblankStart() > DISPLAY_CONFIG.scanlinesPerFrame()) {
            throw new IllegalStateException("DISPLAY_CONFIG.vblankStart out of range");
        }
    }

    @Override
    public void attach(Scene scene) {
        keyboard.attach(scene);
    }

    @Override
    public void fxPulse() {
        vpu.fxPulse();
    }

    public static DisplayConfig displayConfig() {
        return DISPLAY_CONFIG;
    }

    /** Implied display refresh rate given the platform BUS clock. */
    public static double refreshHz() {
        int cpf = DISPLAY_CONFIG.cyclesPerFrame();
        return (cpf <= 0) ? 0.0 : ((double) BUS_HZ / (double) cpf);
    }
}