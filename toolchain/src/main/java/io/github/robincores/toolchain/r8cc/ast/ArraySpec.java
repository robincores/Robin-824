package io.github.robincores.toolchain.r8cc.ast;

public record ArraySpec(Integer length) {
    public boolean hasExplicitLength() { return length != null; }
}
