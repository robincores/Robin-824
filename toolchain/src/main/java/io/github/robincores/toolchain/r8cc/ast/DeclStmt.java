package io.github.robincores.toolchain.r8cc.ast;

public record DeclStmt(CType type, String name, ArraySpec array, Expr init) implements Stmt {}
