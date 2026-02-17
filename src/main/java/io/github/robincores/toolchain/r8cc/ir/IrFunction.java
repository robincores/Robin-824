package io.github.robincores.toolchain.r8cc.ir;

import java.util.List;

public record IrFunction(String name, int paramCount, List<IrInstr> code) {
}
