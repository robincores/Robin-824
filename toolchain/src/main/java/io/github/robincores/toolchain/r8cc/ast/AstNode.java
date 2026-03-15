package io.github.robincores.toolchain.r8cc.ast;

public sealed interface AstNode permits TranslationUnit, GlobalDecl, FunctionDef, Stmt, Expr {}
