package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AssemblerMacroTest {

    @Test
    public void macro_expands_params_into_data() {
        var as = new Assembler(null);
        var st = as.assembleFile("""
                .arch r816
                .macro EMIT3 a,b,c
                  .data \\a, \\b, \\c
                .endm
                EMIT3 1,2,3
                """);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(1, 2, 3), st.output);
    }

    @Test
    public void macro_can_call_another_macro() {
        var as = new Assembler(null);
        var st = as.assembleFile("""
                .arch r816
                .macro A x
                  .data \\x
                .endm
                .macro B y
                  A \\y
                .endm
                B 9
                """);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(9), st.output);
    }

    @Test
    public void macro_unique_at_avoids_label_collisions() {
        var as = new Assembler(null);
        var st = as.assembleFile("""
                .arch r816
                .macro MARK
                .Lx\\@:
                  .data 1
                .endm
                MARK
                MARK
                """);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(1, 1), st.output);
    }

    @Test
    public void macro_recursion_is_reported() {
        var as = new Assembler(null);
        var st = as.assembleFile("""
                .arch r816
                .macro A
                  A
                .endm
                A
                """);
        assertFalse(st.errors.isEmpty(), "Expected assembler errors for macro recursion");
        String all = st.errors.toString();
        assertTrue(all.toLowerCase().contains("macro recursion") || all.toLowerCase().contains("macro expansion depth"),
                "Expected macro recursion/depth message, got: " + all);
    }
}
