package org.robincores.r8.system;

import org.robincores.r8.cpu.Memory;

public class RAM implements Memory {
  private final byte[] ram;

  public RAM(int size) {
    ram = new byte[size];
  }

  @Override
  public byte read(int address) {
    return ram[address];
  }

  @Override
  public void write(int address, byte value) {
    ram[address] = value;
  }
}
