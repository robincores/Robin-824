package org.robincores.r8.system;

import org.robincores.r8.cpu.Memory;

public class ROM implements Memory {
  private final byte[] rom;

  public ROM(byte[] data) {
    rom = data;
  }

  @Override
  public byte read(int address) {
    return rom[address];
  }

  @Override
  public void write(int address, byte value) {
    // ROM is read-only, so writing does nothing.
    System.err.println("Attempted to write to ROM at address: " + address + ". This is not allowed.");
  }
}
