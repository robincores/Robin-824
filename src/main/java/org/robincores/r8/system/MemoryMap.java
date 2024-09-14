package org.robincores.r8.system;

import org.robincores.r8.cpu.Memory;
import java.util.HashMap;
import java.util.Map;

public class MemoryMap implements Memory {
  private static class MemoryRegion {
    int startAddress;
    int size;
    Memory memory;

    MemoryRegion(int startAddress, int size, Memory memory) {
      this.startAddress = startAddress;
      this.size = size;
      this.memory = memory;
    }

    boolean contains(int address) {
      return address >= startAddress && address < startAddress + size;
    }
  }

  private final Map<Integer, MemoryRegion> memoryRegions = new HashMap<>();

  public void mapRegion(int startAddress, int size, Memory memory) {
    memoryRegions.put(startAddress, new MemoryRegion(startAddress, size, memory));
  }

  private MemoryRegion findRegion(int address) {
    return memoryRegions.values().stream()
        .filter(region -> region.contains(address))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No memory region mapped for address: " + address));
  }

  @Override
  public byte read(int address) {
    MemoryRegion region = findRegion(address);
    return region.memory.read(address - region.startAddress);
  }

  @Override
  public void write(int address, byte value) {
    MemoryRegion region = findRegion(address);
    region.memory.write(address - region.startAddress, value);
  }
}
