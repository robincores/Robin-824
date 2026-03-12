package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;

import static org.junit.jupiter.api.Assertions.*;

abstract class R816TestSupport {

    static final int TRAP_ILLEGAL_INSN = 0x02;
    static final int TRAP_EBREAK = 0x03;
    static final int TRAP_ECALL = 0x08;
    static final int TRAP_DIV_ZERO = 0x18;

    static final int CSR_MSTATUS = 0x00;
    static final int CSR_MIE = 0x01;
    static final int CSR_MIP = 0x02;
    static final int CSR_MTVEC = 0x03;
    static final int CSR_MEPC = 0x04;
    static final int CSR_MCAUSE = 0x05;
    static final int CSR_MTVAL = 0x06;
    static final int CSR_MEXT = 0x07;

    static final int MSTATUS_MIE = 1 << 0;
    static final int MSTATUS_MPIE = 1 << 1;

    static final class RamBus implements Bus {
        final byte[] mem;

        RamBus(int size) {
            this.mem = new byte[size];
        }

        @Override
        public byte read8(int address) {
            return mem[address & (mem.length - 1)];
        }

        @Override
        public void write8(int address, byte value) {
            mem[address & (mem.length - 1)] = value;
        }

        void load(int addr, int... bytes) {
            for (int i = 0; i < bytes.length; i++) {
                mem[(addr + i) & (mem.length - 1)] = (byte) bytes[i];
            }
        }

        void writeWord16(int addr, int value) {
            write8(addr, (byte) value);
            write8(addr + 1, (byte) (value >>> 8));
        }

        int readWord16(int addr) {
            int lo = Byte.toUnsignedInt(read8(addr));
            int hi = Byte.toUnsignedInt(read8(addr + 1));
            return lo | (hi << 8);
        }

        byte[] slice(int addr, int len) {
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) {
                out[i] = read8(addr + i);
            }
            return out;
        }
    }

    static R816 cpu(RamBus bus, int... program) {
        bus.load(0, program);
        return new R816(bus);
    }

    static R816 cpu(int... program) {
        RamBus bus = new RamBus(1 << 16);
        return cpu(bus, program);
    }

    static R816 cpuWithBlk(boolean hasBlk, RamBus bus, int... program) {
        bus.load(0, program);
        return new R816(bus, hasBlk);
    }

    static R816 cpuWithBlk(boolean hasBlk, int... program) {
        RamBus bus = new RamBus(1 << 16);
        return cpuWithBlk(hasBlk, bus, program);
    }

    static R816 cpuWithMode(boolean hasBlk, R8Core.MicroFusionMode mode, RamBus bus, int... program) {
        bus.load(0, program);
        return new R816(bus, hasBlk, mode);
    }

    static R816 cpuWithMode(boolean hasBlk, R8Core.MicroFusionMode mode, int... program) {
        RamBus bus = new RamBus(1 << 16);
        return cpuWithMode(hasBlk, mode, bus, program);
    }

    static void step(R816 cpu, int steps) {
        for (int i = 0; i < steps; i++) {
            cpu.executeInstruction();
        }
    }

    static int ldlOpcode(int index) {
        return 0x03 | ((index & 0x0F) << 2);
    }

    static int stlOpcode(int index) {
        return 0x83 | ((index & 0x0F) << 2);
    }

    static void assertTrap(R816 cpu, int cause, int epc, int tval) {
        assertFalse(cpu.mcauseIsInterrupt(), "expected synchronous trap");
        assertEquals(cause, cpu.mcauseCode(), "trap cause");
        assertEquals(epc & 0xFFFF, cpu.mepc(), "mepc");
        assertEquals(tval & 0xFFFF, cpu.mtval(), "mtval");
    }

    static void assertInterrupt(R816 cpu, int cause, int epc) {
        assertTrue(cpu.mcauseIsInterrupt(), "expected interrupt");
        assertEquals(cause, cpu.mcauseCode(), "interrupt cause");
        assertEquals(epc & 0xFFFF, cpu.mepc(), "mepc");
    }
}
