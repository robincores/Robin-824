package io.github.robincores.toolchain.r8cc.backend.r8;

public interface R8AsmDialect {
    String preamble();
    String funcLabel(String name);

    /** Optional prologue inserted right after label. Can be empty. */
    String funcPrologue(int paramCount);

    String pushConst(int value);
    String loadParam(int index);

    String bin(String op);
    String un(String op);

    String call(String name, int argc);

    /** Can be multi-line (e.g., load return address then jr). */
    String ret();

    String loadLocal(int wk);
    String storeLocal(int wk);
    String pop1();
}
