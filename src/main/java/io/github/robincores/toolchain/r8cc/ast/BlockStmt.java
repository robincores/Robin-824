package io.github.robincores.toolchain.r8cc.ast;

import java.util.List;

public record BlockStmt(List<Stmt> stmts) implements Stmt {}
