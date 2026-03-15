package io.github.robincores.toolchain.r8cc.ast;

public record GlobalDecl(CType type, String name, ArraySpec array, Expr init) implements AstNode {}
