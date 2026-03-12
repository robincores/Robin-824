package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.backend.r8.R816Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class R816DialectCompareTest {

    private final R816Dialect d = new R816Dialect();

    @Test
    void bin_supports_comparison_ops() {
        assertEquals("  seq", d.bin("=="));
        assertEquals("  sne", d.bin("!="));
        assertEquals("  slt", d.bin("<"));
        assertEquals("  swap\n  slt", d.bin(">"));

        String le = d.bin("<=");
        assertTrue(le.contains("stl w10"));
        assertTrue(le.contains("stl w11"));
        assertTrue(le.contains("slt"));
        assertTrue(le.contains("seq"));
        assertTrue(le.contains("or"));

        String ge = d.bin(">=");
        assertTrue(ge.contains("stl w10"));
        assertTrue(ge.contains("stl w11"));
        assertTrue(ge.contains("slt"));
        assertTrue(ge.contains("seq"));
        assertTrue(ge.contains("or"));
    }

    @Test
    void bin_supports_eager_logical_ops() {
        String and = d.bin("&&");
        assertTrue(and.contains("u 0"));
        assertTrue(and.contains("sne"));
        assertTrue(and.contains("swap"));
        assertTrue(and.endsWith("and"));

        String or = d.bin("||");
        assertTrue(or.contains("u 0"));
        assertTrue(or.contains("sne"));
        assertTrue(or.contains("swap"));
        assertTrue(or.endsWith("or"));
    }
}
