package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816BlockOpsAndMicroFusionTest extends R816TestSupport {

    @Test
    void movbCopiesByteByByteUntilCountReachesZero() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0200, 0x11, 0x22, 0x33);
        R816 cpu = cpu(bus, 0x7F, 0x01); // ESC MOVB
        cpu.wksp[0] = 0x0300;
        cpu.wksp[1] = 0x0200;
        cpu.wksp[2] = 3;

        step(cpu, 3);
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33}, bus.slice(0x0300, 3));
        assertEquals(0x0303, cpu.wksp[0]);
        assertEquals(0x0203, cpu.wksp[1]);
        assertEquals(0, cpu.wksp[2]);
        assertEquals(0x0002, cpu.ip(), "falls through after final element");
    }

    @Test
    void movwUsesNativeWordStride() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0400, 0x34, 0x12, 0x78, 0x56);
        R816 cpu = cpu(bus, 0x7F, 0x05); // ESC MOVW
        cpu.wksp[0] = 0x0500;
        cpu.wksp[1] = 0x0400;
        cpu.wksp[2] = 2;

        step(cpu, 2);
        assertArrayEquals(new byte[]{0x34, 0x12, 0x78, 0x56}, bus.slice(0x0500, 4));
        assertEquals(0x0504, cpu.wksp[0]);
        assertEquals(0x0404, cpu.wksp[1]);
        assertEquals(0, cpu.wksp[2]);
    }

    @Test
    void fillbAndFillwWriteRepeatedPatterns() {
        RamBus bus = new RamBus(1 << 16);
        R816 fillb = cpu(bus, 0x7F, 0x09); // ESC FILLB
        fillb.wksp[0] = 0x0600;
        fillb.wksp[1] = 0x00A5;
        fillb.wksp[2] = 4;
        step(fillb, 4);
        assertArrayEquals(new byte[]{(byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5}, bus.slice(0x0600, 4));

        RamBus bus2 = new RamBus(1 << 16);
        R816 fillw = cpu(bus2, 0x7F, 0x0D); // ESC FILLW
        fillw.wksp[0] = 0x0700;
        fillw.wksp[1] = 0xBEEF;
        fillw.wksp[2] = 2;
        step(fillw, 2);
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBE, (byte) 0xEF, (byte) 0xBE}, bus2.slice(0x0700, 4));
    }

    @Test
    void zeroCountBlockOpFallsThroughWithoutTouchingMemory() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0800, 0x11, 0x22, 0x33);
        R816 cpu = cpu(bus, 0x7F, 0x01);
        cpu.wksp[0] = 0x0900;
        cpu.wksp[1] = 0x0800;
        cpu.wksp[2] = 0;

        cpu.executeInstruction();
        assertArrayEquals(new byte[]{0, 0, 0}, bus.slice(0x0900, 3));
        assertEquals(0x0002, cpu.ip());
    }

    @Test
    void blockOpsTrapWhenExtensionIsDisabled() {
        R816 cpu = cpuWithBlk(false, 0x7F, 0x01);
        cpu.mtvec = 0x0100;
        cpu.executeInstruction();
        assertTrap(cpu, TRAP_ILLEGAL_INSN, 0x0000, 0x007F);
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void interruptBetweenBlockIterationsReportsEscAsMepc() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0200, 0x10, 0x20, 0x30);
        R816 cpu = cpu(bus, 0x5B, 0x7F, 0x01); // EI ; ESC MOVB
        cpu.mtvec = 0x0100;
        cpu.wksp[0] = 0x0300;
        cpu.wksp[1] = 0x0200;
        cpu.wksp[2] = 3;

        cpu.executeInstruction(); // EI
        cpu.raise(R8Core.TIMER_INTERRUPT_MASK);
        cpu.executeInstruction(); // first MOVB element then IRQ service

        assertEquals(0x10, Byte.toUnsignedInt(bus.read8(0x0300)));
        assertEquals(0x0301, cpu.wksp[0]);
        assertEquals(0x0201, cpu.wksp[1]);
        assertEquals(2, cpu.wksp[2]);
        assertInterrupt(cpu, 1, 0x0001);
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void safeMicroFusionExecutesImmediateProducerAndBinaryConsumerTogether() {
        R816 cpu = cpuWithMode(true, R8Core.MicroFusionMode.SAFE,
                0x02, 0x01, // B 1
                0x20        // ADD
        );
        cpu.setAReg(2);
        cpu.setBReg(0);
        cpu.setCReg(0);

        int cycles = cpu.executeInstruction();
        assertEquals(3, cpu.a());
        assertEquals(0, cpu.b());
        assertEquals(0x0003, cpu.ip());
        assertTrue(cycles > 0);
    }

    @Test
    void safeMicroFusionStillHonorsInterruptBoundaryBetweenTheTwoInstructions() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0000,
                0x5B,       // EI
                0x02, 0x01, // B 1
                0x20        // ADD
        );
        R816 cpu = cpuWithMode(true, R8Core.MicroFusionMode.SAFE, bus);
        cpu.mtvec = 0x0100;
        cpu.setAReg(2);
        cpu.executeInstruction(); // EI

        cpu.raise(R8Core.TIMER_INTERRUPT_MASK);
        cpu.executeInstruction();

        assertEquals(1, cpu.a());
        assertEquals(2, cpu.b());
        assertInterrupt(cpu, 1, 0x0003, 0);
        assertEquals(0x0100, cpu.ip());
    }

    private static void assertInterrupt(R816 cpu, int cause, int epc, int ignoredMtval) {
        R816TestSupport.assertInterrupt(cpu, cause, epc);
        assertEquals(0, cpu.mtval());
    }
}
