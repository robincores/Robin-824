package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;
import io.github.robincores.r8.system.InterruptSink;
import io.github.robincores.r8.system.Tickable;

import java.util.Arrays;

/**
 * PIT — 16-byte command-port timer for R8 systems (8-bit bus friendly).
 *
 * <p><b>Clock model:</b> PIT runs from the fixed <b>BUS clock</b>. MTIME advances at a programmable
 * tick rate (default 1 MHz) using fixed-point accumulation from BUS cycles.
 *
 * <p><b>Write protocol:</b> write operands to 0x01..0x0F, then write opcode to 0x00.
 * <p><b>Read protocol:</b> read 0x00 for STATUS, read 0x01..0x0F from the selected window.
 */
public final class PIT implements BusDevice, Tickable {

    // MMIO (16 bytes)
    public static final int MMIO_SIZE      = 0x10;
    public static final int REG_CMD_STATUS = 0x00;
    public static final int REG_DATA0      = 0x01; // DATA0..DATA14 = 0x01..0x0F

    // STATUS bits (read @ 0x00)
    public static final int ST_RUN    = 0x01; // alarm armed
    public static final int ST_AUTO   = 0x02; // periodic
    public static final int ST_IRQ_EN = 0x04; // IRQ enabled
    public static final int ST_FIRED  = 0x08; // sticky until ACK/CLEAR

    // Opcodes (write to 0x00)
    public static final int CMD_SET_PERIOD   = 0x10; // DATA0..DATA(N-1)=PERIOD (LE)
    public static final int CMD_ARM_ONESHOT  = 0x11;
    public static final int CMD_ARM_AUTO     = 0x12;
    public static final int CMD_STOP         = 0x13;
    public static final int CMD_IRQ_EN       = 0x14; // DATA0 bit0
    public static final int CMD_ACK          = 0x15;
    public static final int CMD_CLEAR        = 0x16;
    public static final int CMD_SELECT_READ  = 0x17; // DATA0=windowId
    public static final int CMD_SEED_RNG     = 0x18; // DATA0..DATA3=seed (LE)

    // Read windows
    public static final int WIN_MTIME = 0x00;
    public static final int WIN_RNG   = 0x01;

    // Wiring / config
    private final InterruptSink sink;
    private final int irqBit;

    private long busHz;
    private final long tickHz;

    private final int mtimeBits;
    private final int mtimeBytes;
    private final long mask;

    private final byte[] dataLatch = new byte[0x0F];
    private int readWindow = WIN_MTIME;

    private long tickAcc;
    private long mtime;

    private long mtimeSnap;
    private boolean snapValid;

    private long period;
    private long down;
    private int status;

    private int rngState = 0x6D2B79F5;

    public PIT(InterruptSink sink, int irqBit, int mtimeBits, long busHz, long tickHz) {
        if (sink == null) throw new NullPointerException("sink");
        if (busHz <= 0) throw new IllegalArgumentException("busHz must be > 0");
        if (tickHz <= 0) throw new IllegalArgumentException("tickHz must be > 0");
        if (mtimeBits < 16 || mtimeBits > 64 || (mtimeBits % 8) != 0) {
            throw new IllegalArgumentException("mtimeBits must be 16..64 and multiple of 8");
        }
        this.sink = sink;
        this.irqBit = irqBit;
        this.busHz = busHz;
        this.tickHz = tickHz;

        this.mtimeBits = mtimeBits;
        this.mtimeBytes = mtimeBits / 8;
        this.mask = (mtimeBits == 64) ? 0xFFFF_FFFF_FFFF_FFFFL : ((1L << mtimeBits) - 1L);

        // power-on defaults
        reset(false);
    }

    /** Convenience factory: MTIME 32-bit, BUS->1MHz tick. */
    public static PIT create1MHz(InterruptSink sink, int irqBit, long busHz) {
        return new PIT(sink, irqBit, 32, busHz, 1_000_000L);
    }

    /** Reset internal state. If {@code hard} also clears the operand latch. */
    public void reset(boolean hard) {
        tickAcc = 0;
        mtime = 0;
        mtimeSnap = 0;
        snapValid = false;
        period = 0;
        down = 0;
        status = 0;
        readWindow = WIN_MTIME;
        rngState = (rngState != 0) ? rngState : 0x6D2B79F5;
        if (hard) Arrays.fill(dataLatch, (byte) 0);
    }

    public void setBusHz(long busHz) {
        if (busHz <= 0) throw new IllegalArgumentException("busHz must be > 0");
        this.busHz = busHz;
        this.tickAcc = 0;
    }

    public PIT withSeed(int seed) {
        this.rngState = (seed != 0) ? seed : 0x6D2B79F5;
        return this;
    }

    @Override
    public int size() {
        return MMIO_SIZE;
    }

    @Override
    public void tick(int cycles) {
        if (cycles <= 0) return;

        // Fixed-point: accumulate BUS cycles into tickHz units.
        tickAcc += (long) cycles * tickHz;

        long ticks = tickAcc / busHz;
        if (ticks > 0) {
            tickAcc -= ticks * busHz;
            mtime = (mtime + ticks) & mask;

            if ((status & ST_RUN) != 0 && down != 0) {
                long remaining = down & mask;

                if (ticks < remaining) {
                    down = (remaining - ticks) & mask;
                } else {
                    long over = ticks - remaining;
                    fireOnce();

                    // Periodic reload: compute where we land within the next period.
                    if (((status & ST_RUN) != 0) && ((status & ST_AUTO) != 0)) {
                        long p = period & mask;
                        if (p != 0) {
                            long rem = over % p;
                            down = (p - rem) & mask;
                            if (down == 0) down = p;
                        } else {
                            // period==0 -> disarm
                            down = 0;
                            status &= ~(ST_RUN | ST_AUTO);
                        }
                    } else {
                        down = 0;
                    }
                }
            }
        }

        // Level-style IRQ line: if FIRED is set and IRQ_EN, keep raising.
        if ((status & (ST_FIRED | ST_IRQ_EN)) == (ST_FIRED | ST_IRQ_EN)) {
            sink.raise(irqBit);
        }
    }

    private void fireOnce() {
        status |= ST_FIRED;

        if ((status & ST_AUTO) != 0) {
            down = period & mask;
            if (down == 0) {
                // Guard: periodic with period==0 becomes disabled.
                status &= ~(ST_RUN | ST_AUTO);
            }
        } else {
            status &= ~ST_RUN;
        }
    }

    @Override
    public byte read(int offset) {
        int o = offset & 0x0F;
        if (o == REG_CMD_STATUS) return (byte) (status & 0xFF);

        int di = (o - REG_DATA0) & 0x0F;
        if (di >= dataLatch.length) return 0;

        if (readWindow == WIN_MTIME) {
            // Snapshot-on-first-byte for stable multi-byte reads.
            if (di == 0 || !snapValid) {
                mtimeSnap = mtime;
                snapValid = true;
            }
            return (byte) ((mtimeSnap >>> (di * 8)) & 0xFF);
        }

        // RNG window
        if (rngState == 0) rngState = 0x6D2B79F5; // avoid xorshift lock-up
        rngState = xorshift32(rngState);
        return (byte) (rngState & 0xFF);
    }

    @Override
    public void write(int offset, byte value) {
        int o = offset & 0x0F;
        int v = value & 0xFF;

        if (o == REG_CMD_STATUS) {
            exec(v);
            return;
        }

        int di = (o - REG_DATA0) & 0x0F;
        if (di >= 0 && di < dataLatch.length) {
            dataLatch[di] = (byte) v;
            // Writing operands invalidates any previous MTIME snapshot.
            snapValid = false;
        }
    }

    private void exec(int cmd) {
        int c = cmd & 0xFF;

        switch (c) {
            case CMD_SET_PERIOD -> period = readLatchLE(mtimeBytes);

            case CMD_ARM_ONESHOT -> {
                long p = period & mask;
                if (p == 0) {
                    down = 0;
                    status &= ~(ST_RUN | ST_AUTO);
                } else {
                    down = p;
                    status |= ST_RUN;
                    status &= ~ST_AUTO;
                }
            }

            case CMD_ARM_AUTO -> {
                long p = period & mask;
                if (p == 0) {
                    down = 0;
                    status &= ~(ST_RUN | ST_AUTO);
                } else {
                    down = p;
                    status |= (ST_RUN | ST_AUTO);
                }
            }

            case CMD_STOP -> status &= ~(ST_RUN | ST_AUTO);

            case CMD_IRQ_EN -> {
                if ((dataLatch[0] & 1) != 0) status |= ST_IRQ_EN;
                else status &= ~ST_IRQ_EN;
            }

            case CMD_ACK -> status &= ~ST_FIRED;

            case CMD_CLEAR -> {
                // Full clear: status + timers + snapshot + latch (compat not required)
                status = 0;
                down = 0;
                period = 0;
                tickAcc = 0;
                mtime = 0;
                snapValid = false;
                readWindow = WIN_MTIME;
                Arrays.fill(dataLatch, (byte) 0);
            }

            case CMD_SELECT_READ -> {
                int w = dataLatch[0] & 0xFF;
                readWindow = (w == WIN_RNG) ? WIN_RNG : WIN_MTIME;
            }

            case CMD_SEED_RNG -> {
                int s = (dataLatch[0] & 0xFF)
                        | ((dataLatch[1] & 0xFF) << 8)
                        | ((dataLatch[2] & 0xFF) << 16)
                        | ((dataLatch[3] & 0xFF) << 24);
                rngState = (s != 0) ? s : 0x6D2B79F5;
            }

            default -> { /* ignore */ }
        }

        snapValid = false;
    }

    private long readLatchLE(int nBytes) {
        long v = 0;
        for (int i = 0; i < nBytes; i++) v |= (long) (dataLatch[i] & 0xFF) << (i * 8);
        return v & mask;
    }

    private static int xorshift32(int x) {
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        return x;
    }
}
