package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 10: .equ / .set constants (and using them in expressions).
 */
public class AssemblerEquTest {

    @Test
    void equ_constants_work_in_data_and_immediates() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .equ foo, 0x10+1
                .byte foo, foo+1
                i foo+2
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // foo = 0x11
        // .data => 0x11, 0x12
        // i imm16 little-endian for 0x13 => 13 00 (opcode 0x8B)
        assertEquals(List.of(0x11, 0x12, 0x8B, 0x13, 0x00), st.output);
    }

    @Test
    void equ_redefinition_is_an_error() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .equ x, 1
                .equ x, 2
                .byte x
                """;

        AssemblerState st = as.assembleFile(src);
        assertFalse(st.errors.isEmpty(), "Expected an error for .equ redefinition");
        assertTrue(st.errors.get(0).msg.toLowerCase().contains("already defined"),
                "Expected 'already defined' message, got: " + st.errors.get(0));
    }

    @Test
    void set_allows_redefinition_and_updates_value() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .equ x, 1
                .set x, 2
                .byte x
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(2), st.output);
    }
}
