package io.github.robincores.toolchain.r8cc.ast;

import java.util.List;

public record TranslationUnit(List<GlobalDecl> globals, List<FunctionDef> functions) implements AstNode {
    public TranslationUnit(List<FunctionDef> functions) { this(List.of(), functions); }
}
