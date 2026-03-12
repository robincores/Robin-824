package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816StackDepthTest extends R816TestSupport {

    @Test
    void fivePushesAndFivePopsPreserveLifoOrderThroughMemoryStack() {
        R816 cpu = cpu(
                0x43, 0x11, 0x11, 0x45, // I 0x1111 ; PUSH
                0x43, 0x22, 0x22, 0x45, // I 0x2222 ; PUSH
                0x43, 0x33, 0x33, 0x45, // I 0x3333 ; PUSH
                0x43, 0x44, 0x44, 0x45, // I 0x4444 ; PUSH
                0x43, 0x55, 0x55, 0x45, // I 0x5555 ; PUSH
                0x41, stlOpcode(0),     // POP ; store 5555
                0x41, stlOpcode(1),     // POP ; store 4444
                0x41, stlOpcode(2),     // POP ; store 3333
                0x41, stlOpcode(3),     // POP ; store 2222
                0x41, stlOpcode(4),     // POP ; store 1111
                0xFF                    // HLT
        );
        cpu.wksp[15] = 0x0200;

        for (int i = 0; i < 64 && !cpu.isHalted(); i++) {
            cpu.executeInstruction();
        }

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(0x0200, cpu.sp(), "stack pointer should fully unwind");
        assertEquals(0x5555, cpu.wksp(0) & 0xFFFF);
        assertEquals(0x4444, cpu.wksp(1) & 0xFFFF);
        assertEquals(0x3333, cpu.wksp(2) & 0xFFFF);
        assertEquals(0x2222, cpu.wksp(3) & 0xFFFF);
        assertEquals(0x1111, cpu.wksp(4) & 0xFFFF);
    }

    @Test
    void stackPointerWrapsAcrossZeroOnPushAndPop() {
        RamBus bus = new RamBus(1 << 16);
        R816 cpu = cpu(bus,
                0x43, 0xCD, 0xAB, // I 0xABCD
                0x45,             // PUSH
                0x41              // POP
        );
        cpu.wksp[15] = 0x0000;

        cpu.executeInstruction();
        cpu.executeInstruction();
        assertEquals(0xFFFE, cpu.sp(), "push should wrap stack pointer at 16-bit boundary");
        assertEquals(0xABCD, bus.readWord16(0xFFFE));

        cpu.executeInstruction();
        assertEquals(0x0000, cpu.sp(), "pop should restore wrapped stack pointer");
        assertEquals(0xABCD, cpu.a() & 0xFFFF);
    }
}
