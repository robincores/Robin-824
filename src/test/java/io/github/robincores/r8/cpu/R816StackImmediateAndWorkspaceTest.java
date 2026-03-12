package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816StackImmediateAndWorkspaceTest extends R816TestSupport {

    @Test
    void configMatchesR816Shape() {
        R816 cpu = cpu(0x00);
        assertEquals(2, cpu.wordBytes());
        assertEquals(0xFFFF, cpu.addrMask());
        assertEquals(0xFFFF, cpu.wordMask());
        assertEquals(0x8000, cpu.wordSignBit());
    }

    @Test
    void signedUnsignedAndWordImmediatesUseExpectedExtensionRules() {
        R816 cpu = cpu(
                0x02, 0xFF,       // B -1
                0x06, 0xFF,       // U 255
                0x43, 0xFF, 0xFF  // I -1
        );

        cpu.executeInstruction();
        assertEquals(-1, cpu.a());

        cpu.executeInstruction();
        assertEquals(0x00FF, cpu.a() & 0xFFFF);
        assertEquals(-1, cpu.b());

        cpu.executeInstruction();
        assertEquals(-1, cpu.a());
        assertEquals(0x00FF, cpu.b() & 0xFFFF);
    }

    @Test
    void aiipUsesPostImmediateIp() {
        R816 cpu = cpu(0x47, 0x04, 0x00); // AIIP +4
        cpu.executeInstruction();
        assertEquals(0x0007, cpu.a() & 0xFFFF);
        assertEquals(0x0003, cpu.ip());
    }

    @Test
    void fixedWorkspaceLoadAndStoreCoverAllSixteenShortForms() {
        for (int i = 0; i < 16; i++) {
            RamBus bus = new RamBus(1 << 16);
            R816 cpu = cpu(bus, ldlOpcode(i), stlOpcode(i));
            cpu.wksp[i] = 0x1000 + i;
            cpu.setAReg(0xAAAA);
            cpu.setBReg(0xBBBB);
            cpu.setCReg(0xCCCC);

            cpu.executeInstruction();
            assertEquals(0x1000 + i, cpu.a() & 0xFFFF, "ldl index " + i);
            assertEquals(0xAAAA, cpu.b() & 0xFFFF, "ldl shifts prior A into B");
            assertEquals(0xBBBB, cpu.c() & 0xFFFF, "ldl shifts prior B into C");

            cpu.setAReg(0x2000 + i);
            cpu.setBReg(0x3000 + i);
            cpu.setCReg(0x4000 + i);
            cpu.executeInstruction();
            assertEquals(0x2000 + i, cpu.wksp[i] & 0xFFFF, "stl index " + i);
            assertEquals(0x3000 + i, cpu.a() & 0xFFFF);
            assertEquals(0x4000 + i, cpu.b() & 0xFFFF);
        }
    }

    @Test
    void extendedWorkspaceLoadAndStoreUseImm8Index() {
        R816 cpu = cpu(
                0x7B, 0xA5, // LDLX 0xA5
                0xFB, 0xBC  // STLX 0xBC
        );
        cpu.wksp[0xA5] = 0x1357;
        cpu.setAReg(0xAAAA);
        cpu.setBReg(0xBBBB);
        cpu.setCReg(0xCCCC);

        cpu.executeInstruction();
        assertEquals(0x1357, cpu.a() & 0xFFFF);
        assertEquals(0xAAAA, cpu.b() & 0xFFFF);
        assertEquals(0xBBBB, cpu.c() & 0xFFFF);

        cpu.setAReg(0x2468);
        cpu.setBReg(0xABCD);
        cpu.setCReg(0xEE00);
        cpu.executeInstruction();
        assertEquals(0x2468, cpu.wksp[0xBC] & 0xFFFF);
        assertEquals(0xABCD, cpu.a() & 0xFFFF);
        assertEquals(0xEE00, cpu.b() & 0xFFFF);
    }

    @Test
    void stackCacheOpsBehaveLikeDocumented() {
        R816 cpu = cpu(
                0x04, // DUP
                0x08, // SWAP
                0x0C, // DROP1
                0x10, // DROP2
                0x14  // I2B
        );
        cpu.setAReg(0x0011);
        cpu.setBReg(0x0022);
        cpu.setCReg(0x0033);

        cpu.executeInstruction();
        assertEquals(0x0011, cpu.a() & 0xFFFF);
        assertEquals(0x0011, cpu.b() & 0xFFFF);
        assertEquals(0x0022, cpu.c() & 0xFFFF);

        cpu.executeInstruction();
        assertEquals(0x0011, cpu.a() & 0xFFFF);
        assertEquals(0x0011, cpu.b() & 0xFFFF);

        cpu.executeInstruction();
        assertEquals(0x0011, cpu.a() & 0xFFFF);
        assertEquals(0x0022, cpu.b() & 0xFFFF);

        cpu.executeInstruction();
        assertEquals(0x0022, cpu.a() & 0xFFFF);
        assertEquals(0x0000, cpu.b() & 0xFFFF);
        assertEquals(0x0000, cpu.c() & 0xFFFF);

        cpu.setAReg(0x00F2);
        cpu.executeInstruction();
        assertEquals((short) (byte) 0xF2, cpu.a());
        assertEquals(0xFFF2, cpu.a() & 0xFFFF);
    }
}
