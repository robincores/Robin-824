package io.github.robincores.toolchain.r8cc.backend.r8;

public final class R8PseudoDialect implements R8AsmDialect {
    @Override public String preamble() { return "; pseudo asm"; }
    @Override public String funcLabel(String name) { return name + ":"; }
    @Override public String funcPrologue(int paramCount, int frameBytes) { return "  ; prologue params=" + paramCount + " frame=" + frameBytes; }
    @Override public String pushConst(int value) { return "  PUSH_CONST " + value; }
    @Override public String loadParam(int index) { return "  LOAD_PARAM w" + index; }
    @Override public String loadGlobal(String name, int sizeBytes) { return "  LOAD_GLOBAL " + name + "/" + sizeBytes; }
    @Override public String storeGlobal(String name, int sizeBytes) { return "  STORE_GLOBAL " + name + "/" + sizeBytes; }
    @Override public String addrGlobal(String name) { return "  ADDR_GLOBAL " + name; }
    @Override public String bin(String op) { return "  BIN " + op; }
    @Override public String un(String op) { return "  UN " + op; }
    @Override public String call(String name, int argc) { return "  CALL " + name + " " + argc; }
    @Override public String ret(int frameBytes) { return "  RET ; frame=" + frameBytes; }
    @Override public String loadLocal(int offsetBytes, int sizeBytes) { return "  LOAD_LOCAL @" + offsetBytes + "/" + sizeBytes; }
    @Override public String storeLocal(int offsetBytes, int sizeBytes) { return "  STORE_LOCAL @" + offsetBytes + "/" + sizeBytes; }
    @Override public String addrLocal(int offsetBytes) { return "  ADDR_LOCAL @" + offsetBytes; }
    @Override public String loadIndirect(int sizeBytes) { return "  LOAD_INDIRECT/" + sizeBytes; }
    @Override public String storeIndirectKeep(int sizeBytes) { return "  STORE_INDIRECT_KEEP/" + sizeBytes; }
    @Override public String pop1() { return "  POP1"; }
    @Override public String label(String name) { return name + ":"; }
    @Override public String jmp(String target) { return "  JMP " + target; }
    @Override public String brIfZero(String target) { return "  BR_IF_ZERO " + target; }
}
