package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;

/**
 * System MMIO page (v1).
 *
 * <p>Mapped at {@code 0xBFF0–0xBFFF} (16 bytes). Currently exposes a single
 * register {@code VIDWIN} at offset {@code 0x00}:
 *
 * <ul>
 *   <li>bits 0..1: {@code VBANK} — selects which VRAM plane (0..3) is visible through
 *       the {@code 0xC000–0xFFFF} video window when in VRAM view.</li>
 *   <li>bit 2: {@code WIN_MMIO} — when 1, the video window exposes VPU MMIO instead of VRAM.</li>
 * </ul>
 */
public final class SysMMIO implements BusDevice {

    public static final int SIZE = 0x10;

    private int vidwin;

    /** Raw VIDWIN register value (0..255). */
    public int vidwin() {
        return vidwin & 0xFF;
    }

    /** Selected VRAM plane (0..3). */
    public int vbank() {
        return vidwin & 0x03;
    }

    /** True when the video window exposes VPU MMIO rather than VRAM planes. */
    public boolean winMmio() {
        return (vidwin & 0x04) != 0;
    }

    @Override
    public byte read(int offset) {
        if (offset == 0x00) return (byte) vidwin();
        return 0;
    }

    @Override
    public void write(int offset, byte value) {
        if (offset == 0x00) {
            vidwin = Byte.toUnsignedInt(value);
        }
    }

    @Override
    public int size() {
        return SIZE;
    }
}
