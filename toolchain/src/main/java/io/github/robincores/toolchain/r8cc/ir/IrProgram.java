package io.github.robincores.toolchain.r8cc.ir;

import java.util.List;

public record IrProgram(List<IrGlobal> globals, List<IrFunction> functions) {
    public IrProgram(List<IrFunction> functions) { this(List.of(), functions); }
}
