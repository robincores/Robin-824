package io.github.robincores.toolchain.r8as;

import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.cpu.R816;

abstract class R816AssemblerTestSupport {

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

        void load(int addr, java.util.List<Integer> bytes) {
            for (int i = 0; i < bytes.size(); i++) {
                write8(addr + i, (byte) (bytes.get(i) & 0xFF));
            }
        }
    }

    static R816 cpuFromState(AssemblerState state) {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0, state.getOutput());
        return new R816(bus);
    }

    static void runUntilHalt(R816 cpu, int maxSteps) {
        for (int i = 0; i < maxSteps && !cpu.isHalted(); i++) {
            cpu.executeInstruction();
        }
    }
}
