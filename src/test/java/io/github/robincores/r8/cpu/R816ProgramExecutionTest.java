package io.github.robincores.r8.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R816ProgramExecutionTest extends R816TestSupport {

    @Test
    void sumOneThroughFourLoopProducesTenAndHalts() {
        R816 cpu = cpu(
                0x02, 0x00,         // B 0
                stlOpcode(0),       // sum = 0
                0x02, 0x01,         // B 1
                stlOpcode(1),       // i = 1
                0x02, 0x05,         // B 5
                stlOpcode(2),       // limit = 5
                ldlOpcode(0),       // load sum
                ldlOpcode(1),       // load i
                0x20,               // ADD => sum+i
                stlOpcode(0),       // sum = sum+i
                ldlOpcode(1),       // load i
                0x02, 0x01,         // B 1
                0x20,               // ADD => i+1
                stlOpcode(1),       // i = i+1
                ldlOpcode(1),       // load i
                ldlOpcode(2),       // load limit
                0x2A, 0xF3,         // BLT back to loop body at address 9
                ldlOpcode(0),       // final sum in A
                0xFF                // HLT
        );

        for (int i = 0; i < 64 && !cpu.isHalted(); i++) {
            cpu.executeInstruction();
        }

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(10, cpu.a(), "1+2+3+4 should equal 10");
        assertEquals(10, cpu.wksp(0));
        assertEquals(5, cpu.wksp(1));
        assertEquals(5, cpu.wksp(2));
    }

    @Test
    void jalSubroutineCanDoubleAnArgumentAndReturnViaJr() {
        R816 cpu = cpu(
                0x02, 0x07,         // B 7
                0x77, 0x01, 0x00,   // JAL +1 -> subroutine at address 6
                0xFF,               // HLT after return
                0x08,               // SWAP   (A=arg, B=ret)
                0x04,               // DUP
                0x20,               // ADD    (arg+arg)
                0x08,               // SWAP   (A=ret, B=result)
                0x42                // JR     (return, leave result in A)
        );

        for (int i = 0; i < 16 && !cpu.isHalted(); i++) {
            cpu.executeInstruction();
        }

        assertTrue(cpu.isHalted(), "caller should resume and halt");
        assertEquals(14, cpu.a(), "subroutine should double the argument");
        assertEquals(0x0006, cpu.ip(), "HLT should have been fetched after returning to caller");
    }

    @Test
    void bnezDrivenCountdownLoopRunsToZero() {
        R816 cpu = cpu(
                0x02, 0x03,   // B 3
                0x38,         // DEC
                0x04,         // DUP
                0x3E, 0xFC,   // BNEZ back to DEC at address 2
                0xFF          // HLT
        );

        for (int i = 0; i < 16 && !cpu.isHalted(); i++) {
            cpu.executeInstruction();
        }

        assertTrue(cpu.isHalted(), "program should halt after countdown");
        assertEquals(0, cpu.a(), "countdown should end at zero");
    }
}
