package org.robincores.r8.assembler;

public class Symbol {
  public int value;

  // Constructor
  public Symbol(int value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return "Symbol{" +
        "value=" + value +
        '}';
  }
}
