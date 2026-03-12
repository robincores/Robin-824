package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816EdgeCaseTest extends R816TestSupport {

    @Test
    void divideAndRemainderByZeroTrapPreciselyForAllVariants() {
        int[] ops = {0x2C, 0x30, 0xAC, 0xB0}; // DIV, REM, DIVU, REMU
        for (int op : ops) {
            R816 cpu = cpu(op);
            cpu.mtvec = 0x0100;
            cpu.setAReg(0);
            cpu.setBReg(0x1234);
            cpu.setCReg(0x5678);

            cpu.executeInstruction();

            assertTrap(cpu, TRAP_DIV_ZERO, 0x0000, 0);
            assertEquals(0x0100, cpu.ip(), "should vector to trap handler");
        }
    }

    @Test
    void signedAndUnsignedDivisionRespectR816WordSemanticsAtTheEdges() {
        R816 cpu = cpu(0x2C, 0x30, 0xAC, 0xB0);

        cpu.setAReg(0xFFFF);   // -1
        cpu.setBReg(0x8000);   // -32768
        cpu.setCReg(0x1111);
        cpu.executeInstruction();
        assertEquals(0x8000, cpu.a() & 0xFFFF, "-32768 / -1 wraps back into 16-bit word");
        assertEquals(0x1111, cpu.b() & 0xFFFF);

        cpu.setAReg(0xFFFF);   // -1
        cpu.setBReg(0x8000);   // -32768
        cpu.setCReg(0x2222);
        cpu.executeInstruction();
        assertEquals(0x0000, cpu.a() & 0xFFFF, "remainder by -1 should be zero");
        assertEquals(0x2222, cpu.b() & 0xFFFF);

        cpu.setAReg(0x00FF);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(0x3333);
        cpu.executeInstruction();
        assertEquals(0x0101, cpu.a() & 0xFFFF, "65535 / 255 = 257");
        assertEquals(0x3333, cpu.b() & 0xFFFF);

        cpu.setAReg(0x0100);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(0x4444);
        cpu.executeInstruction();
        assertEquals(0x00FF, cpu.a() & 0xFFFF, "65535 % 256 = 255");
        assertEquals(0x4444, cpu.b() & 0xFFFF);
    }

    @Test
    void signedAndUnsignedComparisonsDisagreeOnHighBitValues() {
        R816 cpu = cpu(0x6C, 0x70); // SLT, SLTU

        cpu.setAReg(0x0000);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(0xAAAA);
        cpu.executeInstruction();
        assertEquals(1, cpu.a(), "-1 < 0 for signed compare");
        assertEquals(0xAAAA, cpu.b() & 0xFFFF);

        cpu.setAReg(0x0000);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(0xBBBB);
        cpu.executeInstruction();
        assertEquals(0, cpu.a(), "65535 < 0 is false for unsigned compare");
        assertEquals(0xBBBB, cpu.b() & 0xFFFF);
    }

    @Test
    void shiftAmountsAreMaskedToTheLowFourBitsOnR816() {
        R816 cpu = cpu(0x60, 0x64, 0x68); // SHL, SHR, SAR

        cpu.setAReg(16);
        cpu.setBReg(0x0001);
        cpu.setCReg(0x1111);
        cpu.executeInstruction();
        assertEquals(0x0001, cpu.a() & 0xFFFF, "shift by 16 behaves like shift by 0");
        assertEquals(0x1111, cpu.b() & 0xFFFF);

        cpu.setAReg(17);
        cpu.setBReg(0x8000);
        cpu.setCReg(0x2222);
        cpu.executeInstruction();
        assertEquals(0x4000, cpu.a() & 0xFFFF, "logical shift right by 17 behaves like shift by 1");
        assertEquals(0x2222, cpu.b() & 0xFFFF);

        cpu.setAReg(17);
        cpu.setBReg(0x8000);
        cpu.setCReg(0x3333);
        cpu.executeInstruction();
        assertEquals(0xC000, cpu.a() & 0xFFFF, "arithmetic shift right by 17 behaves like shift by 1");
        assertEquals(0x3333, cpu.b() & 0xFFFF);
    }

    @Test
    void clzUsesTheFullSixteenBitWordWidth() {
        R816 cpu = cpu(0x90, 0x90, 0x90); // CLZ x3

        cpu.setAReg(0x8000);
        cpu.executeInstruction();
        assertEquals(0, cpu.a(), "highest bit set has zero leading zeros");

        cpu.setAReg(0x0001);
        cpu.executeInstruction();
        assertEquals(15, cpu.a(), "lowest bit set has fifteen leading zeros in 16 bits");

        cpu.setAReg(0x0000);
        cpu.executeInstruction();
        assertEquals(16, cpu.a(), "CLZ(0) returns WORD_BITS");
    }
}
