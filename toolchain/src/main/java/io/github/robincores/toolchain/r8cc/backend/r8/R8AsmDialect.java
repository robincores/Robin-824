package io.github.robincores.toolchain.r8cc.backend.r8;

public interface R8AsmDialect {
    String preamble();
    String funcLabel(String name);
    String funcPrologue(int paramCount, int frameBytes);
    String pushConst(int value);
    String loadParam(int index);
    String loadGlobal(String name, int sizeBytes);
    String storeGlobal(String name, int sizeBytes);
    String addrGlobal(String name);
    String bin(String op);
    String un(String op);
    String call(String name, int argc);
    String ret(int frameBytes);
    String loadLocal(int offsetBytes, int sizeBytes);
    String storeLocal(int offsetBytes, int sizeBytes);
    String addrLocal(int offsetBytes);
    String loadIndirect(int sizeBytes);
    String storeIndirectKeep(int sizeBytes);
    String pop1();
    String label(String name);
    String jmp(String target);
    String brIfZero(String target);
}
