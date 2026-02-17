package io.github.robincores.toolchain.r8cc.ast;

import java.util.List;

public record CallExpr(String fn, List<Expr> args) implements Expr {}
