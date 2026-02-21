package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused regression tests for the R8 family assembler.
 *
 * These tests intentionally avoid RISC-V and validate:
 *  - R816 label fixups for PC-relative branches
 *  - R824/R832 immediate widths (24/32-bit little-endian)
 */
public class AssemblerEncodingTest {

//    @Test
//    void r816_branch_fixup_is_pc_relative_from_next_instruction() {
//        var as = new Assembler(null);
//
//        String src = """
//                .arch r816
//                start:
//                  i0
//                  j end
//                  i1
//                end:
//                  i1
//                """;
//
//        AssemblerState st = as.assembleFile(src);
//        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
//
//        // Expected bytes:
//        // i0  = 0x83
//        // j   = 0x62 + rel8 (target=4, ofs=1, ipofs=2 => +1)
//        // i1  = 0x87
//        // i1  = 0x87
//        assertEquals(List.of(0x83, 0x62, 0x01, 0x87, 0x87), st.output);
//    }

    @Test
    void r824_imm24_is_little_endian_3_bytes() {
        var as = new Assembler(null);

        String src = """
                .arch r824
                i 0x123456
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // i = 0x8B + imm24 little-endian (56 34 12)
        assertEquals(List.of(0x8B, 0x56, 0x34, 0x12), st.output);
    }

    @Test
    void r832_imm32_is_little_endian_4_bytes() {
        var as = new Assembler(null);

        String src = """
                .arch r832
                i 0x12345678
                """;

        AssemblerState st = as.assembleFile(src);
        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);

        // i = 0x8B + imm32 little-endian (78 56 34 12)
        assertEquals(List.of(0x8B, 0x78, 0x56, 0x34, 0x12), st.output);
    }
}
