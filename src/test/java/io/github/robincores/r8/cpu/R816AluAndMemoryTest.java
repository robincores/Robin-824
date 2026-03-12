package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816AluAndMemoryTest extends R816TestSupport {

    @Test
    void nativeWordLoadStoreAreLittleEndian() {
        RamBus bus = new RamBus(1 << 16);
        R816 cpu = cpu(bus,
                0x02, 0x10, // B 0x10 addr
                0x43, 0x34, 0x12, // I 0x1234 value
                0x21,       // ST
                0x02, 0x10, // B 0x10
                0x01        // LD
        );

        step(cpu, 3);
        assertEquals(0x34, Byte.toUnsignedInt(bus.read8(0x0010)));
        assertEquals(0x12, Byte.toUnsignedInt(bus.read8(0x0011)));

        step(cpu, 2);
        assertEquals(0x1234, cpu.a() & 0xFFFF);
    }

    @Test
    void byteLoadStoreCoverSignedAndUnsignedPaths() {
        RamBus bus = new RamBus(1 << 16);
        R816 cpu = cpu(bus,
                0x02, 0x20,       // B 0x20 addr
                0x02, 0xF1,       // B -15 low byte = 0xF1
                0x25,             // SB
                0x02, 0x20, 0x05, // B 0x20 ; LB
                0x02, 0x20, 0x09  // B 0x20 ; LU
        );

        step(cpu, 3);
        assertEquals(0xF1, Byte.toUnsignedInt(bus.read8(0x0020)));

        step(cpu, 2);
        assertEquals((byte) 0xF1, cpu.a());
        assertEquals(0xFFF1, cpu.a() & 0xFFFF);

        step(cpu, 2);
        assertEquals(0x00F1, cpu.a() & 0xFFFF);
    }

    @Test
    void pushAndPopUseNativeWordStackPointer() {
        RamBus bus = new RamBus(1 << 16);
        R816 cpu = cpu(bus, 0x45, 0x41); // PUSH; POP
        cpu.wksp[15] = 0x0200;
        cpu.setAReg(0xBEEF);
        cpu.setBReg(0x1111);
        cpu.setCReg(0x2222);

        cpu.executeInstruction();
        assertEquals(0x01FE, cpu.sp());
        assertEquals(0xBEEF, bus.readWord16(0x01FE));
        assertEquals(0x1111, cpu.a() & 0xFFFF);
        assertEquals(0x2222, cpu.b() & 0xFFFF);

        cpu.executeInstruction();
        assertEquals(0x0200, cpu.sp());
        assertEquals(0xBEEF, cpu.a() & 0xFFFF);
        assertEquals(0x1111, cpu.b() & 0xFFFF);
        assertEquals(0x2222, cpu.c() & 0xFFFF);
    }

    @Test
    void addSubMulAndBitwiseOpsConsumeTwoOperands() {
        R816 cpu = cpu(0x20, 0x24, 0x28, 0x40, 0x44, 0x48);

        cpu.setAReg(3);
        cpu.setBReg(4);
        cpu.setCReg(9);
        cpu.executeInstruction();
        assertEquals(7, cpu.a());
        assertEquals(9, cpu.b());

        cpu.setAReg(3);
        cpu.setBReg(4);
        cpu.setCReg(9);
        cpu.executeInstruction();
        assertEquals(1, cpu.a());
        assertEquals(9, cpu.b());

        cpu.setAReg(3);
        cpu.setBReg(4);
        cpu.setCReg(9);
        cpu.executeInstruction();
        assertEquals(12, cpu.a());
        assertEquals(9, cpu.b());

        cpu.setAReg(0x0F0F);
        cpu.setBReg(0x33CC);
        cpu.setCReg(0x7777);
        cpu.executeInstruction();
        assertEquals(0x030C, cpu.a() & 0xFFFF);

        cpu.setAReg(0x0F0F);
        cpu.setBReg(0x33CC);
        cpu.setCReg(0x7777);
        cpu.executeInstruction();
        assertEquals(0x3FCF, cpu.a() & 0xFFFF);

        cpu.setAReg(0x0F0F);
        cpu.setBReg(0x33CC);
        cpu.setCReg(0x7777);
        cpu.executeInstruction();
        assertEquals(0x3CC3, cpu.a() & 0xFFFF);
    }

    @Test
    void divAndRemainderSupportSignedAndUnsignedMath() {
        R816 cpu = cpu(0x2C, 0x30, 0xAC, 0xB0);

        cpu.setAReg(3);
        cpu.setBReg(10);
        cpu.setCReg(99);
        cpu.executeInstruction();
        assertEquals(3, cpu.a());
        assertEquals(99, cpu.b());

        cpu.setAReg(3);
        cpu.setBReg(10);
        cpu.setCReg(99);
        cpu.executeInstruction();
        assertEquals(1, cpu.a());
        assertEquals(99, cpu.b());

        cpu.setAReg(2);
        cpu.setBReg(0xFFFE);
        cpu.setCReg(99);
        cpu.executeInstruction();
        assertEquals(0x7FFF, cpu.a() & 0xFFFF);

        cpu.setAReg(4);
        cpu.setBReg(0xFFFE);
        cpu.setCReg(99);
        cpu.executeInstruction();
        assertEquals(2, cpu.a());
    }

    @Test
    void comparisonsAndShiftsMatchSignednessRules() {
        R816 cpu = cpu(0x50, 0x54, 0x6C, 0x70, 0x60, 0x64, 0x68);

        cpu.setAReg(5);
        cpu.setBReg(5);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(1, cpu.a());

        cpu.setAReg(5);
        cpu.setBReg(7);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(1, cpu.a());

        cpu.setAReg(1);
        cpu.setBReg(-2);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(1, cpu.a());

        cpu.setAReg(1);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(0, cpu.a(), "0xFFFF unsigned is not < 1");

        cpu.setAReg(4);
        cpu.setBReg(0x0003);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(0x0030, cpu.a() & 0xFFFF);

        cpu.setAReg(4);
        cpu.setBReg(0x00F0);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(0x000F, cpu.a() & 0xFFFF);

        cpu.setAReg(4);
        cpu.setBReg(0xFFF0);
        cpu.setCReg(123);
        cpu.executeInstruction();
        assertEquals(0xFFFF, cpu.a() & 0xFFFF);
    }

    @Test
    void unaryOpsCoverIncDecNegInvAndClz() {
        R816 cpu = cpu(0x34, 0x38, 0x3C, 0x4C, 0x90, 0x90);

        cpu.setAReg(9);
        cpu.executeInstruction();
        assertEquals(10, cpu.a());

        cpu.setAReg(9);
        cpu.executeInstruction();
        assertEquals(8, cpu.a());

        cpu.setAReg(7);
        cpu.executeInstruction();
        assertEquals(-7, cpu.a());

        cpu.setAReg(0x00F0);
        cpu.executeInstruction();
        assertEquals(0xFF0F, cpu.a() & 0xFFFF);

        cpu.setAReg(0x0010);
        cpu.executeInstruction();
        assertEquals(11, cpu.a(), "16-bit CLZ(0x0010)=11");

        cpu.setAReg(0);
        cpu.executeInstruction();
        assertEquals(16, cpu.a());
    }

    @Test
    void nintendoPrimitivesExposeCarryBorrowAndUpperProducts() {
        R816 cpu = cpu(0x80, 0x84, 0x88, 0x8C);

        cpu.setAReg(1);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(0x2222);
        cpu.executeInstruction();
        assertEquals(0x0000, cpu.a() & 0xFFFF);
        assertEquals(1, cpu.b());
        assertEquals(0x2222, cpu.c() & 0xFFFF);

        cpu.setAReg(2);
        cpu.setBReg(1);
        cpu.setCReg(0x3333);
        cpu.executeInstruction();
        assertEquals(0xFFFF, cpu.a() & 0xFFFF);
        assertEquals(1, cpu.b());
        assertEquals(0x3333, cpu.c() & 0xFFFF);

        cpu.setAReg(0x0100);
        cpu.setBReg(0x0100);
        cpu.setCReg(0x4444);
        cpu.executeInstruction();
        assertEquals(0x0001, cpu.a() & 0xFFFF);
        assertEquals(0x4444, cpu.b() & 0xFFFF);

        cpu.setAReg(0xFFFF);
        cpu.setBReg(0xFFFF);
        cpu.setCReg(0x5555);
        cpu.executeInstruction();
        assertEquals(0xFFFE, cpu.a() & 0xFFFF);
        assertEquals(0x5555, cpu.b() & 0xFFFF);
    }

    @Test
    void halfwordAndThirtyTwoBitInstructionsTrapOnR816() {
        int[] illegalOpcodes = {0x0D, 0x11, 0x29, 0x15, 0x19, 0x2D};
        for (int opcode : illegalOpcodes) {
            R816 cpu = cpu(opcode);
            cpu.mtvec = 0x0100;
            cpu.executeInstruction();
            assertTrap(cpu, TRAP_ILLEGAL_INSN, 0x0000, opcode);
            assertEquals(0x0100, cpu.ip());
        }
    }
}
