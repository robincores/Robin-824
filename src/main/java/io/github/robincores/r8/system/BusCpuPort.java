package io.github.robincores.r8.system;

import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.cpu.CpuPort;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Minimal Bus-backed CpuPort.
 *
 * <p>This is deliberately small and boring:</p>
 * <ul>
 *   <li>No public frontend/cache package.</li>
 *   <li>No speculative framework objects.</li>
 *   <li>Only a tiny private one-byte peek cache for fusion lookahead.</li>
 * </ul>
 *
 * <p>Instruction-side and data-side timing are charged here, not in the CPU core.</p>
 */
public final class BusCpuPort implements CpuPort {
    private final Bus bus;
    private final int addrMask;
    private final int wordBytes;
    private final IntConsumer cycleSink;

    // Tiny private lookahead cache for ipeek8()/ifetch8() cooperation.
    private boolean peekValid;
    private int peekAddr;
    private int peekValue;

    public BusCpuPort(Bus bus, int addrMask, int wordBytes, IntConsumer cycleSink) {
        if (wordBytes != 2 && wordBytes != 3 && wordBytes != 4) {
            throw new IllegalArgumentException("wordBytes must be 2, 3, or 4. Got: " + wordBytes);
        }
        this.bus = Objects.requireNonNull(bus, "bus");
        this.addrMask = addrMask;
        this.wordBytes = wordBytes;
        this.cycleSink = Objects.requireNonNull(cycleSink, "cycleSink");
    }

    @Override
    public int ipeek8(int address) {
        int a = mask(address);
        if (peekValid && peekAddr == a) return peekValue;
        int v = Byte.toUnsignedInt(bus.read8(a));
        peekValid = true;
        peekAddr = a;
        peekValue = v;
        return v;
    }

    @Override
    public int ifetch8(int address) {
        int a = mask(address);
        final int v;
        if (peekValid && peekAddr == a) {
            v = peekValue;
        } else {
            v = Byte.toUnsignedInt(bus.read8(a));
        }
        peekValid = false;     // consume / invalidate lookahead cooperation
        cycleSink.accept(1);   // shell owns I-side fetch timing
        return v;
    }

    @Override
    public int dreadB(int address) {
        peekValid = false;
        int v = Byte.toUnsignedInt(bus.read8(mask(address)));
        cycleSink.accept(1);
        return v;
    }

    @Override
    public int dreadHW(int address) {
        int a = mask(address);
        int lo = dreadB(a);
        int hi = dreadB(a + 1);
        return (hi << 8) | lo;
    }

    @Override
    public int dreadW(int address) {
        int a = mask(address);
        int v = 0;
        for (int i = 0; i < wordBytes; i++) {
            v |= dreadB(a + i) << (8 * i);
        }
        return v;
    }

    @Override
    public void dwriteB(int address, int value) {
        peekValid = false;
        bus.write8(mask(address), (byte) value);
        cycleSink.accept(1);
    }

    @Override
    public void dwriteHW(int address, int value) {
        int a = mask(address);
        int v = value & 0xFFFF;
        dwriteB(a, v);
        dwriteB(a + 1, v >>> 8);
    }

    @Override
    public void dwriteW(int address, int value) {
        int a = mask(address);
        for (int i = 0; i < wordBytes; i++) {
            dwriteB(a + i, value >>> (8 * i));
        }
    }

    @Override
    public void idle(int cycles) {
        if (cycles < 0) throw new IllegalArgumentException("cycles must be >= 0");
        if (cycles != 0) cycleSink.accept(cycles);
    }

    private int mask(int address) {
        return address & addrMask;
    }
}
