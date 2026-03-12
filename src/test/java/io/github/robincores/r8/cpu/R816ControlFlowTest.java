package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816ControlFlowTest extends R816TestSupport {

    @Test
    void beqFamilyConsumesAandBAndUsesSignedRelativeOffsets() {
        int[] opcodes = {0x22, 0x26, 0x2A, 0x2E, 0x32, 0x36};

        int[][] takenCases = {
                {7, 7},           // BEQ
                {7, 8},           // BNE
                {-2, 1},          // BLT
                {1, 0xFFFF},      // BLTU (1 < 65535)
                {7, 7},           // BGE
                {0xFFFF, 1}       // BGEU
        };

        for (int i = 0; i < opcodes.length; i++) {
            R816 cpu = cpu(opcodes[i], 0x02);
            cpu.setAReg(takenCases[i][1]);
            cpu.setBReg(takenCases[i][0]);
            cpu.setCReg(0x5555);
            cpu.executeInstruction();
            assertEquals(0x0004, cpu.ip(), "taken branch opcode " + Integer.toHexString(opcodes[i]));
            assertEquals(0x5555, cpu.a() & 0xFFFF);
            assertEquals(0, cpu.b());
            assertEquals(0, cpu.c());
        }
    }

    @Test
    void beqzAndBnezConsumeOnlyA() {
        R816 beqz = cpu(0x3A, 0x02);
        beqz.setAReg(0);
        beqz.setBReg(0x1111);
        beqz.setCReg(0x2222);
        beqz.executeInstruction();
        assertEquals(0x0004, beqz.ip());
        assertEquals(0x1111, beqz.a() & 0xFFFF);
        assertEquals(0x2222, beqz.b() & 0xFFFF);

        R816 bnez = cpu(0x3E, 0xFE);
        bnez.setIPtr(0x0010);
        ((RamBus) bnez.bus).load(0x0010, 0x3E, 0xFE);
        bnez.setAReg(1);
        bnez.setBReg(0xAAAA);
        bnez.setCReg(0xBBBB);
        bnez.executeInstruction();
        assertEquals(0x0010, bnez.ip(), "-2 offset returns to same instruction address");
        assertEquals(0xAAAA, bnez.a() & 0xFFFF);
        assertEquals(0xBBBB, bnez.b() & 0xFFFF);
    }

    @Test
    void braSupportsNegativeRelativeLoops() {
        RamBus bus = new RamBus(1 << 16);
        R816 cpu = cpu(bus);
        bus.load(0x0100, 0xA2, 0xFE); // BRA -2
        cpu.setIPtr(0x0100);
        cpu.executeInstruction();
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void jUsesSignedWordRelativeOffset() {
        RamBus bus = new RamBus(1 << 16);
        R816 cpu = cpu(bus);
        bus.load(0x0100, 0x73, 0xFD, 0xFF); // J -3
        cpu.setIPtr(0x0100);
        cpu.executeInstruction();
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void jalPushesReturnAddressThenBranches() {
        R816 cpu = cpu(0x77, 0x02, 0x00);
        cpu.setAReg(0x1111);
        cpu.setBReg(0x2222);
        cpu.executeInstruction();
        assertEquals(0x0005, cpu.ip());
        assertEquals(0x0003, cpu.a(), "return address is post-immediate IP");
        assertEquals(0x1111, cpu.b() & 0xFFFF);
        assertEquals(0x2222, cpu.c() & 0xFFFF);
    }

    @Test
    void jrAndJalrUseRegisterTargets() {
        R816 jr = cpu(0x42);
        jr.setAReg(0x1234);
        jr.setBReg(0xAAAA);
        jr.setCReg(0xBBBB);
        jr.executeInstruction();
        assertEquals(0x1234, jr.ip());
        assertEquals(0xAAAA, jr.a() & 0xFFFF);
        assertEquals(0xBBBB, jr.b() & 0xFFFF);

        R816 jalr = cpu(0x46);
        jalr.setAReg(0x2345);
        jalr.setBReg(0xAAAA);
        jalr.setCReg(0xBBBB);
        jalr.executeInstruction();
        assertEquals(0x2345, jalr.ip());
        assertEquals(0x0001, jalr.a(), "return address is post-opcode IP");
        assertEquals(0xAAAA, jalr.b() & 0xFFFF);
        assertEquals(0xBBBB, jalr.c() & 0xFFFF);
    }
}
