package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 12: Forward refs + expression fixups (label arithmetic).
 */
public class AssemblerFixupExprTest {

    @Test
    void forward_expression_in_immediate_is_patched() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                i target+2
                target:
                .byte 0xAA
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // target is at address 3 (after i imm16 = 3 bytes). target+2 = 5 => 05 00 (little-endian)
        assertEquals(List.of(0x8B, 0x05, 0x00, 0xAA), st.output);
    }

    @Test
    void forward_expression_in_data_is_patched() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .byte target+1
                target:
                .byte 0xAA
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // target is at address 1, so target+1 = 2, and then 0xAA
        assertEquals(List.of(0x02, 0xAA), st.output);
    }
}
