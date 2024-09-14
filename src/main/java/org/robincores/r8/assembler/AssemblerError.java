package org.robincores.r8.assembler;

// Represents an assembler error
class AssemblerError {
  String msg;
  int line;

  public AssemblerError(String msg, int line) {
    this.msg = msg;
    this.line = line;
  }
}