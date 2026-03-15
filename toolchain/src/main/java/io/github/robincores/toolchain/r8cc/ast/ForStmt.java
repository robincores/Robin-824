package io.github.robincores.toolchain.r8cc.ast;

public record ForStmt(Stmt init, Expr cond, Expr post, Stmt body) implements Stmt {
}