package org.robincores.r8.assembler;

import java.util.List;

// Represents a variable (immediate, relative, etc.) with bit size and properties
class AssemblerVar {
  int bits;
  List<String> toks;
  String endian;
  boolean iprel;
  int ipofs;
  int ipmul;

  public AssemblerVar(int bits, List<String> toks, String endian, boolean iprel, int ipofs, int ipmul) {
    this.bits = bits;
    this.toks = toks;
    this.endian = endian;
    this.iprel = iprel;
    this.ipofs = ipofs;
    this.ipmul = ipmul;
  }
}
