package io.github.robincores.r8.bus;

/**
 * Byte-addressable system bus as seen by the CPU.
 * All RAM/ROM/MMIO access flows through this interface.
 */
public interface Bus {

    /** Read an 8-bit value from the given bus address. */
    byte read8(int address);

    /** Write an 8-bit value to the given bus address. */
    void write8(int address, byte value);
}
