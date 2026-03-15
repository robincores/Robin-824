package io.github.robincores.toolchain.r8cc.ir;

public sealed interface IrInstr permits
        IrInstr.PushConst,
        IrInstr.LoadParam,
        IrInstr.LoadGlobal,
        IrInstr.StoreGlobal,
        IrInstr.AddrGlobal,
        IrInstr.Bin,
        IrInstr.Un,
        IrInstr.Call,
        IrInstr.Ret,
        IrInstr.LoadLocal,
        IrInstr.StoreLocal,
        IrInstr.AddrLocal,
        IrInstr.LoadIndirect,
        IrInstr.StoreIndirectKeep,
        IrInstr.Pop1,
        IrInstr.Label,
        IrInstr.Jmp,
        IrInstr.BrIfZero {

    record PushConst(int value) implements IrInstr {}
    record LoadParam(int index) implements IrInstr {}
    record LoadGlobal(String name, int sizeBytes) implements IrInstr {}
    record StoreGlobal(String name, int sizeBytes) implements IrInstr {}
    record AddrGlobal(String name) implements IrInstr {}
    record Bin(String op) implements IrInstr {}
    record Un(String op) implements IrInstr {}
    record Call(String name, int argc) implements IrInstr {}
    record Ret() implements IrInstr {}
    record LoadLocal(int offsetBytes, int sizeBytes) implements IrInstr {}
    record StoreLocal(int offsetBytes, int sizeBytes) implements IrInstr {}
    record AddrLocal(int offsetBytes) implements IrInstr {}
    record LoadIndirect(int sizeBytes) implements IrInstr {}
    record StoreIndirectKeep(int sizeBytes) implements IrInstr {}
    record Pop1() implements IrInstr {}
    record Label(String name) implements IrInstr {}
    record Jmp(String target) implements IrInstr {}
    record BrIfZero(String target) implements IrInstr {}
}
