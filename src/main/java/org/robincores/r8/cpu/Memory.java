package org.robincores.r8.cpu;

public interface Memory {
  byte read(int address);
  void write(int address, byte value);
}
