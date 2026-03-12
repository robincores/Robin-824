package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ir.IrFunction;
import io.github.robincores.toolchain.r8cc.ir.IrInstr;
import io.github.robincores.toolchain.r8cc.ir.IrProgram;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IrBuilderTest extends R8CcTestSupport {

    private static IrFunction fn(IrProgram program, String name) {
        return program.functions().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function: " + name));
    }

    @Test
    void ir_for_add_function_loads_two_params_and_applies_bin_add() {
        IrProgram ir = ir("""
                int add(int a, int b) { return a + b; }
                """);

        IrFunction add = fn(ir, "add");
        List<IrInstr> code = add.code();

        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.LoadParam p && p.index() == 0));
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.LoadParam p && p.index() == 1));
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.Bin b && b.op().equals("+")),
                "Expected BIN + in add()");
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.Ret),
                "Expected return instruction in add()");
    }

    @Test
    void ir_for_function_call_pushes_constants_and_emits_call() {
        IrProgram ir = ir("""
                int add(int a, int b) { return a + b; }
                int main() { return add(3, 4); }
                """);

        IrFunction main = fn(ir, "main");
        List<IrInstr> code = main.code();

        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.PushConst c && c.value() == 3));
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.PushConst c && c.value() == 4));
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.Call c && c.name().equals("add") && c.argc() == 2));
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.Ret));
    }

    @Test
    void ir_for_decl_and_return_uses_local_slot() {
        IrProgram ir = ir("""
                int main() {
                  int x = 3;
                  return x;
                }
                """);

        IrFunction main = fn(ir, "main");
        List<IrInstr> code = main.code();

        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.PushConst c && c.value() == 3));
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.StoreLocal s && s.wk() == 0),
                "Expected first local to use slot 0");
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.LoadLocal l && l.wk() == 0),
                "Expected return x to reload slot 0");
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.Ret));
    }

    @Test
    void ir_for_assignment_updates_existing_local() {
        IrProgram ir = ir("""
                int main() {
                  int x = 1;
                  x = x + 2;
                  return x;
                }
                """);

        IrFunction main = fn(ir, "main");
        List<IrInstr> code = main.code();

        long storeCount = code.stream().filter(i -> i instanceof IrInstr.StoreLocal s && s.wk() == 0).count();
        assertTrue(storeCount >= 2, "Expected declaration store and assignment store for local x");
        assertTrue(code.stream().anyMatch(i -> i instanceof IrInstr.Bin b && b.op().equals("+")));
    }

    @Test
    void ir_for_expression_statement_drops_unused_result() {
        IrProgram ir = ir("""
                int add(int a, int b) { return a + b; }
                int main() {
                  add(1, 2);
                  return 0;
                }
                """);

        IrFunction main = fn(ir, "main");
        assertTrue(main.code().stream().anyMatch(i -> i instanceof IrInstr.Pop1),
                "Expected expr statement result to be dropped");
    }
}
