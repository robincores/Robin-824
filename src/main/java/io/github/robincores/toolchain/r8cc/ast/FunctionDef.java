package io.github.robincores.toolchain.r8cc.ast;

import java.util.List;

public record FunctionDef(String name, List<Param> params, Stmt body) implements AstNode {
    public record Param(String name) {}
}
