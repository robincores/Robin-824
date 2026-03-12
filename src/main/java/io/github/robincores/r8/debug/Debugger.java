package io.github.robincores.r8.debug;

import io.github.robincores.r8.cpu.R8Core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

public final class Debugger {

    public enum Mode { RUN, PAUSE, STEP }

    private final Set<Integer> breakpoints = ConcurrentHashMap.newKeySet();

    private volatile Mode mode = Mode.RUN;
    private volatile Thread cpuThread;
    private volatile String lastStopReason = "";

    // Temporary breakpoint used for "Run→" (run-to-cursor)
    private volatile int tempBreakpoint = -1;

    // Bound from CPU at runtime (so this debugger works for R816/R824/R832/...)
    private volatile int addrMask = 0xFFFF;
    private volatile int addrHexDigits = 4;

    public Mode mode() { return mode; }
    public String lastStopReason() { return lastStopReason; }

    private void bindCpuShape(R8Core cpu) {
        int m = cpu.addrMask();
        if (m != this.addrMask) {
            this.addrMask = m;

            // 0xFFFF -> 4, 0xFF_FFFF -> 6, 0xFFFF_FFFF -> 8
            int bits = 32 - Integer.numberOfLeadingZeros(m);
            int digits = (bits + 3) / 4;
            this.addrHexDigits = Math.max(1, Math.min(8, digits));
        }
    }

    public void attachCpuThreadIfNeeded() {
        if (cpuThread == null) cpuThread = Thread.currentThread();
    }

    public void pause(String reason) {
        lastStopReason = (reason == null) ? "" : reason;
        mode = Mode.PAUSE;
    }

    public void resume() {
        mode = Mode.RUN;
        unparkCpu();
    }

    public void step() {
        mode = Mode.STEP;
        unparkCpu();
    }

    /**
     * Run until {@code addr} is reached, then pause.
     * Implemented as a temporary breakpoint that clears itself when hit.
     */
    public void runTo(int addr) {
        tempBreakpoint = addr;
        mode = Mode.RUN;
        unparkCpu();
    }

    public void clearRunTo() {
        tempBreakpoint = -1;
    }

    // ------------------------------------------------------------
    // Breakpoints
    // ------------------------------------------------------------

    public boolean toggleBreakpoint(int addr) {
        int a = addr & addrMask;
        if (breakpoints.remove(a)) return false;
        breakpoints.add(a);
        return true;
    }

    public boolean addBreakpoint(int addr) {
        return breakpoints.add(addr & addrMask);
    }

    public boolean removeBreakpoint(int addr) {
        return breakpoints.remove(addr & addrMask);
    }

    public Set<Integer> breakpointsView() {
        return breakpoints;
    }

    public void clearBreakpoints() {
        breakpoints.clear();
    }

    // ------------------------------------------------------------
    // CPU thread integration
    // ------------------------------------------------------------

    /** Call before executing an instruction (CPU thread). */
    public void checkBefore(R8Core cpu) {
        attachCpuThreadIfNeeded();
        bindCpuShape(cpu);

        if (mode == Mode.PAUSE) {
            parkLoop();
            return;
        }

        // Only check breakpoints in RUN (not during STEP).
        if (mode == Mode.RUN) {
            int ip = cpu.ip() & addrMask; // address of next instruction

            int tb = tempBreakpoint;
            if (tb >= 0 && ip == (tb & addrMask)) {
                tempBreakpoint = -1;
                pause("run→ @0x" + hexAddr(ip));
                parkLoop();
                return;
            }

            if (breakpoints.contains(ip)) {
                pause("breakpoint @0x" + hexAddr(ip));
                parkLoop();
            }
        }
    }

    /** Call after executing an instruction (CPU thread). */
    public void checkAfter(R8Core cpu) {
        attachCpuThreadIfNeeded();
        bindCpuShape(cpu);

        if (mode == Mode.STEP) {
            // After exactly one instruction, stop.
            pause("step @0x" + hexAddr(cpu.ip() & addrMask));
            parkLoop();
        }
    }

    /**
     * Call when the CPU enters a trap/interrupt vector (CPU thread).
     * For traps like EBREAK/ECALL/ILLEGAL, the meaningful PC is MEPC.
     */
    public void onTrap(R8Core cpu) {
        attachCpuThreadIfNeeded();
        bindCpuShape(cpu);
        tempBreakpoint = -1; // cancel run-to-cursor

        int pc = cpu.mepc() & addrMask;
        pause(cpu.mcauseText() + " @0x" + hexAddr(pc));
        parkLoop();
    }

    /** Call specifically for EBREAK (CPU thread). */
    public void onEbreak(R8Core cpu) {
        attachCpuThreadIfNeeded();
        bindCpuShape(cpu);
        tempBreakpoint = -1; // cancel run-to-cursor

        int pc = cpu.mepc() & addrMask;
        pause("ebreak @0x" + hexAddr(pc));
        parkLoop();
    }

    // ------------------------------------------------------------

    private void parkLoop() {
        while (mode == Mode.PAUSE) {
            LockSupport.parkNanos(this, 25_000_000L);
        }
    }

    private void unparkCpu() {
        Thread t = cpuThread;
        if (t != null) LockSupport.unpark(t);
    }

    private String hexAddr(int v) {
        return String.format("%0" + addrHexDigits + "X", v & addrMask);
    }
}
