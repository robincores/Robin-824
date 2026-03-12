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
        IrInstr.Pop1,
        IrInstr.Label,
        IrInstr.Jmp,
        IrInstr.BrIfZero {

    record PushConst(int value) implements IrInstr {}
    record LoadParam(int index) implements IrInstr {}
    record Bin(String op) implements IrInstr {}
    record Un(String op) implements IrInstr {}
    record Call(String name, int argc) implements IrInstr {}
    record Ret() implements IrInstr {}
    record LoadLocal(int wk) implements IrInstr {}
    record StoreLocal(int wk) implements IrInstr {}
    record Pop1() implements IrInstr {}

    record Label(String name) implements IrInstr {}
    record Jmp(String target) implements IrInstr {}
    record BrIfZero(String target) implements IrInstr {}
}
