package io.github.robincores.toolchain.r8as;

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

  // inside AssemblerState
  public List<AssemblerError> getErrors() { return errors; }
  public List<Integer> getOutput() { return output; }

  // optional, nice-to-have:
  public Object getIntermediate() { return intermediate; }
  public List<AssemblerLine> getLines() { return lines; }
  public List<AssemblerFixup> getFixups() { return fixups; }

  public AssemblerState() {}
}
