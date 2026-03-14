package io.github.robincores.r8.cpu;

/**
 * Small visual scaffold for the new port-based R8Core direction.
 *
 * <p>This is NOT a second architecture and not a replacement for the real R8Core yet.
 * It only shows the helper shape after moving fetch/data/timing behind CpuPort.</p>
 */
public abstract class R8CoreScaffold {
    protected final CpuPort port;

    protected final int ADDR_MASK;
    protected final int WORD_BYTES;
    protected final int WORD_MASK;

    protected int IPtr;

    protected R8CoreScaffold(CpuPort port, int addrMask, int wordBytes, int wordMask) {
        this.port = port;
        this.ADDR_MASK = addrMask;
        this.WORD_BYTES = wordBytes;
        this.WORD_MASK = wordMask;
    }

    protected final int maskAddr(int address) {
        return address & ADDR_MASK;
    }

    protected final byte readByte(int address) {
        return (byte) port.dreadB(maskAddr(address));
    }

    protected final void writeByte(int address, byte value) {
        port.dwriteB(maskAddr(address), value & 0xFF);
    }

    protected final int readHalf(int address) {
        return port.dreadHW(maskAddr(address)) & 0xFFFF;
    }

    protected final void writeHalf(int address, int value) {
        port.dwriteHW(maskAddr(address), value & 0xFFFF);
    }

    protected final int readWord(int address) {
        return port.dreadW(maskAddr(address)) & WORD_MASK;
    }

    protected final void writeWord(int address, int value) {
        port.dwriteW(maskAddr(address), value & WORD_MASK);
    }

    protected final int fetchOpcode() {
        int op = port.ifetch8(maskAddr(IPtr)) & 0xFF;
        IPtr = maskAddr(IPtr + 1);
        return op;
    }

    protected final int peekNextOpcode() {
        return port.ipeek8(maskAddr(IPtr)) & 0xFF;
    }

    protected final int fetchImm8() {
        int v = port.ifetch8(maskAddr(IPtr)) & 0xFF;
        IPtr = maskAddr(IPtr + 1);
        return v;
    }

    protected final int fetchImmW() {
        int v = 0;
        for (int i = 0; i < WORD_BYTES; i++) {
            v |= fetchImm8() << (8 * i);
        }
        return v & WORD_MASK;
    }

    protected final void decodeCycle() {
        port.idle(1);
    }
}
