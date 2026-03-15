package io.github.robincores.toolchain.r8as;

public class AssemblerErrorResult implements AssemblerLineResult {
  String error;

  public AssemblerErrorResult(String error) {
    this.error = error;
  }
}
