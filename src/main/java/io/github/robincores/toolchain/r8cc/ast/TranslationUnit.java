package io.github.robincores.toolchain.r8cc.ast;

import java.util.List;

public record TranslationUnit(List<FunctionDef> functions) implements AstNode {}
