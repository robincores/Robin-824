package io.github.robincores.toolchain.r8cc.ast;

import java.util.List;

public record FunctionDef(CType returnType, String name, List<Param> params, Stmt body) implements AstNode {
    public record Param(CType type, String name, ArraySpec array) {}
}
