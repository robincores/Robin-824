package io.github.robincores.toolchain.r8cc.ast;

public sealed interface Stmt extends AstNode permits BlockStmt, DeclStmt, ExprStmt, ReturnStmt {}
