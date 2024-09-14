package org.robincores.r8.assembler;

public class AssemblerLine {
  int line;
  int offset;
  int nbits;
  String insns;

  // Constructor for AssemblerLine
  public AssemblerLine(int line, int offset, int nbits) {
    this.line = line;
    this.offset = offset;
    this.nbits = nbits;
    this.insns = "";  // Initialize empty instruction string
  }
}
