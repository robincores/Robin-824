package io.github.robincores.toolchain.r8cc.ast;

public record WhileStmt(Expr condition, Stmt body) implements Stmt {}
