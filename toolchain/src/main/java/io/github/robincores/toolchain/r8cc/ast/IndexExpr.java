package io.github.robincores.toolchain.r8cc.ast;

public record IndexExpr(Expr base, Expr index) implements Expr {}
