package io.github.robincores.toolchain.r8cc.ast;

public sealed interface Expr extends AstNode permits IntLit, VarRef, BinOp, CallExpr, UnaryOp, StringLit, IndexExpr {}
