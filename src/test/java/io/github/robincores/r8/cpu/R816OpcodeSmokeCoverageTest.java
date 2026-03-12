package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816OpcodeSmokeCoverageTest extends R816TestSupport {

    @Test
    void nopFenceAndHltAreReachableAndBehaveReasonably() {
        R816 cpu = cpu(0x00, 0x6F, 0xFF);

        cpu.executeInstruction();
        assertEquals(0x0001, cpu.ip());
        assertFalse(cpu.isHalted());

        cpu.executeInstruction();
        assertEquals(0x0002, cpu.ip());
        assertFalse(cpu.isHalted());

        cpu.executeInstruction();
        assertEquals(0x0003, cpu.ip());
        assertTrue(cpu.isHalted());
    }
}
