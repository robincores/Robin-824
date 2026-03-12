package io.github.robincores.r8.device;

import io.github.robincores.r8.system.Tickable;

/**
 * VPU contract exposed to the system bus.
 *
 * <p>The CPU can access the VPU through the 16K VideoWindow (0xC000–0xFFFF).
 * Depending on SysMMIO.VIDWIN.WIN_MMIO, the window maps either:
 * <ul>
 *   <li>MMIO space via {@link #readMmio(int)} / {@link #writeMmio(int, byte)}</li>
 *   <li>banked 16K memory via {@link #readVramPlane(int, int)} / {@link #writeVramPlane(int, int, byte)}</li>
 * </ul>
 *
 * <p>Banks are 16K each (0..15). Your recommended map:
 * <ul>
 *   <li>0x0: reserved / text overlay (banked view)</li>
 *   <li>0x1..0x8: bitplane framebuffer banks</li>
 *   <li>0x9: copper program + tables/lists</li>
 *   <li>0xA..0xF: sprite data banks</li>
 * </ul>
 */
public interface VPU extends Tickable {

    /** Read from VPU MMIO space (0x0000..0x3FFF when VideoWindow is in MMIO mode). */
    byte readMmio(int offset);

    /** Write to VPU MMIO space (0x0000..0x3FFF when VideoWindow is in MMIO mode). */
    void writeMmio(int offset, byte value);

    /** Read a byte from the selected 16K bank when VideoWindow is in banked mode. */
    byte readVramPlane(int bank, int offset);

    /** Write a byte to the selected 16K bank when VideoWindow is in banked mode. */
    void writeVramPlane(int bank, int offset, byte value);

    /** Present any queued frame to JavaFX (called on FX thread). */
    void fxPulse();

    /** Optional convenience. */
    default void reset() { /* no-op */ }

    /** Optional convenience. */
    default void reset(boolean hard) { /* no-op */ }
}