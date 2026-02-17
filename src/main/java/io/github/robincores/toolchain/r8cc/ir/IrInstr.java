package io.github.robincores.toolchain.r8cc.ir;

public sealed interface IrInstr permits
        IrInstr.PushConst,
        IrInstr.LoadParam,
        IrInstr.Bin,
        IrInstr.Un,
        IrInstr.Call,
        IrInstr.Ret,
        IrInstr.LoadLocal,
        IrInstr.StoreLocal,
        IrInstr.Pop1 {

    record PushConst(int value) implements IrInstr {
    }

    record LoadParam(int index) implements IrInstr {
    }

    /**
     * Binary op consumes two stack values, pushes one result.
     */
    record Bin(String op) implements IrInstr {
    }

    /**
     * Unary op consumes one stack value, pushes one result.
     */
    record Un(String op) implements IrInstr {
    }

    record Call(String name, int argc) implements IrInstr {
    }

    record Ret() implements IrInstr {
    }

    record LoadLocal(int wk) implements IrInstr {
    }   // wksp index (0..15)

    record StoreLocal(int wk) implements IrInstr {
    }  // wksp index (0..15)

    record Pop1() implements IrInstr {
    }  // drop top-of-reg-stack
}
