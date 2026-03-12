package io.github.robincores.toolchain.r8as;

import io.github.robincores.r8.cpu.R816;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R816AssemblerExecutionTest extends R816AssemblerTestSupport {

    @Test
    void assembled_countdown_loop_runs_to_zero() {
        AssemblerState state = AssemblerTestSupport.assembleR816Ok(String.join("\n",
                "b 3",
                "loop:",
                "dec",
                "dup",
                "bnez loop",
                "hlt"
        ));

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 32);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(0, cpu.a() & 0xFFFF, "countdown should end at zero");
    }

    @Test
    void assembled_jal_and_jr_subroutine_doubles_an_argument() {
        AssemblerState state = AssemblerTestSupport.assembleR816Ok(String.join("\n",
                "b 7",
                "jal sub",
                "hlt",
                "sub:",
                "swap",
                "dup",
                "add",
                "swap",
                "jr"
        ));

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 32);

        assertTrue(cpu.isHalted(), "caller should eventually halt");
        assertEquals(14, cpu.a() & 0xFFFF, "subroutine should double the input");
    }

    @Test
    void assembled_program_from_path_with_include_executes() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("r816-exec-inc");
        AssemblerTestSupport.writeTextFile(dir, "body.asm", """
                hlt
                """);
        java.nio.file.Path main = AssemblerTestSupport.writeTextFile(dir, "main.asm", """
                .arch r816
                b 1
                .include "body.asm"
                """);

        AssemblerState state = new Assembler().assemblePath(main);
        AssemblerTestSupport.assertNoErrors(state);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 8);

        assertTrue(cpu.isHalted());
        assertEquals(1, cpu.a() & 0xFFFF);
    }

    @Test
    void relaxed_far_beq_taken_reaches_target() {
        Assembler a = AssemblerTestSupport.newR816Assembler().setRelaxBranches(true);
        AssemblerState state = a.assembleFile(String.join("\n",
                "b 0",               // A = 0 so BEQ should be taken
                "beq target",
                AssemblerTestSupport.manyBytes(200, 0),
                "b 99",              // must be skipped if branch is correct
                "hlt",
                "target:",
                "b 7",
                "hlt"
        ));

        AssemblerTestSupport.assertNoErrors(state);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 512);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(7, cpu.a() & 0xFFFF, "taken relaxed BEQ should land at target");
    }

    @Test
    void relaxed_far_beq_not_taken_falls_through() {
        Assembler a = AssemblerTestSupport.newR816Assembler().setRelaxBranches(true);
        AssemblerState state = a.assembleFile(String.join("\n",
                "b 1",               // A = 1 so BEQ should NOT be taken
                "beq target",
                AssemblerTestSupport.manyBytes(200, 0),
                "b 9",
                "hlt",
                "target:",
                "b 7",
                "hlt"
        ));

        AssemblerTestSupport.assertNoErrors(state);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 512);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(9, cpu.a() & 0xFFFF, "not-taken relaxed BEQ should fall through");
    }

    @Test
    void relaxed_far_bra_jumps_to_target() {
        Assembler a = AssemblerTestSupport.newR816Assembler().setRelaxBranches(true);
        AssemblerState state = a.assembleFile(String.join("\n",
                "b 1",
                "bra target",
                AssemblerTestSupport.manyBytes(200, 0),
                "b 99",
                "hlt",
                "target:",
                "b 5",
                "hlt"
        ));

        AssemblerTestSupport.assertNoErrors(state);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 512);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(5, cpu.a() & 0xFFFF, "relaxed BRA should jump to target");
    }

    @Test
    void relaxed_absolute_far_bra_jumps_to_target() {
        Assembler a = AssemblerTestSupport.newR816Assembler()
                .setRelaxBranches(true)
                .setRelaxMaxPasses(20);

        AssemblerState state = a.assembleFile(String.join("\n",
                "b 1",
                "bra target",
                "hlt",
                ".org 0x9000",
                "target:",
                "b 6",
                "hlt"
        ));

        AssemblerTestSupport.assertNoErrors(state);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 512);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(6, cpu.a() & 0xFFFF, "absolute relaxed BRA should reach far target");
    }
}
