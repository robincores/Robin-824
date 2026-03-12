package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.backend.r8.R816Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class R816DialectTest {

    @Test
    void pushConst_prefers_b_then_u_then_i() {
        R816Dialect d = new R816Dialect();

        assertEquals("  b -1", d.pushConst(-1));
        assertEquals("  b 42", d.pushConst(42));
        assertEquals("  u 200", d.pushConst(200));
        assertEquals("  i 0x1234", d.pushConst(0x1234));
    }

    @Test
    void prologue_homes_extra_params_and_shifts_first_local_after_them() {
        R816Dialect d = new R816Dialect();

        String prologue = d.funcPrologue(6);
        assertTrue(prologue.contains("stl w14"));
        assertTrue(prologue.contains("stl w4"), "arg4 should home into w4");
        assertTrue(prologue.contains("stl w5"), "arg5 should home into w5");

        assertEquals("  ldl w6", d.loadLocal(0), "first local should start after homed extra params");
        assertEquals("  stl w6", d.storeLocal(0), "first local should store after homed extra params");
    }

    @Test
    void loadParam_uses_registers_for_first_four_and_homed_slots_after_that() {
        R816Dialect d = new R816Dialect();
        d.funcPrologue(6);

        assertEquals("  ldl w0", d.loadParam(0));
        assertEquals("  ldl w3", d.loadParam(3));
        assertEquals("  ldl w4", d.loadParam(4));
        assertEquals("  ldl w5", d.loadParam(5));
    }

    @Test
    void call_moves_register_args_and_spills_extra_args_to_memory_stack() {
        R816Dialect d = new R816Dialect();

        String asm = d.call("foo", 6);

        int pushCount = asm.split("\\bpush\\b", -1).length - 1;
        assertEquals(2, pushCount, "argc=6 should spill arg4 and arg5 to memory stack");
        assertTrue(asm.contains("stl w3"));
        assertTrue(asm.contains("stl w2"));
        assertTrue(asm.contains("stl w1"));
        assertTrue(asm.contains("stl w0"));
        assertTrue(asm.contains("CALL foo"));
        assertTrue(asm.endsWith("ldl w0"), "call should leave result on operand stack");
    }

    @Test
    void ret_stores_return_value_and_uses_ret_macro() {
        R816Dialect d = new R816Dialect();
        assertEquals("  stl w0\n  RET", d.ret());
    }
}
