package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;
import io.github.robincores.r8.system.InterruptSink;
import io.github.robincores.r8.system.Tickable;

/**
 * PIT — 16-byte command-port timer for R8 systems (8-bit bus friendly).
 *
 * <p>Write protocol: write operands to 0x01..0x0F, then write opcode to 0x00.</p>
 * <p>Read protocol: read 0x00 for STATUS, read 0x01..0x0F from the selected window.</p>
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>MTIME: free-running uptime counter (16..64 bits, ctor-selected, little-endian reads)</li>
 *   <li>PERIOD/DOWN: downcounter alarm (same width as MTIME), one-shot or auto-reload</li>
 *   <li>RNG: byte stream (non-crypto) via a read window</li>
 * </ul>
 */
public final class PIT implements BusDevice, Tickable {

    // ------------------------------------------------------------
    // MMIO (16 bytes)
    // ------------------------------------------------------------

    public static final int MMIO_SIZE      = 0x10;
    public static final int REG_CMD_STATUS = 0x00;
    public static final int REG_DATA0      = 0x01; // DATA0..DATA14 = 0x01..0x0F

    // ------------------------------------------------------------
    // STATUS bits (read @ 0x00)
    // ------------------------------------------------------------

    public static final int ST_RUN    = 0x01; // alarm armed
    public static final int ST_AUTO   = 0x02; // periodic
    public static final int ST_IRQ_EN = 0x04; // IRQ enabled
    public static final int ST_FIRED  = 0x08; // sticky until ACK/CLEAR

    // ------------------------------------------------------------
    // Opcodes (write to 0x00)
    // ------------------------------------------------------------

    /** DATA0..DATA(N-1) = PERIOD (LE, N = mtimeBytes). */
    public static final int CMD_SET_PERIOD = 0x10;
    public static final int CMD_ARM_ONESHOT = 0x11;
    public static final int CMD_ARM_AUTO    = 0x12;
    public static final int CMD_STOP        = 0x13;
    /** DATA0 bit0: 1=enable, 0=disable. */
    public static final int CMD_IRQ_EN      = 0x14;
    public static final int CMD_ACK         = 0x15;
    public static final int CMD_CLEAR       = 0x16;
    /** DATA0 = windowId. */
    public static final int CMD_SELECT_READ = 0x17;
    /** DATA0..DATA3 = seed (32-bit LE). Optional. */
    public static final int CMD_SEED_RNG    = 0x18;

    // ------------------------------------------------------------
    // Read windows
    // ------------------------------------------------------------

    public static final int WIN_MTIME = 0x00; // DATA[0..N-1] = MTIME LE (snap on DATA0 read)
    public static final int WIN_RNG   = 0x01; // DATA reads return random bytes (stream)

    // ------------------------------------------------------------
    // Wiring / config
    // ------------------------------------------------------------

    private final InterruptSink sink;
    private final int irqBit;
    private final int cyclesPerTick;

    private final int mtimeBits;
    private final int mtimeBytes;
    private final long mask;

    // ------------------------------------------------------------
    // Command latch + read window select
    // ------------------------------------------------------------

    private final byte[] dataLatch = new byte[0x0F]; // DATA0..DATA14
    private int readWindow = WIN_MTIME;

    // ------------------------------------------------------------
    // Time / alarm state
    // ------------------------------------------------------------

    private long cycleAcc;
    private long mtime;

    private long mtimeSnap;
    private boolean snapValid;

    private long period;
    private long down;
    private int status;

    // ------------------------------------------------------------
    // RNG (non-crypto)
    // ------------------------------------------------------------

    private int rngState = 0x6D2B79F5; // deterministic default, non-zero

    /**
     * @param sink          interrupt sink (CPU implements this)
     * @param irqBit        interrupt bit to raise on alarm expiry
     * @param mtimeBits     16..64, multiple of 8 (e.g. 32 / 48 / 64)
     * @param cyclesPerTick CPU cycles per 1 tick of MTIME/DOWN (>= 1)
     */
    public PIT(InterruptSink sink, int irqBit, int mtimeBits, int cyclesPerTick) {
        if (sink == null) throw new NullPointerException("sink");
        if (cyclesPerTick < 1) throw new IllegalArgumentException("cyclesPerTick must be >= 1");
        if (mtimeBits < 16 || mtimeBits > 64 || (mtimeBits % 8) != 0) {
            throw new IllegalArgumentException("mtimeBits must be 16..64 and multiple of 8");
        }
        this.sink = sink;
        this.irqBit = irqBit;
        this.cyclesPerTick = cyclesPerTick;

        this.mtimeBits = mtimeBits;
        this.mtimeBytes = mtimeBits / 8;

        this.mask = (mtimeBits == 64) ? 0xFFFF_FFFF_FFFF_FFFFL : ((1L << mtimeBits) - 1L);
    }

    /** Optional convenience: deterministic or variable seed chosen by the system. */
    public PIT withSeed(int seed) {
        this.rngState = (seed != 0) ? seed : 0x6D2B79F5;
        return this;
    }

    @Override
    public int size() {
        return MMIO_SIZE;
    }

    // ============================================================
    // Tick
    // ============================================================

    @Override
    public void tick(int cycles) {
        if (cycles <= 0) return;

        cycleAcc += (long) cycles;

        long ticks = cycleAcc / (long) cyclesPerTick;
        if (ticks <= 0) {
            // If FIRED is sticky and IRQ enabled, keep it asserted.
            if ((status & (ST_FIRED | ST_IRQ_EN)) == (ST_FIRED | ST_IRQ_EN)) sink.raise(irqBit);
            return;
        }
        cycleAcc -= ticks * (long) cyclesPerTick;

        // MTIME always increments
        mtime = (mtime + ticks) & mask;

        // Alarm
        if ((status & ST_RUN) != 0 && down != 0) {
            long remaining = down & mask;

            if (ticks < remaining) {
                down = (remaining - ticks) & mask;
            } else {
                // Crossed (or reached) zero at least once
                ticks -= remaining;
                expireOnce();

                if ((status & ST_RUN) != 0 && (status & ST_AUTO) != 0 && period != 0) {
                    // Skip whole periods if we fell behind
                    long p = period & mask;
                    long rem = (p == 0) ? 0 : (ticks % p);
                    down = (p - rem) & mask;
                    if (down == 0) down = p; // keep next expiry p ticks away on exact boundary
                } else {
                    down = 0;
                }
            }
        }

        // Level-ish behavior: if FIRED is set and IRQ enabled, keep asserting.
        if ((status & (ST_FIRED | ST_IRQ_EN)) == (ST_FIRED | ST_IRQ_EN)) {
            sink.raise(irqBit);
        }
    }

    private void expireOnce() {
        status |= ST_FIRED;

        if ((status & ST_IRQ_EN) != 0) sink.raise(irqBit);

        if ((status & ST_AUTO) != 0) {
            down = period & mask;
            if (down == 0) status &= ~ST_RUN; // period=0 disarms
        } else {
            status &= ~ST_RUN; // one-shot stops
        }
    }

    // ============================================================
    // MMIO read
    // ============================================================

    @Override
    public byte read(int offset) {
        if (offset == REG_CMD_STATUS) return (byte) status;

        if (offset >= REG_DATA0 && offset < MMIO_SIZE) {
            int i = offset - REG_DATA0;
            return switch (readWindow) {
                case WIN_MTIME -> readMtime(i);
                case WIN_RNG   -> nextRandomByte();
                default -> 0;
            };
        }

        return 0;
    }

    private byte readMtime(int i) {
        if (i == 0) {
            mtimeSnap = mtime;
            snapValid = true;
        }
        if (i < 0 || i >= mtimeBytes) return 0;

        long v = snapValid ? mtimeSnap : mtime;
        byte b = (byte) ((v >>> (i * 8)) & 0xFF);

        if (i == mtimeBytes - 1) snapValid = false;
        return b;
    }

    private byte nextRandomByte() {
        // xorshift32 (non-crypto)
        int x = rngState;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        rngState = (x != 0) ? x : 0x6D2B79F5;
        return (byte) (rngState & 0xFF);
    }

    // ============================================================
    // MMIO write
    // ============================================================

    @Override
    public void write(int offset, byte value) {
        if (offset == REG_CMD_STATUS) {
            exec(Byte.toUnsignedInt(value));
            return;
        }
        if (offset >= REG_DATA0 && offset < MMIO_SIZE) {
            dataLatch[offset - REG_DATA0] = value;
        }
    }

    private void exec(int opcode) {
        int op0 = Byte.toUnsignedInt(dataLatch[0]);

        switch (opcode) {
            case CMD_SET_PERIOD -> {
                period = readOperandLE(mtimeBytes) & mask;
                // If running auto, a new period only takes effect on next ARM.
            }

            case CMD_ARM_ONESHOT -> {
                status &= ~(ST_AUTO | ST_FIRED);
                status |= ST_RUN;
                down = period & mask;
                if (down == 0) status &= ~ST_RUN;
            }

            case CMD_ARM_AUTO -> {
                status &= ~ST_FIRED;
                status |= (ST_RUN | ST_AUTO);
                down = period & mask;
                if (down == 0) status &= ~ST_RUN;
            }

            case CMD_STOP -> {
                status &= ~(ST_RUN | ST_AUTO);
                down = 0;
            }

            case CMD_IRQ_EN -> {
                if ((op0 & 0x01) != 0) status |= ST_IRQ_EN;
                else status &= ~ST_IRQ_EN;

                if ((status & (ST_IRQ_EN | ST_FIRED)) == (ST_IRQ_EN | ST_FIRED)) {
                    sink.raise(irqBit);
                }
            }

            case CMD_ACK -> status &= ~ST_FIRED;

            case CMD_CLEAR -> {
                mtime = 0;
                mtimeSnap = 0;
                snapValid = false;

                period = 0;
                down = 0;
                status = 0;

                cycleAcc = 0;
                // rngState left as-is (use CMD_SEED_RNG if you want)
            }

            case CMD_SELECT_READ -> readWindow = op0 & 0xFF;

            case CMD_SEED_RNG -> {
                int s =
                        (Byte.toUnsignedInt(dataLatch[0])      ) |
                                (Byte.toUnsignedInt(dataLatch[1]) <<  8) |
                                (Byte.toUnsignedInt(dataLatch[2]) << 16) |
                                (Byte.toUnsignedInt(dataLatch[3]) << 24);
                rngState = (s != 0) ? s : 0x6D2B79F5;
            }

            default -> {
                // reserved: ignored
            }
        }
    }

    private long readOperandLE(int nbytes) {
        long v = 0;
        int n = Math.min(nbytes, dataLatch.length);
        for (int i = 0; i < n; i++) {
            v |= (Byte.toUnsignedLong(dataLatch[i]) << (i * 8));
        }
        return v;
    }
}
