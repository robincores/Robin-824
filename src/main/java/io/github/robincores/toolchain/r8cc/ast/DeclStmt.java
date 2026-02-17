package io.github.robincores.toolchain.r8cc.ast;

public record DeclStmt(String name, Expr init) implements Stmt {}
