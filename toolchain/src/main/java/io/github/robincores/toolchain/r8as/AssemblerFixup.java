package io.github.robincores.toolchain.r8as;

// Represents a fixup (symbol resolution during assembly)
//
// NOTE: Fixups are often resolved at finish(), after many files have been included.
// We therefore store the originating source/column (and optional context) so
// diagnostics point to the correct file, not the top-level assembler entry.
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

  // Better diagnostics (origin of this fixup)
  String source;  // file path / logical source name
  int col;        // 1-based
  String ctx;     // optional: instruction/directive text

  public AssemblerFixup(
      String sym,
      int ofs,
      int size,
      int srcofs,
      int dstofs,
      int dstlen,
      int line,
      boolean iprel,
      int ipofs,
      int ipmul,
      String endian,
      String source,
      int col,
      String ctx
  ) {
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

    this.source = source;
    this.col = (col <= 0) ? 1 : col;
    this.ctx = ctx;
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
        ", source='" + source + '\'' +
        ", col=" + col +
        ", ctx='" + ctx + '\'' +
        '}';
  }
}
