package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 13: Local labels
 *  - Numeric locals: 1: ... j 1f / j 1b
 *  - .Lfoo locals (rewritten internally)
 */
public class AssemblerLocalLabelsTest {

    @Test
    void numeric_local_forward_1f_jumps_to_next_1_label() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                1:
                  i0
                  j 1f
                  i1
                1:
                  i1
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // Same layout as the named-label test: branch offset should be +1
        assertEquals(List.of(0x83, 0x62, 0x01, 0x87, 0x87), st.output);
    }

    @Test
    void numeric_local_backward_1b_jumps_to_previous_1_label() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                1:
                  i0
                  j 1b
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // target=0, ofs=1, ipofs=2 => delta = -3 => 0xFD
        assertEquals(List.of(0x83, 0x62, 0xFD), st.output);
    }

    @Test
    void dot_L_local_labels_are_accepted_and_resolve() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .Lloop:
                  i0
                  j .Lloop
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // Same as backward numeric local case
        assertEquals(List.of(0x83, 0x62, 0xFD), st.output);
    }
}
