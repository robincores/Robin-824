package io.github.robincores.r8.system;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R8Core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all R8 system configurations.
 * <p>
 * Provides the shared run loop, program loading, and tickable device
 * management. Concrete subclasses (R816System, R824System, etc.) wire
 * up the bus, CPU, and peripherals in their constructors.
 * </p>
 */
public abstract class AbstractSystem implements R8System {

    protected R8Core cpu;
    protected BusMap bus;

    private final List<Tickable> tickables = new ArrayList<>();
    private volatile boolean running;

    /**
     * Optional real-time throttle.
     * If {@code cpuHz > 0}, the run-loop will pace execution so that
     * {@code 1 "cycle" == 1 CPU clock tick} at {@code cpuHz}.
     *
     * <p>Emulators often run "as fast as possible"; pacing is useful when
     * you want video/audio to behave at a stable real-time rate.
     */
    private volatile long cpuHz = 0L;

    /** Enable/disable real-time pacing. Use {@code 0} to disable. */
    protected final void setCpuHz(long cpuHz) {
        this.cpuHz = Math.max(0L, cpuHz);
    }

    protected void init(R8Core cpu, BusMap bus) {
        this.cpu = cpu;
        this.bus = bus;
    }

    /**
     * Register a tickable device (Timer, VPU, UART, etc.).
     * Tickables are advanced after each CPU instruction in registration order.
     */
    protected void addTickable(Tickable t) {
        tickables.add(t);
    }

    @Override
    public void loadProgram(String filePath, int startAddress) throws IOException {
        loadProgramBytes(Files.readAllBytes(Path.of(filePath)), startAddress);
    }

    @Override
    public void loadProgramBytes(byte[] data, int startAddress) {
        for (int i = 0; i < data.length; i++) {
            bus.write8(startAddress + i, data[i]);
        }
    }

    @Override
    public void run() {
        running = true;

        final long hz = cpuHz;
        final long startNs = (hz > 0) ? System.nanoTime() : 0L;
        long emuCycles = 0L;

        while (running) {
            int cycles = cpu.executeInstruction();

            // If you keep "halted returns 0", this stops the system.
            // Recommended: implement "HLT = sleep" in CPU and return 1 idle cycle instead.
            if (cycles == 0) break;

            // ---- Optional pacing ----
            if (hz > 0) {
                emuCycles += (long) cycles;
                long targetElapsedNs = (emuCycles * 1_000_000_000L) / hz;
                long nowElapsedNs = System.nanoTime() - startNs;
                long sleepNs = targetElapsedNs - nowElapsedNs;
                if (sleepNs > 0) {
                    // parkNanos is precise enough here; avoids busy-waiting.
                    java.util.concurrent.locks.LockSupport.parkNanos(sleepNs);
                }
            }

            for (Tickable t : tickables) t.tick(cycles);
        }
    }

    @Override
    public void stop() {
        running = false;
    }
}
