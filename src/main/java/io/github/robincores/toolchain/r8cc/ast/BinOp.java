package io.github.robincores.toolchain.r8cc.ast;

public record BinOp(String op, Expr left, Expr right) implements Expr {}
