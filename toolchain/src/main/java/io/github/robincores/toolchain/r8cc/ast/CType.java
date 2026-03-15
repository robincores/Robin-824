package io.github.robincores.toolchain.r8cc.ast;

public record CType(Base base, int ptrDepth) {
    public enum Base { INT, CHAR }

    public static final CType INT = new CType(Base.INT, 0);
    public static final CType CHAR = new CType(Base.CHAR, 0);

    public int sizeBytes() { return ptrDepth > 0 ? 2 : (base == Base.CHAR ? 1 : 2); }
    public int alignBytes() { return sizeBytes(); }
    public boolean isPointer() { return ptrDepth > 0; }
    public CType addressOf() { return new CType(base, ptrDepth + 1); }
    public CType deref() {
        if (ptrDepth <= 0) throw new IllegalStateException("Cannot dereference non-pointer type: " + this);
        return new CType(base, ptrDepth - 1);
    }
    @Override public String toString() { return (base == Base.INT ? "int" : "char") + "*".repeat(ptrDepth); }
}
