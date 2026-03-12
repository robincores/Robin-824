package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816InterruptTimingTest extends R816TestSupport {

    @Test
    void pendingInterruptIsTakenAfterTheCurrentInstructionNotBeforeIt() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0000,
                0x53, 0x01,   // SETI software
                0x5B,         // EI
                0x00,         // NOP
                0x00          // NOP
        );
        bus.load(0x0100, 0x63); // IRET

        R816 cpu = cpu(bus);
        cpu.mtvec = 0x0100;

        cpu.executeInstruction(); // SETI
        cpu.executeInstruction(); // EI
        cpu.setInterruptPending(R8Core.SOFTWARE_INTERRUPT_MASK);

        assertEquals(0x0003, cpu.ip(), "still at mainline before next step");
        cpu.executeInstruction(); // execute NOP at 0x0003, then take IRQ

        assertInterrupt(cpu, 0, 0x0004);
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void maskedPendingInterruptWaitsUntilItsSourceBecomesEnabled() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0000,
                0x5B,         // EI
                0x00,         // NOP
                0x53, 0x01,   // SETI software
                0x00          // NOP
        );
        bus.load(0x0100, 0x63); // IRET

        R816 cpu = cpu(bus);
        cpu.mtvec = 0x0100;

        cpu.executeInstruction(); // EI (only timer enabled by default)
        cpu.setInterruptPending(R8Core.SOFTWARE_INTERRUPT_MASK);

        cpu.executeInstruction(); // NOP, software IRQ still masked
        assertEquals(0x0002, cpu.ip(), "mainline should continue while SW IRQ is masked");
        assertEquals(0x0001, cpu.mip() & 0x07, "pending bit should remain latched");

        cpu.executeInstruction(); // SETI software, then pending SW IRQ should fire immediately
        assertInterrupt(cpu, 0, 0x0004);
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void iretCanImmediatelyRetakeAnotherPendingInterruptBeforeMainlineResumes() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0000,
                0x53, 0x07,   // SETI software|timer|external
                0x5B,         // EI
                0x00,         // NOP
                0x00          // NOP
        );
        bus.load(0x0100, 0x63); // IRET

        R816 cpu = cpu(bus);
        cpu.mtvec = 0x0100;

        cpu.executeInstruction(); // SETI 0x07
        cpu.executeInstruction(); // EI
        cpu.setInterruptPending(
                R8Core.SOFTWARE_INTERRUPT_MASK |
                R8Core.TIMER_INTERRUPT_MASK |
                R8Core.EXTERNAL_INTERRUPT_MASK
        );

        cpu.executeInstruction(); // NOP at 0x0003, then SW IRQ
        assertInterrupt(cpu, 0, 0x0004);

        cpu.executeInstruction(); // IRET, then timer IRQ immediately
        assertInterrupt(cpu, 1, 0x0004);

        cpu.executeInstruction(); // IRET, then external IRQ immediately
        assertInterrupt(cpu, 2, 0x0004);

        cpu.executeInstruction(); // final IRET, now back to mainline
        assertEquals(0x0004, cpu.ip());
        assertEquals(0x0000, cpu.mip() & 0x07, "all pending IRQs should be consumed");
    }
}
