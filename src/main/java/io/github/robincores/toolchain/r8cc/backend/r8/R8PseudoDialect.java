package io.github.robincores.toolchain.r8cc.backend.r8;

public final class R8PseudoDialect implements R8AsmDialect {

    @Override
    public String preamble() {
        return """
      ; r8cc pseudo-asm
      ; Next step: map these ops to real R816/R824/... mnemonics or assembler macros.
      """;
    }

    @Override public String funcLabel(String name) { return name + ":"; }

    @Override public String funcPrologue(int paramCount) { return ""; }

    @Override public String pushConst(int value) { return "  PUSH_CONST " + value; }

    @Override public String loadParam(int index) { return "  LOAD_PARAM " + index; }

    @Override public String bin(String op) { return "  BIN " + op; }

    @Override public String un(String op) { return "  UN " + op; }

    @Override public String call(String name, int argc) { return "  CALL " + name + " " + argc; }

    @Override public String ret() { return "  RET"; }

    @Override public String loadLocal(int wk)  { return "  LOAD_LOCAL w" + wk; }
    @Override public String storeLocal(int wk) { return "  STORE_LOCAL w" + wk; }
    @Override public String pop1()             { return "  POP1"; }
}
