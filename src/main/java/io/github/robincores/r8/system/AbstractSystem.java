package io.github.robincores.r8.system;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R8Core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Base class for R8 system configurations.
 *
 * <p>Provides the shared run-loop, program loading, and {@link Tickable} device ticking.
 * Concrete systems wire {@link #cpu} + {@link #bus} via {@link #init(R8Core, BusMap)} and
 * register peripherals via {@link #addTickable(Tickable)}.</p>
 *
 * <p><b>Tick strategy:</b> after each instruction, peripherals are ticked with the instruction
 * cycle count returned by the CPU.</p>
 *
 * <p><b>Pacing strategy (cpuHz &gt; 0):</b> batch-and-sleep to avoid per-instruction sleeps.
 * Executes ~1ms worth of cycles per batch, then sleeps (≥0.5ms) or spin-waits (&lt;0.5ms) to
 * match wall-clock time; if behind, it immediately continues to catch up. Periodically rebases
 * counters to prevent {@code (emuCycles * 1e9)} overflow on long runs.</p>
 */
public abstract class AbstractSystem implements R8System {

    /**
     * CPU instance wired by the concrete system.
     */
    protected R8Core cpu;

    /**
     * Bus instance wired by the concrete system.
     */
    protected BusMap bus;

    /**
     * Tickable peripherals (timer, VPU, APU, etc.).
     */
    private final List<Tickable> tickables = new ArrayList<>();

    /**
     * Run-loop flag, cleared by {@link #stop()} or when the CPU halts (cycles==0).
     */
    private volatile boolean running;

    /**
     * Target CPU frequency in Hz for pacing; {@code 0} means run as fast as possible.
     */
    private volatile long cpuHz = 0L;

    /**
     * Enable/disable real-time pacing.
     *
     * @param cpuHz target CPU frequency in Hz; use {@code 0} to disable pacing
     */
    protected final void setCpuHz(long cpuHz) {
        this.cpuHz = Math.max(0L, cpuHz);
    }

    /**
     * Wire the CPU and bus into this base system.
     *
     * @param cpu configured CPU
     * @param bus configured memory map / bus
     */
    protected void init(R8Core cpu, BusMap bus) {
        this.cpu = cpu;
        this.bus = bus;
    }

    /**
     * Register a tickable device.
     *
     * <p>Tickables are advanced after each instruction in registration order.</p>
     *
     * @param t device to tick
     */
    protected void addTickable(Tickable t) {
        tickables.add(t);
    }

    @Override
    public void loadProgram(String filePath, int startAddress) throws IOException {
        loadProgramBytes(Files.readAllBytes(Path.of(filePath)), startAddress);
    }

    /**
     * Load a program image by writing bytes through the bus.
     *
     * @param data         program bytes
     * @param startAddress load address
     */
    @Override
    public void loadProgramBytes(byte[] data, int startAddress) {
        for (int i = 0; i < data.length; i++) {
            bus.write8(startAddress + i, data[i]);
        }
    }

    /**
     * Run until the CPU halts (cycles==0) or {@link #stop()} is called.
     *
     * <p>Fast mode (cpuHz==0): executes continuously.</p>
     * <p>Paced mode (cpuHz&gt;0): executes ~1ms batches and then sleep/spin-waits to match real time.</p>
     */
    @Override
    public void run() {
        running = true;

        final long hz = cpuHz;
        if (hz <= 0) {
            // Fast-as-possible execution
            while (running) {
                int cycles = cpu.executeInstruction();
                if (cycles == 0) {
                    running = false;
                    break;
                }
                for (Tickable t : tickables) t.tick(cycles);
            }
            return;
        }

        // Paced execution (~real-time)
        long startNs = System.nanoTime();
        long emuCycles = 0L;

        final long batchTarget = Math.max(1L, hz / 1_000L); // ~1ms worth of cycles

        while (running) {
            long batchCycles = 0L;

            // Execute a batch, ticking peripherals per instruction.
            while (running && batchCycles < batchTarget) {
                int cycles = cpu.executeInstruction();
                if (cycles == 0) {
                    running = false;
                    break;
                }

                for (Tickable t : tickables) t.tick(cycles);
                batchCycles += (long) cycles;
            }

            if (!running) break;

            emuCycles += batchCycles;

            // Rebase to prevent long overflow in (emuCycles * 1e9).
            if (emuCycles > 1_000_000_000L) {
                long consumedNs = (emuCycles * 1_000_000_000L) / hz;
                startNs += consumedNs;
                emuCycles = 0L;
            }

            long targetElapsedNs = (emuCycles * 1_000_000_000L) / hz;
            long nowElapsedNs = System.nanoTime() - startNs;
            long sleepNs = targetElapsedNs - nowElapsedNs;

            if (sleepNs > 500_000L) {
                // >= 0.5ms: let the OS schedule.
                LockSupport.parkNanos(sleepNs);
            } else if (sleepNs > 0) {
                // < 0.5ms: spin; sub-millisecond sleeps are unreliable on many OSes.
                final long until = System.nanoTime() + sleepNs;
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
            }
            // If sleepNs <= 0, we're behind; immediately start the next batch (catch up).
        }
    }

    /**
     * Request the run-loop to stop (safe from other threads).
     */
    @Override
    public void stop() {
        running = false;
    }
}