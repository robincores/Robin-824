package io.github.robincores.r8.system;

import io.github.robincores.r8.bus.BusMap;
import io.github.robincores.r8.cpu.R8Core;
import io.github.robincores.r8.debug.Debugger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Base class for R8 system configurations.
 *
 * <p><b>Master clock model</b>:
 * Fixed {@code BUS_HZ} drives all device timing and wall-clock pacing.
 * CPU runs at {@code CPU_HZ = BUS_HZ * cpuMul} (conceptually), and returns CPU cycles per instruction.
 * We convert CPU cycles to BUS cycles via carry:
 * <pre>
 *   cpuCarry += cpuCycles;
 *   busDelta  = cpuCarry / cpuMul;
 *   cpuCarry -= busDelta * cpuMul;
 * </pre>
 * and tick peripherals using BUS cycles only. HSYNC/VSYNC remain stable when cpuMul changes.</p>
 *
 * <p><b>Pacing</b>:
 * Runs ~1ms worth of BUS cycles per batch, then sleeps/spins to match real time.</p>
 */
public abstract class AbstractSystem implements R8System {

    /** CPU instance wired by the concrete system. */
    protected R8Core cpu;

    /** Bus instance wired by the concrete system. */
    protected BusMap bus;

    /** Tickable peripherals (PIT, VPU, APU, etc.). */
    private final List<Tickable> tickables = new ArrayList<>();

    /** Run-loop flag, cleared by {@link #stop()} or when the CPU halts (cycles==0). */
    private volatile boolean running;

    /**
     * Fixed BUS frequency in Hz (system master clock). Must be set once before run().
     * Kept volatile only so a debugger/UI can read it safely; runtime mutation is forbidden.
     */
    private volatile long busHz = 0L;

    /**
     * CPU clock multiplier relative to BUS_HZ. Must be >= 1.
     * Can be changed while running (takes effect quickly), but BUS_HZ cannot.
     */
    private volatile int cpuMul = 1;

    // Debugger (optional)
    private volatile Debugger debugger;

    /**
     * Set the fixed bus clock (required).
     * Must be called exactly once before {@link #run()} starts.
     */
    protected final void setBusHz(long busHz) {
        if (busHz <= 0) throw new IllegalArgumentException("busHz must be > 0");
        if (running) throw new IllegalStateException("Cannot change BUS_HZ while running");
        if (this.busHz != 0L && this.busHz != busHz) {
            throw new IllegalStateException("BUS_HZ already set to " + this.busHz + " (cannot change)");
        }
        this.busHz = busHz;
    }

    /** @return configured bus clock in Hz */
    protected final long busHz() {
        return busHz;
    }

    /**
     * Set CPU multiplier relative to bus clock.
     * Examples: 1 = baseline, 2 = 2× CPU, 4 = 4× CPU.
     */
    protected final void setCpuMul(int mul) {
        if (mul < 1 || mul > 64) throw new IllegalArgumentException("cpuMul out of range: " + mul);
        this.cpuMul = mul;
    }

    /** @return current CPU multiplier */
    protected final int cpuMul() {
        return cpuMul;
    }

    protected void init(R8Core cpu, BusMap bus) {
        this.cpu = cpu;
        this.bus = bus;

        Debugger dbg = this.debugger;
        if (dbg != null) cpu.setDebugger(dbg); // requires R8Core.setDebugger(Debugger)
    }

    protected void addTickable(Tickable t) {
        tickables.add(t);
    }

    // -------------------- loading --------------------

    @Override
    public void loadProgram(String filePath, int startAddress) throws IOException {
        loadProgramBytes(Files.readAllBytes(Path.of(filePath)), startAddress);
    }

    @Override
    public void loadProgramBytes(byte[] data, int startAddress) {
        if (bus == null) throw new IllegalStateException("Bus not initialized");
        int mask = bus.addrMask();
        long space = bus.addrSpace();

        long base = startAddress & 0xFFFF_FFFFL;
        long end = base + (long) data.length;
        if (end > space) {
            throw new IllegalArgumentException("Program overruns address space: start=0x"
                    + Integer.toHexString(startAddress) + " len=" + data.length);
        }

        int addr = startAddress & mask;
        for (byte b : data) {
            bus.write8(addr, b);
            addr = (addr + 1) & mask;
        }
    }

    // -------------------- run loop --------------------

    @Override
    public void run() {
        final long hz = this.busHz;
        if (hz <= 0) {
            throw new IllegalStateException("BUS_HZ not configured (call setBusHz(...) in your system constructor)");
        }

        running = true;
        try {
            long targetNs = System.nanoTime();
            long rem = 0L;        // fixed-point remainder for BUS->ns conversion

            long cpuCarry = 0L;   // CPU cycles carry for CPU->BUS conversion

            while (running) {
                final int mul = this.cpuMul; // cache for this batch (may be changed; effect is ~1ms granularity)

                // ~1ms worth of BUS cycles
                final long batchTargetBus = Math.max(1L, hz / 1_000L);
                long batchBus = 0L;

                Debugger dbg = this.debugger;

                if (mul == 1) {
                    // Fast path: CPU cycles == BUS cycles
                    while (running && batchBus < batchTargetBus) {
                        if (dbg != null) dbg.checkBefore(cpu);

                        int cpuCycles = cpu.executeInstruction();
                        if (cpuCycles == 0) { running = false; break; }

                        if (dbg != null) dbg.checkAfter(cpu);

                        tickDevices(cpuCycles);
                        batchBus += cpuCycles;
                    }
                } else {
                    // mul > 1: accumulate CPU cycles until at least 1 BUS cycle elapses
                    while (running && batchBus < batchTargetBus) {
                        if (dbg != null) dbg.checkBefore(cpu);

                        int cpuCycles = cpu.executeInstruction();
                        if (cpuCycles == 0) { running = false; break; }

                        if (dbg != null) dbg.checkAfter(cpu);

                        cpuCarry += (long) cpuCycles;

                        long busDelta = cpuCarry / (long) mul;
                        if (busDelta != 0) {
                            cpuCarry -= busDelta * (long) mul;

                            // In this architecture busDelta is always small (≈ hz/1000), so int cast is safe.
                            tickDevices((int) busDelta);
                            batchBus += busDelta;
                        }
                    }
                }

                if (!running) break;

                // Pace: convert BUS cycles to nanoseconds (fixed-point)
                long num = batchBus * 1_000_000_000L + rem;
                long batchNs = num / hz;
                rem = num - batchNs * hz;
                targetNs += batchNs;

                long now = System.nanoTime();
                long sleepNs = targetNs - now;

                if (sleepNs > 500_000L) {
                    LockSupport.parkNanos(sleepNs);
                } else if (sleepNs > 0) {
                    long until = now + sleepNs;
                    while (System.nanoTime() < until) Thread.onSpinWait();
                }
                // If behind (sleepNs <= 0), immediately continue to catch up.
            }
        } finally {
            running = false;
        }
    }

    private void tickDevices(int busCycles) {
        if (busCycles <= 0) return;
        for (Tickable t : tickables) t.tick(busCycles);
    }

    @Override
    public void stop() {
        running = false;

        // Important: if debugger parked the CPU thread, wake it so it can exit.
        Debugger dbg = this.debugger;
        if (dbg != null) dbg.resume(); // simplest reliable "unpark" without extra API
    }

    // -------------------- debugger API --------------------

    public final void setDebugger(Debugger dbg) {
        this.debugger = dbg;
        R8Core c = this.cpu;
        if (c != null) c.setDebugger(dbg);
    }

    public final Debugger debugger() { return debugger; }

    // UI access
    public final R8Core cpuRef() { return cpu; }
    public final BusMap busRef() { return bus; }
    public final boolean isRunning() { return running; }
}