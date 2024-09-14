package org.robincores.r8.system;

import javafx.scene.canvas.Canvas;
import org.robincores.r8.cpu.R824;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class R824System {
  private MemoryMap memoryMap;
  private R824 cpu;
  TimerDevice timer;

  private boolean running;

  public R824System(Canvas canvas) {
    memoryMap = new MemoryMap();
    configure();
  }

  // Method to configure the memory map of the system
  private void configure() {
    // 10MB System RAM, mapped at 0x400000
    RAM systemRAM = new RAM(10 * 1024 * 1024);  // 10MB of System RAM

    // Load the 64KB Boot ROM from a file
    //ROM bootROM = new ROM(new byte[]{/* Boot ROM data */});  // 64KB Boot ROM

    // 4MB Cartridge ROM or RAM Expansion (can be used for loading cartridges or additional RAM)
    //ROM cartridgeROM = new ROM(new byte[]{/* Cartridge ROM data or empty expansion */});  // 4MB Cartridge ROM

    // 1MB VRAM
    RAM vram = new RAM(1 * 1024 * 1024);  // 1MB of VRAM for video

    // Initialize the CPU with the configured memory map
    cpu = new R824(memoryMap);

    // Timer Device
    timer = new TimerDevice(cpu);

    // Map ROM, RAM, VRAM, and IO regions in the memory map
    //memoryMap.mapRegion(0x000000, 64 * 1024, bootROM);  // 64KB Boot ROM
    //memoryMap.mapRegion(0x010000, 4 * 1024 * 1024, cartridgeROM);  // 4MB Cartridge ROM / Expansion
    //memoryMap.mapRegion(0x400000, 10 * 1024 * 1024, systemRAM);  // 10MB System RAM
    memoryMap.mapRegion(0x000000, 10 * 1024 * 1024, systemRAM);  // 1MB System RAM
    memoryMap.mapRegion(0xE00000, 1 * 1024 * 1024, vram);  // 1MB VRAM

    // 1MB IO + Audio Buffers
    memoryMap.mapRegion(0xF00000, 8, timer);  // Timer mapped to address 0xF00000
  }

  // Method to load a binary program into RAM at the given address
  public void loadProgram(String filePath, int startAddress) throws IOException {
    byte[] program = Files.readAllBytes(Path.of(filePath));
    for (int i = 0; i < program.length; i++) {
      memoryMap.write(startAddress + i, program[i]);
    }
  }

  // Main loop for running the CPU
  public void run() {
    running = true;

    int cycles;
    while (running) {
      // Execute one instruction at a time
      cycles = cpu.executeInstruction();

      // Increment timers and check for interrupts
      timer.incrementTime(cycles);

      // Add timing/synchronization logic here if necessary
      // You can use Thread.sleep or a more precise timing mechanism for emulation
    }
  }

  // Method to stop the system
  public void stop() {
    running = false;
  }
}
