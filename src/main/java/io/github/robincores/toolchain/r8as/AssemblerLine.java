package io.github.robincores.toolchain.r8as;

public class AssemblerLine {
    int line;
    int offset;
    int nbits;
    String insns;

    // Section this listing line belongs to (e.g. ".text", ".data", ".bss")
    String section;

    public AssemblerLine(int line, int offset, int nbits) {
        this(line, offset, nbits, ".text");
    }

    public AssemblerLine(int line, int offset, int nbits, String section) {
        this.line = line;
        this.offset = offset;
        this.nbits = nbits;
        this.insns = "";
        this.section = section;
    }
}
