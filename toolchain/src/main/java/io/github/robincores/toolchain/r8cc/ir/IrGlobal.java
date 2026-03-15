package io.github.robincores.toolchain.r8cc.ir;

public record IrGlobal(String name, byte[] initBytes, int alignBytes, boolean readOnly) {}
