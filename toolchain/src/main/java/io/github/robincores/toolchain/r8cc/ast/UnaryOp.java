package io.github.robincores.toolchain.r8cc.ast;

public record UnaryOp(String op, Expr expr) implements Expr {
}
