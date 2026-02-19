package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Alias coverage for the R8 workspace register file.
 *
 * We keep:
 *  - canonical names: w0..w14, sp
 *  - numeric aliases: r0..r15 and w15 (-> sp)
 */
public class AssemblerAliasTest {

    @Test
    void r8_workspace_register_aliases_work() {
        var as = new Assembler(null);

        String src = """
                .arch r816
                ldl w1
                ldl r1
                ldl sp
                ldl w15
                ldl @1
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // ldl w1  -> 00 0001 11 = 0x07
        // ldl r1  -> alias -> w1 -> 0x07
        // ldl sp  -> 00 1111 11 = 0x3F
        // ldl w15 -> alias -> sp -> 0x3F
        // ldl @1  -> numeric workspace index -> 0x07
        assertEquals(List.of(0x07, 0x07, 0x3F, 0x3F, 0x07), st.output);
    }
}
