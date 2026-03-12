package io.github.robincores.toolchain.r8cc.ast;

public record IfStmt(Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt {}
