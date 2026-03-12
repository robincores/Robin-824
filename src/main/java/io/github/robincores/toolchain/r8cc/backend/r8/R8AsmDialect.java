package io.github.robincores.toolchain.r8cc.backend.r8;

public interface R8AsmDialect {
    String preamble();
    String funcLabel(String name);

    String funcPrologue(int paramCount);

    String pushConst(int value);
    String loadParam(int index);

    String bin(String op);
    String un(String op);

    String call(String name, int argc);
    String ret();

    String loadLocal(int wk);
    String storeLocal(int wk);
    String pop1();

    String label(String name);
    String jmp(String target);
    String brIfZero(String target);
}
