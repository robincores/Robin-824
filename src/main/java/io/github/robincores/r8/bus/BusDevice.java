package io.github.robincores.r8.bus;

/**
 * Anything mapped on the bus: RAM, ROM, MMIO devices.
 */
public interface BusDevice {

    byte read(int offset);

    void write(int offset, byte value);

    int size();
}
