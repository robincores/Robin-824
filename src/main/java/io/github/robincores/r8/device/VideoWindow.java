package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;

/**
 * 16K Video Window at {@code 0xC000–0xFFFF}.
 *
 * <p>Depending on {@link SysMMIO#winMmio()}, this window either:
 * <ul>
 *   <li>exposes one of four 16K VRAM planes (selected by {@link SysMMIO#vbank()}), or</li>
 *   <li>exposes the VPU MMIO page (registers + palette RAM).</li>
 * </ul>
 */
public final class VideoWindow implements BusDevice {

    public static final int SIZE = 0x4000;

    private final SysMMIO sys;
    private final VPU vpu;

    public VideoWindow(SysMMIO sys, VPU vpu) {
        this.sys = sys;
        this.vpu = vpu;
    }

    @Override
    public byte read(int offset) {
        if (sys.winMmio()) {
            return vpu.readMmio(offset);
        }
        int plane = sys.vbank();
        return vpu.readVramPlane(plane, offset);
    }

    @Override
    public void write(int offset, byte value) {
        if (sys.winMmio()) {
            vpu.writeMmio(offset, value);
            return;
        }
        int plane = sys.vbank();
        vpu.writeVramPlane(plane, offset, value);
    }

    @Override
    public int size() {
        return SIZE;
    }
}
