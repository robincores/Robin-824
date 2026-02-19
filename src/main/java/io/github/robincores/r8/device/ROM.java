package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;

public final class ROM implements BusDevice {
    private final byte[] data;

    public ROM(byte[] data) {
        this.data = data.clone();
    }

    @Override
    public int size() {
        return data.length;
    }

    @Override
    public byte read(int offset) {
        return data[offset];
    }

    @Override
    public void write(int offset, byte value) {
        // ignored (read-only)
    }
}
