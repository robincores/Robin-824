package org.robincores.r8.assembler;

import java.util.List;
import java.util.Map;

// Represents the assembler specification (rules and variables)
class AssemblerSpec {
  String name;
  int width;
  Map<String, AssemblerVar> vars;
  List<AssemblerRule> rules;

  public AssemblerSpec(String name, int width, Map<String, AssemblerVar> vars, List<AssemblerRule> rules) {
    this.name = name;
    this.width = width;
    this.vars = vars;
    this.rules = rules;
  }
}