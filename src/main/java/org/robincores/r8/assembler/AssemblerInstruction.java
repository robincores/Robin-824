package org.robincores.r8.assembler;

// Represents an instruction in the assembled output
class AssemblerInstruction extends AssemblerLineResult {
  int opcode;
  int nbits;

  public AssemblerInstruction(int opcode, int nbits) {
    this.opcode = opcode;
    this.nbits = nbits;
  }
}