package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816SystemTrapAndInterruptTest extends R816TestSupport {

    @Test
    void csrRoundTripCoversWritableStateAndMextIsReadOnly() {
        R816 cpu = cpu(
                0x43, 0x34, 0x12, 0x4F, CSR_MTVEC, // I 0x1234 ; CSRW MTVEC
                0x4B, CSR_MTVEC,                   // CSRR MTVEC
                0x43, 0x03, 0x00, 0x4F, CSR_MSTATUS, // I 3 ; CSRW MSTATUS
                0x4B, CSR_MSTATUS,                 // CSRR MSTATUS
                0x43, 0x00, 0x00, 0x4F, CSR_MEXT, // attempt write read-only CSR
                0x4B, CSR_MEXT                    // CSRR MEXT
        );

        step(cpu, 2);
        assertEquals(0x1234, cpu.mtvec());

        cpu.executeInstruction();
        assertEquals(0x1234, cpu.a() & 0xFFFF);

        step(cpu, 2);
        assertEquals(0x0003, cpu.mstatus() & 0xFFFF);

        cpu.executeInstruction();
        assertEquals(0x0003, cpu.a() & 0xFFFF);

        step(cpu, 2);
        cpu.executeInstruction();
        assertEquals(0x0001, cpu.a() & 0xFFFF, "R8BLK bit should still be present");
    }

    @Test
    void mextReflectsBlockExtensionAvailability() {
        R816 yesBlk = cpuWithBlk(true, 0x4B, CSR_MEXT);
        yesBlk.executeInstruction();
        assertEquals(0x0001, yesBlk.a() & 0xFFFF);

        R816 noBlk = cpuWithBlk(false, 0x4B, CSR_MEXT);
        noBlk.executeInstruction();
        assertEquals(0x0000, noBlk.a() & 0xFFFF);
    }

    @Test
    void mipWriteSemanticsMatchSoftwareAndW1CClears() {
        R816 cpu = cpu(
                0x02, 0x07, 0x4F, CSR_MIP, // B 7 ; CSRW MIP  => set SW, clear TIMER/EXT if pending
                0x4B, CSR_MIP,             // CSRR MIP
                0x02, 0x06, 0x4F, CSR_MIP, // B 6 ; CSRW MIP  => clear SW, clear TIMER/EXT by W1C
                0x4B, CSR_MIP              // CSRR MIP
        );

        cpu.setInterruptPending(R8Core.TIMER_INTERRUPT_MASK | R8Core.EXTERNAL_INTERRUPT_MASK);
        step(cpu, 2);
        cpu.executeInstruction();
        assertEquals(0x0001, cpu.a() & 0xFFFF, "only SW should remain pending");

        step(cpu, 2);
        cpu.executeInstruction();
        assertEquals(0x0000, cpu.a() & 0xFFFF);
    }

    @Test
    void eiDiSetiAndClriManipulateInterruptEnables() {
        R816 cpu = cpu(
                0x5B,             // EI
                0x53, 0x05,       // SETI SW|EXT
                0x57, 0x02,       // CLRI TIMER
                0x5F              // DI
        );

        cpu.executeInstruction();
        assertEquals(MSTATUS_MIE, cpu.mstatus() & MSTATUS_MIE);

        cpu.executeInstruction();
        assertEquals(0x07, cpu.mie() & 0x07);

        cpu.executeInstruction();
        assertEquals(0x05, cpu.mie() & 0x07);

        cpu.executeInstruction();
        assertEquals(0, cpu.mstatus() & MSTATUS_MIE);
    }

    @Test
    void illegalInstructionTrapCapturesOpcodeAndEpc() {
        R816 cpu = cpu(0xDE);
        cpu.mtvec = 0x0100;
        cpu.executeInstruction();
        assertTrap(cpu, TRAP_ILLEGAL_INSN, 0x0000, 0x00DE);
        assertEquals(0x0100, cpu.ip());
    }

    @Test
    void divideByZeroTrapIsPrecise() {
        R816 cpu = cpu(0x2C); // DIV
        cpu.mtvec = 0x0200;
        cpu.setAReg(0);
        cpu.setBReg(10);
        cpu.executeInstruction();
        assertTrap(cpu, TRAP_DIV_ZERO, 0x0000, 0);
        assertEquals(0x0200, cpu.ip());
    }

    @Test
    void ecallAndEbreakEnterTrapVector() {
        R816 ecall = cpu(0x67);
        ecall.mtvec = 0x0100;
        ecall.executeInstruction();
        assertTrap(ecall, TRAP_ECALL, 0x0000, 0);
        assertEquals(0x0100, ecall.ip());

        R816 ebreak = cpu(0x6B);
        ebreak.mtvec = 0x0200;
        ebreak.executeInstruction();
        assertTrap(ebreak, TRAP_EBREAK, 0x0000, 0);
        assertEquals(0x0200, ebreak.ip());
    }

    @Test
    void interruptEntryAndIretRestoreExecutionPointAndMie() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0x0000, 0x5B, 0x00); // EI ; NOP
        bus.load(0x0100, 0x63);       // IRET
        R816 cpu = cpu(bus);
        cpu.mtvec = 0x0100;

        cpu.executeInstruction(); // EI
        cpu.raise(R8Core.TIMER_INTERRUPT_MASK);
        cpu.executeInstruction(); // NOP then IRQ service

        assertInterrupt(cpu, 1, 0x0002);
        assertEquals(0x0100, cpu.ip());
        assertEquals(0, cpu.mstatus() & MSTATUS_MIE, "MIE cleared in handler");
        assertEquals(MSTATUS_MPIE, cpu.mstatus() & MSTATUS_MPIE, "MPIE remembers prior MIE");

        cpu.executeInstruction(); // IRET
        assertEquals(0x0002, cpu.ip());
        assertEquals(MSTATUS_MIE, cpu.mstatus() & MSTATUS_MIE, "IRET restores MIE from MPIE");
    }

    @Test
    void interruptPriorityIsSoftwareThenTimerThenExternal() {
        RamBus bus = new RamBus(1 << 16);
        bus.load(
                0x0000,
                0x53, 0x07,       // SETI software|timer|external
                0x5B,             // EI
                0x00, 0x00, 0x00  // NOPs
        );
        bus.load(0x0100, 0x63);   // IRET

        R816 cpu = cpu(bus);
        cpu.mtvec = 0x0100;

        cpu.executeInstruction(); // SETI 0x07
        cpu.executeInstruction(); // EI

        cpu.setInterruptPending(
                R8Core.SOFTWARE_INTERRUPT_MASK |
                        R8Core.TIMER_INTERRUPT_MASK |
                        R8Core.EXTERNAL_INTERRUPT_MASK
        );

        cpu.executeInstruction(); // first NOP -> software taken
        assertInterrupt(cpu, 0, 0x0004);

        cpu.executeInstruction(); // IRET -> timer taken immediately
        assertInterrupt(cpu, 1, 0x0004);

        cpu.executeInstruction(); // IRET -> external taken immediately
        assertInterrupt(cpu, 2, 0x0004);

        cpu.executeInstruction(); // final IRET, now really back to mainline
        assertEquals(0x0004, cpu.ip());
    }

    @Test
    void haltedCpuOnlyWakesWhenGlobalAndPerSourceEnablePermitIt() {
        RamBus noWakeBus = new RamBus(1 << 16);
        noWakeBus.load(0x0000, 0x5F, 0xFF, 0x00); // DI ; HLT ; NOP
        R816 noWake = cpu(noWakeBus);
        step(noWake, 2);
        assertTrue(noWake.isHalted());
        noWake.raise(R8Core.TIMER_INTERRUPT_MASK);
        assertEquals(1, noWake.executeInstruction(), "remains idle while halted");
        assertTrue(noWake.isHalted());

        RamBus wakeBus = new RamBus(1 << 16);
        wakeBus.load(0x0000, 0x5B, 0xFF, 0x00); // EI ; HLT ; NOP
        R816 wake = cpu(wakeBus);
        wake.mtvec = 0x0100;
        step(wake, 2);
        assertTrue(wake.isHalted());
        wake.raise(R8Core.TIMER_INTERRUPT_MASK);
        wake.executeInstruction();
        assertFalse(wake.isHalted());
        assertInterrupt(wake, 1, 0x0003);
    }
}
