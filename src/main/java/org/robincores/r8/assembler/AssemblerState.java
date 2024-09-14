package org.robincores.r8.assembler;

import java.util.List;

// Represents the state of the assembler during assembly
public class AssemblerState {
  int ip;                    // Instruction pointer
  int line;                  // Current line number in the source file
  int origin;                // Origin of the code (where the code starts)
  int codelen;               // Length of the code in bits
  Object intermediate;       // Intermediate data used during the assembly process (can be any type)
  List<Integer> output;      // Assembled output (machine code)
  List<AssemblerLine> lines; // List of lines in the assembly file
  List<AssemblerError> errors; // List of errors encountered during assembly
  List<AssemblerFixup> fixups; // List of unresolved symbols and addresses to fix up later

  // Constructor to initialize the assembler state
//  public AssemblerState(int ip, int line, int origin, int codelen, Object intermediate, List<Integer> output, List<AssemblerLine> lines, List<AssemblerError> errors, List<AssemblerFixup> fixups) {
//    this.ip = ip;
//    this.line = line;
//    this.origin = origin;
//    this.codelen = codelen;
//    this.intermediate = intermediate;
//    this.output = output;
//    this.lines = lines;
//    this.errors = errors;
//    this.fixups = fixups;
//  }

  public AssemblerState() {}
}
