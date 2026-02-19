package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;

public final class RAM implements BusDevice {
    private final byte[] data;

    public RAM(int size) {
        this.data = new byte[size];
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
        data[offset] = value;
    }

    public void load(int offset, byte[] blob) {
        System.arraycopy(blob, 0, data, offset, blob.length);
    }
}
