package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 16: Conditional compilation
 *   .if/.elif/.else/.endif
 *   .ifdef/.ifndef
 * plus: new expression operators used by .if (==, &&, etc.)
 */
public class AssemblerConditionalCompilationTest {

    @Test
    void if_false_skips_lines_and_does_not_define_labels() {
        var as = new Assembler(null);

        String src = """
                .arch r816

                .if 0
                skipLabel:
                  .byte 0xAA
                .endif

                .ifdef skipLabel
                  .byte 0x11
                .else
                  .byte 0x22
                .endif
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // skipLabel must NOT exist (skipped), so we should get the else branch.
        assertEquals(List.of(0x22), st.output);
    }

    @Test
    void elif_selects_first_true_branch() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .set X, 2

                .if X == 1
                  .byte 0x11
                .elif X == 2
                  .byte 0x22
                .else
                  .byte 0x33
                .endif
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(0x22), st.output);
    }

    @Test
    void nested_ifs_respect_parent_activity() {
        var as = new Assembler(null);

        String src = """
                .arch r816

                .if 0
                  .if 1
                    .byte 0x11
                  .else
                    .byte 0x22
                  .endif
                .else
                  .byte 0x33
                .endif
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(0x33), st.output);
    }

    @Test
    void ifndef_true_when_symbol_missing() {
        var as = new Assembler(null);

        String src = """
                .arch r816

                .ifndef DOES_NOT_EXIST
                  .byte 0x7E
                .else
                  .byte 0x00
                .endif
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(0x7E), st.output);
    }

    @Test
    void short_circuit_avoids_unknown_symbols() {
        var as = new Assembler(null);

        String src = """
                .arch r816

                .if 0 && UNDEF
                  .byte 0x11
                .else
                  .byte 0x22
                .endif
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(0x22), st.output);
    }

    @Test
    void conditional_changes_layout_so_branch_fixup_matches_taken_bytes() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .set DEBUG, 0

                bra after

                .if DEBUG
                  .byte 0xAA
                .else
                  .byte 0xBB
                .endif

                after:
                .byte 0xCC
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // r816: bra ~rel8 => opcode 0x4A then rel8
        // layout: bra (2 bytes) + one byte (0xBB) => label after at ip=3
        // offset = target - ip - 2 = 3 - 0 - 2 = +1
        assertEquals(List.of(0x4A, 0x01, 0xBB, 0xCC), st.output);
    }

    @Test
    void arch_and_width_builtins_work_in_if_expressions() {
        var as = new Assembler(null);

        String src = """
                .arch r816

                .if __arch_r816__ && (__width__ == 8)
                  .byte 0x01
                .else
                  .byte 0x00
                .endif

                .width 16
                .if __width__ == 16
                  .byte 0x02
                .else
                  .byte 0xFF
                .endif
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // width is 8 initially => .byte emits one 8-bit word: 0x01
        // then width becomes 16 => .byte emits one 16-bit word: 0x0002 (stored as int 2)
        assertEquals(List.of(0x01, 0x02), st.output);
    }
}