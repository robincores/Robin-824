package org.robincores.r8.assembler;

// Represents a slice in the bit pattern (e.g., for variable operands)
class AssemblerRuleSlice {
  int a; // argument index
  int b; // bit index
  int n; // number of bits

  public AssemblerRuleSlice(int a, int b, int n) {
    this.a = a;
    this.b = b;
    this.n = n;
  }
}