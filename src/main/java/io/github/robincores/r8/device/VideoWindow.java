package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;

/**
 * 16K VideoWindow mapped at {@code 0xC000–0xFFFF}.
 *
 * <p>The VideoWindow is a banked 16K aperture into the VPU, controlled by {@link SysMMIO#REG_VIDWIN}:
 * </p>
 * <ul>
 *   <li>If {@link SysMMIO#winMmio()} is {@code true} ({@code VIDWIN.WIN_MMIO=1}),
 *       this window forwards reads/writes to the VPU MMIO space (registers, palette, text/font, etc.).</li>
 *   <li>If {@link SysMMIO#winMmio()} is {@code false} ({@code VIDWIN.WIN_MMIO=0}),
 *       this window forwards reads/writes to the selected 16K bank {@code 0..15}
 *       (selected by {@link SysMMIO#vbank()} / {@code VIDWIN.VBANK}).</li>
 * </ul>
 *
 * <p><b>Note:</b> Banks are general-purpose 16K windows (e.g., planes, copper/tables, sprites),
 * per your bank map.</p>
 */
public final class VideoWindow implements BusDevice {

    /** Size of the VideoWindow mapping (16 KiB). */
    public static final int SIZE = 0x4000;

    private final SysMMIO sys;
    private final VPU vpu;

    public VideoWindow(SysMMIO sys, VPU vpu) {
        this.sys = sys;
        this.vpu = vpu;
    }

    @Override
    public byte read(int offset) {
        // MMIO view: expose VPU registers/palette/text/etc.
        if (sys.winMmio()) {
            return vpu.readMmio(offset);
        }

        // Banked view: expose the selected 16K bank.
        int bank = sys.vbank();
        return vpu.readVramPlane(bank, offset);
    }

    @Override
    public void write(int offset, byte value) {
        // MMIO view: forward to VPU registers/palette/text/etc.
        if (sys.winMmio()) {
            vpu.writeMmio(offset, value);
            return;
        }

        // Banked view: forward to the selected 16K bank.
        int bank = sys.vbank();
        vpu.writeVramPlane(bank, offset, value);
    }

    @Override
    public int size() {
        return SIZE;
    }
}