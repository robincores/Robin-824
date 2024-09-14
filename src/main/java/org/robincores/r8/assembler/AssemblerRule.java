package org.robincores.r8.assembler;

import java.util.List;
import java.util.regex.Pattern;

// Represents an assembler rule (instruction format and bit pattern)
public class AssemblerRule {
  String fmt;
  List<Object> bits;

  // Transient field to avoid serialization/deserialization by Gson
  transient Pattern re;  // Regular expression pattern for matching instructions
  String prefix;
  List<String> varlist;

  public AssemblerRule(String fmt, List<Object> bits) {
    this.fmt = fmt;
    this.bits = bits;
  }

  @Override
  public String toString() {
    return "AssemblerRule{" +
        "fmt='" + fmt + '\'' +
        ", bits=" + bits +
        ", re=" + re +
        ", prefix='" + prefix + '\'' +
        ", varlist=" + varlist +
        '}';
  }
}
