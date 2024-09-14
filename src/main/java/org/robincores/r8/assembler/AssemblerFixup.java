package org.robincores.r8.assembler;

// Represents a fixup (symbol resolution during assembly)
class AssemblerFixup {
  String sym;
  int ofs;
  int size;
  int srcofs;
  int dstofs;
  int dstlen;
  int line;
  boolean iprel;
  int ipofs;
  int ipmul;
  String endian;

  public AssemblerFixup(String sym, int ofs, int size, int srcofs, int dstofs, int dstlen, int line, boolean iprel, int ipofs, int ipmul, String endian) {
    this.sym = sym;
    this.ofs = ofs;
    this.size = size;
    this.srcofs = srcofs;
    this.dstofs = dstofs;
    this.dstlen = dstlen;
    this.line = line;
    this.iprel = iprel;
    this.ipofs = ipofs;
    this.ipmul = ipmul;
    this.endian = endian;
  }

  @Override
  public String toString() {
    return "AssemblerFixup{" +
        "sym='" + sym + '\'' +
        ", ofs=" + ofs +
        ", size=" + size +
        ", srcofs=" + srcofs +
        ", dstofs=" + dstofs +
        ", dstlen=" + dstlen +
        ", line=" + line +
        ", iprel=" + iprel +
        ", ipofs=" + ipofs +
        ", ipmul=" + ipmul +
        ", endian='" + endian + '\'' +
        '}';
  }
}