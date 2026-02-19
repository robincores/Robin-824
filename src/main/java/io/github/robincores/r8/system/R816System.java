package io.github.robincores.r8.system;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R8Core;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.r8.device.*;
import javafx.scene.canvas.Canvas;

import static io.github.robincores.r8.cpu.R8Core.EXTERNAL_INTERRUPT_MASK;
import static io.github.robincores.r8.cpu.R8Core.TIMER_INTERRUPT_MASK;

/**
 * R816 System — 16-bit address space (64KB), CPC-style video window.
 *
 * <h3>Memory Map (v1)</h3>
 * <pre>
 *   0x0000–0xBEFF   RAM                    (48K - 256 bytes)
 *   0xBF00-0xBFFF   MMIO                   (256 bytes)
 *      0xBFE0–0xBFEF   PIT timer MMIO         (16 bytes)
 *      0xBFF0–0xBFFF   System MMIO            (16 bytes)  [VIDWIN, ...]
 *   0xC000–0xFFFF   Video Window           (16K)       [VRAM plane or VPU MMIO]
 * </pre>
 *
 * <h3>Video</h3>
 * <ul>
 *   <li>VRAM is 64K total (4 planes × 16K).</li>
 *   <li>Planes can act as <i>bitplanes</i> (4bpp / 16 colors) or <i>Mode X byte-planes</i>
 *       (8bpp / 256 colors, where {@code x mod 4} selects the plane).</li>
 *   <li>The CPU sees a 16K window at 0xC000–0xFFFF; it can map any plane (0..3)
 *       or switch the window into VPU MMIO view via {@code VIDWIN}.</li>
 *   <li>Display output is 640×400 in a JavaFX window (no smoothing).</li>
 * </ul>
 */
public final class R816System extends AbstractSystem {

    /**
     * Target CPU frequency for pacing (emulator): 12.5 MHz.
     */
    public static final long CPU_HZ = 12_500_000L;

    /**
     * VGA-ish 640×400 @ ~70Hz model:
     * - total scanlines = 449
     * - vblank starts at 400
     * - with CPU 12.5MHz and cyclesPerScanline=400 => ~69.6Hz
     */
    private static final DisplayConfig DISPLAY_CONFIG = new DisplayConfig(
            640, 400,
            640, 400,
            400,
            449,
            400
    );

    // Memory map constants
    private static final int RAM_BASE = 0x0000;
    private static final int RAM_SIZE = 0xBF00;  // 0x0000..0xBEFF

    private static final int PIT_BASE = 0xBFE0;  // 0xBFE0–0xBFEF
    private static final int SYS_MMIO_BASE = 0xBFF0;  // 0xBFF0–0xBFFF
    private static final int VIDEO_WIN_BASE = 0xC000;  // 0xC000–0xFFFF

    public R816System(Canvas canvas) {
        BusMap bus = new BusMap(0xFFFF);
        R816 cpu = new R816(bus);

        // Pace the emulator to a "real" 12.5MHz CPU.
        setCpuHz(CPU_HZ);

        // --- RAM: 0x0000–0xBEFF (48K - 256 bytes)
        bus.map(RAM_BASE, new RAM(RAM_SIZE));

        // --- PIT: 0xBFE0–0xBFEF
        int mtimeBits = 32;
        int cyclesPerTick = 1; // MTIME increments every CPU cycle
        PIT pit = new PIT(cpu, TIMER_INTERRUPT_MASK, mtimeBits, cyclesPerTick);
        bus.map(PIT_BASE, pit);

        // --- System MMIO: 0xBFF0–0xBFFF
        SysMMIO sys = new SysMMIO();
        bus.map(SYS_MMIO_BASE, sys);

        // --- VPU (not directly mapped; exposed via VideoWindow when WIN_MMIO=1)
        VPU vpu = new VPU(
                DISPLAY_CONFIG,
                cpu,
                EXTERNAL_INTERRUPT_MASK,
                canvas
        );

        // --- Video window: 0xC000–0xFFFF (16K)
        bus.map(VIDEO_WIN_BASE, new VideoWindow(sys, vpu));

        init(cpu, bus);
        addTickable(pit);
        addTickable(vpu);
    }

    /**
     * Returns the display config for window sizing.
     */
    public static DisplayConfig displayConfig() {
        return DISPLAY_CONFIG;
    }
}