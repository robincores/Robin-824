package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    @Test
    void evaluates_literals_precedence_and_parentheses() {
        Long v = ExpressionEvaluator.eval("1 + 2 * (3 + 4)", Map.of(), 0);
        assertEquals(15L, v);

        assertEquals(0x1fL, ExpressionEvaluator.eval("0x10 + $0F", Map.of(), 0));
        assertEquals(1000L, ExpressionEvaluator.eval("1_000", Map.of(), 0));
    }

    @Test
    void evaluates_symbols_and_dot_case_insensitively() {
        Map<String, Symbol> syms = Map.of(
                "foo", new Symbol(7),
                "bar", new Symbol(3)
        );
        assertEquals(15L, ExpressionEvaluator.eval("FOO + bar + .", syms, 5));
    }

    @Test
    void evaluates_bitwise_shift_relational_equality_and_logical_ops() {
        assertEquals(8L, ExpressionEvaluator.eval("1 << 3", Map.of(), 0));
        assertEquals(3L, ExpressionEvaluator.eval("15 >> 2", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("3 < 4", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("4 >= 4", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("5 == 5", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("5 != 6", Map.of(), 0));
        assertEquals(7L, ExpressionEvaluator.eval("3 | 4", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("5 & 3", Map.of(), 0));
        assertEquals(6L, ExpressionEvaluator.eval("5 ^ 3", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("1 && 2", Map.of(), 0));
        assertEquals(1L, ExpressionEvaluator.eval("0 || 9", Map.of(), 0));
        assertEquals(0L, ExpressionEvaluator.eval("!1", Map.of(), 0));
        assertEquals(~5L, ExpressionEvaluator.eval("~5", Map.of(), 0));
    }

    @Test
    void short_circuiting_skips_unknown_symbol_in_dead_branch() {
        assertEquals(1L, ExpressionEvaluator.eval("1 || missing", Map.of(), 0));
        assertEquals(0L, ExpressionEvaluator.eval("0 && missing", Map.of(), 0));
    }

    @Test
    void unknown_symbol_in_live_branch_returns_null() {
        assertNull(ExpressionEvaluator.eval("missing + 1", Map.of(), 0));
    }

    @Test
    void division_by_zero_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.eval("9 / 0", Map.of(), 0));
        assertTrue(ex.getMessage().contains("Division by zero"));
    }

    @Test
    void malformed_expression_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.eval("1 = 2", Map.of(), 0));
        assertTrue(ex.getMessage().contains("did you mean '=='"));
    }
}
