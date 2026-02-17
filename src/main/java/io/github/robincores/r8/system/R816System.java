package io.github.robincores.r8.system;

import javafx.scene.canvas.Canvas;
import io.github.robincores.r8.cpu.R816;

import java.io.IOException;

public class R816System implements R8CoreSystem {

    private MemoryMap memoryMap;
    private R816 cpu;
    TimerDevice timer;

    public R816System(Canvas canvas) {
        memoryMap = new MemoryMap();
        configure();
    }

    // Method to configure the memory map of the system
    private void configure() {
        // 64KB System RAM, mapped at 0x0000
        RAM systemRAM = new RAM(64 * 1024);  // 10MB of System RAM

        // Initialize the CPU with the configured memory map
        cpu = new R816(memoryMap);

        // Timer Device
        timer = new TimerDevice(cpu);
    }

    @Override
    public void loadProgram(String filePath, int startAddress) throws IOException {

    }

    @Override
    public void run() {

    }

    @Override
    public void stop() {

    }
}
