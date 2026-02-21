package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 9: Expression + comma-separated directive args.
 */
public class AssemblerExprTest {

    @Test
    void data_accepts_commas_and_expressions() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .byte 0x10+1, 2*3, (7+1)
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(0x11, 0x06, 0x08), st.output);
    }

    @Test
    void instruction_immediates_can_use_expressions() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                i 0x1234 + 2
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // i = 0x8B + imm16 little-endian (0x1236 -> 36 12)
        assertEquals(List.of(0x8B, 0x36, 0x12), st.output);
    }

    @Test
    void align_accepts_expressions() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                .byte 1
                .align 4+4
                .byte 2
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // after one byte, align to 8 => pad 7 zeros
        assertEquals(List.of(1, 0,0,0,0,0,0,0, 2), st.output);
    }
}
