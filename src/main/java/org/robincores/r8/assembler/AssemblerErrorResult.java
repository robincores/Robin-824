package org.robincores.r8.assembler;

public class AssemblerErrorResult extends AssemblerLineResult {
  String error;

  public AssemblerErrorResult(String error) {
    this.error = error;
  }
}
