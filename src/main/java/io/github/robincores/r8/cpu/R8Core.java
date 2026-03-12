package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.debug.Debugger;
import io.github.robincores.r8.system.InterruptSink;

/**
 * R8Core — reference CPU core for the R8 ISA (R816/R824/R832).
 *
 * <p>Design goals:
 * <ul>
 *   <li>Developer-friendly: explicit semantics, readable code, easy to debug.</li>
 *   <li>Fast enough to run real software in the Java emulator.</li>
 *   <li>Faithful trap/IRQ behavior and restartable EXT0 block ops (R8BLK).</li>
 * </ul>
 *
 * <p>Architectural model (Transputer/JVM flavor):
 * <ul>
 *   <li>Operand stack with 3 cached registers: A (TOS), B, C.</li>
 *   <li>Workspace locals: wksp[0..255]; by convention wksp[15] is SP.</li>
 *   <li>Memory stack lives in RAM and is accessed only via PUSH/POP (native word size).</li>
 * </ul>
 *
 * <p><b>Important:</b> This implementation is <i>int-based</i> and therefore supports up to
 * 32-bit WORD sizes (R816/R824/R832). A future {@code R8Core64} should be used for WORD_BYTES=8.
 */
public abstract class R8Core implements InterruptSink {

    // =====================================================================
    // Construction-time configuration
    // =====================================================================

    /**
     * Construction-time configuration.
     *
     * <p>This keeps the core constructor stable as we add emulator-only toggles
     * (micro-fusion, feature flags, etc.) without exploding the parameter list.
     */
    public static final class Config {
        public final int addrMask;
        public final int wordBytes;
        public final int wordMask;
        public final int wordSignBit;

        /** EXT0 block ops (R8BLK). Console profile requires true. */
        public final boolean hasR8Blk;

        /** Emulator-only optimization; never changes architectural semantics. */
        public final MicroFusionMode microFusionMode;

        private Config(Builder b) {
            this.addrMask = b.addrMask;
            this.wordBytes = b.wordBytes;
            this.wordMask = b.wordMask;
            this.wordSignBit = b.wordSignBit;
            this.hasR8Blk = b.hasR8Blk;
            this.microFusionMode = b.microFusionMode;
        }

        public static Builder builder(int addrMask, int wordBytes, int wordMask, int wordSignBit) {
            return new Builder(addrMask, wordBytes, wordMask, wordSignBit);
        }

        public static final class Builder {
            private final int addrMask;
            private final int wordBytes;
            private final int wordMask;
            private final int wordSignBit;

            private boolean hasR8Blk = true;
            private MicroFusionMode microFusionMode = MicroFusionMode.OFF;

            private Builder(int addrMask, int wordBytes, int wordMask, int wordSignBit) {
                this.addrMask = addrMask;
                this.wordBytes = wordBytes;
                this.wordMask = wordMask;
                this.wordSignBit = wordSignBit;
            }

            public Builder hasR8Blk(boolean v) {
                this.hasR8Blk = v;
                return this;
            }

            public Builder microFusionMode(MicroFusionMode mode) {
                this.microFusionMode = (mode == null) ? MicroFusionMode.OFF : mode;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    // =====================================================================
    // ISA-visible constants (interrupt bitmasks, CSR ids, trap causes)
    // =====================================================================

    // Async interrupt bitmasks (ONLY async sources)
    public static final int SOFTWARE_INTERRUPT_MASK = 0x01; // bit 0
    public static final int TIMER_INTERRUPT_MASK    = 0x02; // bit 1
    public static final int EXTERNAL_INTERRUPT_MASK = 0x04; // bit 2

    private static final int ASYNC_INTERRUPT_MASK =
            SOFTWARE_INTERRUPT_MASK | TIMER_INTERRUPT_MASK | EXTERNAL_INTERRUPT_MASK;

    // Interrupt cause numbers (low bits of MCAUSE when interrupt-bit is set)
    private static final int IRQ_SW    = 0;
    private static final int IRQ_TIMER = 1;
    private static final int IRQ_EXT   = 2;

    // Trap causes (synchronous)
    private static final int TRAP_INSN_MISALIGNED = 0x00; // reserved
    private static final int TRAP_ILLEGAL_INSN    = 0x02; // unrecognized opcode / unsupported width guard
    private static final int TRAP_EBREAK          = 0x03; // breakpoint
    private static final int TRAP_ECALL           = 0x08; // environment call
    private static final int TRAP_DIV_ZERO        = 0x18; // divide/remainder by zero (R8-specific)

    // CSR ids (imm8)
    private static final int CSR_MSTATUS = 0x00;
    private static final int CSR_MIE     = 0x01;
    private static final int CSR_MIP     = 0x02;
    private static final int CSR_MTVEC   = 0x03;
    private static final int CSR_MEPC    = 0x04;
    private static final int CSR_MCAUSE  = 0x05;
    private static final int CSR_MTVAL   = 0x06;
    private static final int CSR_MEXT    = 0x07; // extension discovery bitmask (read-only)

    // MEXT bits (normative)
    private static final int MEXT_R8BLK = 1 << 0; // EXT0 BLK ops

    // mstatus bits (minimal)
    private static final int MSTATUS_MIE  = 1 << 0;
    private static final int MSTATUS_MPIE = 1 << 1;

    // =====================================================================
    // Core dependencies + CPU shape (constructor-defined finals)
    // =====================================================================

    protected final Bus bus;

    protected final int ADDR_MASK;     // 0xFFFF / 0xFF_FFFF / 0xFFFF_FFFF ...
    protected final int WORD_BYTES;    // 2 / 3 / 4
    protected final int WORD_BITS;     // 16 / 24 / 32
    protected final int WORD_MASK;     // 0xFFFF / 0xFF_FFFF / 0xFFFF_FFFF ...
    protected final int WORD_SIGNBIT;  // 0x8000 / 0x80_0000 / 0x8000_0000 ...

    public enum MicroFusionMode {
        OFF,
        /**
         * SAFE micro-fusion: the emulator may execute up to two architectural instructions per
         * {@link #executeInstruction()} call when it can prove the second instruction is a
         * simple data consumer of the first (Imm→ALU/Compare/Shift or Load→ALU/Compare/Shift).
         *
         * <p>Architectural semantics are preserved: interrupts are still serviced between the two
         * instructions and traps remain precise.</p>
         */
        SAFE
    }

    /** Micro-fusion policy (emulator-only optimization; never changes architectural semantics). */
    protected final MicroFusionMode microFusionMode;

    /** Whether EXT0 R8BLK ops are implemented (Console profile requires true). */
    protected final boolean hasR8Blk;

    protected R8Core(Bus bus, Config cfg) {
        if (cfg.wordBytes != 2 && cfg.wordBytes != 3 && cfg.wordBytes != 4) {
            throw new IllegalArgumentException("R8Core (int-based) supports WORD_BYTES 2/3/4. Got: " + cfg.wordBytes);
        }
        this.bus = bus;
        this.ADDR_MASK = cfg.addrMask;
        this.WORD_BYTES = cfg.wordBytes;
        this.WORD_BITS = cfg.wordBytes * 8;
        this.WORD_MASK = cfg.wordMask;
        this.WORD_SIGNBIT = cfg.wordSignBit;
        this.hasR8Blk = cfg.hasR8Blk;
        this.microFusionMode = cfg.microFusionMode;
    }

    /** Convenience ctor for older call sites. Prefer {@link #R8Core(Bus, Config)}. */
    protected R8Core(Bus bus, int addrMask, int wordBytes, int wordMask, int wordSignBit) {
        this(bus, Config.builder(addrMask, wordBytes, wordMask, wordSignBit).build());
    }

    /** Convenience ctor for older call sites. Prefer {@link #R8Core(Bus, Config)}. */
    protected R8Core(Bus bus, int addrMask, int wordBytes, int wordMask, int wordSignBit, boolean hasR8Blk) {
        this(bus, Config.builder(addrMask, wordBytes, wordMask, wordSignBit).hasR8Blk(hasR8Blk).build());
    }

    /** Convenience ctor for older call sites. Prefer {@link #R8Core(Bus, Config)}. */
    protected R8Core(
            Bus bus,
            int addrMask,
            int wordBytes,
            int wordMask,
            int wordSignBit,
            boolean hasR8Blk,
            MicroFusionMode microFusionMode
    ) {
        this(bus, Config.builder(addrMask, wordBytes, wordMask, wordSignBit)
                .hasR8Blk(hasR8Blk)
                .microFusionMode(microFusionMode)
                .build());
    }

    // =====================================================================
    // InterruptSink
    // =====================================================================

    @Override
    public final void raise(int interruptBit) {
        setInterruptPending(interruptBit);
        // HLT wake semantics: wake only if an enabled IRQ is pending and global MIE=1.
        if (halted && csrMieEnabled() && (((mip & mie) & ASYNC_INTERRUPT_MASK) != 0)) {
            halted = false;
        }
    }

    // =====================================================================
    // Architectural state (registers)
    // =====================================================================

    /** Workspace registers. By convention wksp[15] is SP. */
    protected final int[] wksp = new int[256];

    /** Operand stack cache: A=TOS, B=next, C=third. */
    protected int AReg, BReg, CReg;

    /** Instruction pointer (masked to address width). */
    protected int IPtr = 0;

    /** HLT state. */
    private boolean halted = false;

    /** Set on trap/interrupt entry to keep micro-fusion precise. */
    private boolean vectorEntered = false;

    // =====================================================================
    // Timing model (simple but stable)
    // =====================================================================

    private static final int S_IFETCH    = 1;
    private static final int S_DECODE    = 1;
    private static final int S_MEM_READ  = 1;
    private static final int S_MEM_WRITE = 1;

    protected final int memReadWordCycles()  { return S_MEM_READ  * WORD_BYTES; }
    protected final int memWriteWordCycles() { return S_MEM_WRITE * WORD_BYTES; }

    // =====================================================================
    // CSR / trap / interrupt registers
    // =====================================================================

    /** Trap vector base (direct mode). */
    protected int mtvec = 0x0000;

    /** Interrupt pending. */
    private int mip = 0;

    /** Interrupt enable mask. */
    private int mie = TIMER_INTERRUPT_MASK; // default enable

    /** Machine status (MIE/MPIE only). */
    private int mstatus = 0;

    /** Return PC saved on trap/interrupt entry. */
    private int mepc = 0;

    /** Cause (MSB set indicates interrupt). */
    private int mcause = 0;

    /** Trap value (optional). */
    private int mtval = 0;

    // =====================================================================
    // Width + register helpers
    // =====================================================================

    protected final int maskAddr(int a) { return a & ADDR_MASK; }

    protected final int uword(int v) { return v & WORD_MASK; }

    /**
     * Normalize a Java int into the architectural WORD domain:
     * mask to WORD bits, then sign-extend to int.
     */
    protected final int normalizeWord(int v) {
        v &= WORD_MASK;
        if ((v & WORD_SIGNBIT) != 0) v |= ~WORD_MASK;
        return v;
    }

    protected final void setIPtr(int address) { this.IPtr = maskAddr(address); }

    protected final void setAReg(int value) { this.AReg = normalizeWord(value); }
    protected final void setBReg(int value) { this.BReg = normalizeWord(value); }
    protected final void setCReg(int value) { this.CReg = normalizeWord(value); }

    protected final void incSP() { wksp[15] = maskAddr(wksp[15] + WORD_BYTES); }
    protected final void decSP() { wksp[15] = maskAddr(wksp[15] - WORD_BYTES); }

    private static int sext8(int v) { return (v & 0x80) != 0 ? (v | 0xFFFF_FF00) : (v & 0xFF); }
    private static int zext8(int v) { return v & 0xFF; }

    // =====================================================================
    // Memory helpers (byte/word + fetch)
    // =====================================================================

    protected final byte readByte(int address) { return bus.read8(maskAddr(address)); }
    protected final void writeByte(int address, byte value) { bus.write8(maskAddr(address), value); }

    /** Reads masked address and returns unsigned WORD value (native width). */
    protected final int readWord(int address) {
        int v = 0;
        int a = maskAddr(address);
        for (int i = 0; i < WORD_BYTES; i++) {
            v |= (Byte.toUnsignedInt(readByte(a + i)) << (8 * i));
        }
        return v & WORD_MASK;
    }

    /** Writes WORD value to masked address (native width). */
    protected final void writeWord(int address, int value) {
        int a = maskAddr(address);
        int v = value & WORD_MASK;
        for (int i = 0; i < WORD_BYTES; i++) {
            writeByte(a + i, (byte) (v >>> (8 * i)));
        }
    }

    /** 16-bit halfword read (little-endian). */
    private int readHalf(int address) {
        int a = maskAddr(address);
        int lo = Byte.toUnsignedInt(readByte(a));
        int hi = Byte.toUnsignedInt(readByte(a + 1));
        return (hi << 8) | lo;
    }

    /** 16-bit halfword write (little-endian). */
    private void writeHalf(int address, int value) {
        int a = maskAddr(address);
        int v = value & 0xFFFF;
        writeByte(a, (byte) (v));
        writeByte(a + 1, (byte) (v >>> 8));
    }

    /** 32-bit word read (little-endian), returned as unsigned long. */
    private long read32u(int address) {
        int a = maskAddr(address);
        long v = 0;
        for (int i = 0; i < 4; i++) v |= ((long) Byte.toUnsignedInt(readByte(a + i))) << (8L * i);
        return v & 0xFFFF_FFFFL;
    }

    /** 32-bit word write (little-endian). */
    private void write32(int address, int value) {
        int a = maskAddr(address);
        for (int i = 0; i < 4; i++) writeByte(a + i, (byte) (value >>> (8 * i)));
    }

    protected final int fetchOpcode() {
        int insn = Byte.toUnsignedInt(readByte(IPtr));
        setIPtr(IPtr + 1);
        return insn;
    }

    protected final int fetchImm8() {
        int op = Byte.toUnsignedInt(readByte(IPtr));
        setIPtr(IPtr + 1);
        return op;
    }

    protected final int fetchImmW() {
        int v = 0;
        for (int i = 0; i < WORD_BYTES; i++) v |= fetchImm8() << (8 * i);
        return v & WORD_MASK;
    }

    // =====================================================================
    // CSR accessors
    // =====================================================================

    private boolean csrMieEnabled() { return (mstatus & MSTATUS_MIE) != 0; }

    private void csrSetMie(boolean v) {
        if (v) mstatus |= MSTATUS_MIE;
        else mstatus &= ~MSTATUS_MIE;
    }

    /** MEXT is a discovery bitmask; currently only bit0 is used (R8BLK). */
    private int csrMextValue() {
        return hasR8Blk ? MEXT_R8BLK : 0;
    }

    private int readCSR(int csr) {
        return switch (csr & 0xFF) {
            case CSR_MSTATUS -> uword(mstatus);
            case CSR_MIE     -> uword(mie) & ASYNC_INTERRUPT_MASK;
            case CSR_MIP     -> uword(mip) & ASYNC_INTERRUPT_MASK;
            case CSR_MTVEC   -> maskAddr(mtvec);
            case CSR_MEPC    -> maskAddr(mepc);
            case CSR_MCAUSE  -> uword(mcause);
            case CSR_MTVAL   -> uword(mtval);
            case CSR_MEXT    -> uword(csrMextValue());
            default -> 0;
        };
    }

    private void writeCSR(int csr, int value) {
        switch (csr & 0xFF) {
            case CSR_MSTATUS -> mstatus = uword(value) & (MSTATUS_MIE | MSTATUS_MPIE);
            case CSR_MIE     -> mie = (uword(value) & ASYNC_INTERRUPT_MASK);

            /**
             * CSR_MIP write semantics:
             *  - SW bit (bit0) is directly writable (set/clear)
             *  - TIMER/EXT bits are W1C only (write-1 clears), otherwise read-only to software
             */
            case CSR_MIP -> {
                int v = uword(value) & ASYNC_INTERRUPT_MASK;

                // SW pending: direct write
                if ((v & SOFTWARE_INTERRUPT_MASK) != 0) mip |= SOFTWARE_INTERRUPT_MASK;
                else mip &= ~SOFTWARE_INTERRUPT_MASK;

                // TIMER/EXT: write-1-to-clear only
                int w1c = v & (TIMER_INTERRUPT_MASK | EXTERNAL_INTERRUPT_MASK);
                mip &= ~w1c;
            }

            case CSR_MTVEC -> mtvec = maskAddr(value);
            case CSR_MEPC  -> mepc = maskAddr(value);
            case CSR_MCAUSE-> mcause = uword(value);
            case CSR_MTVAL -> mtval = uword(value);

            // MEXT is read-only
            case CSR_MEXT -> { /* ignore */ }

            default -> { /* ignore */ }
        }
    }

    // =====================================================================
    // Execution API
    // =====================================================================

    /** Execute one architectural step (one instruction, or one BLK element step). */
    public final int executeInstruction() {
        // WFI-like: wake only when an enabled interrupt is pending and global MIE=1.
        if (halted) {
            if (csrMieEnabled() && (((mip & mie) & ASYNC_INTERRUPT_MASK) != 0)) {
                halted = false;
            } else {
                return 1; // idle cycle
            }
        }

        int totalCycles = 0;

        // ---- Instruction #1 (always)
        vectorEntered = false;
        final int insnPC1 = IPtr;
        final int opcode1 = fetchOpcode();
        totalCycles += decodeAndExecute(opcode1, insnPC1);

        // Architectural boundary: allow interrupts between instructions.
        servicePendingInterrupts();
        if (!microFusionActive() || halted || vectorEntered) return totalCycles;

        // ---- Instruction #2 (optional, SAFE mode)
        final int opcode2Peek = Byte.toUnsignedInt(readByte(IPtr));
        if (!canMicroFusePair(opcode1, opcode2Peek)) return totalCycles;

        vectorEntered = false;
        final int insnPC2 = IPtr;
        final int opcode2 = fetchOpcode();
        totalCycles += decodeAndExecute(opcode2, insnPC2);

        // Another architectural boundary.
        servicePendingInterrupts();
        return totalCycles;
    }

    /**
     * Micro-fusion is an emulator performance optimization:
     * we batch two architectural instructions in one call, but still preserve precise
     * trap/IRQ boundaries by servicing interrupts between them.
     */
    private boolean microFusionActive() {
        return microFusionMode == MicroFusionMode.SAFE && debugger == null;
    }

    private static boolean isImmProducerOpcode(int op) {
        return switch (op & 0xFF) {
            case 0x02, // B imm8
                 0x06, // U imm8
                 0x43, // I immW
                 0x47  // AIIP immW
                    -> true;
            default -> false;
        };
    }

    private static boolean isLoadProducerOpcode(int op) {
        int o = op & 0xFF;

        // LDL0..15: 0x03..0x3F step 4
        if ((o & 0x03) == 0x03 && o <= 0x3F) return true;

        return switch (o) {
            case 0x01, // LD
                 0x05, // LB
                 0x09, // LU
                 0x0D, // LH
                 0x11, // LHU
                 0x15, // L32
                 0x19, // L32U
                 0x41, // POP (loads from memory stack)
                 0x7B  // LDLX
                    -> true;
            default -> false;
        };
    }

    private static boolean isBinaryConsumerOpcode(int op) {
        return switch (op & 0xFF) {
            // Binary ALU
            case 0x20, 0x24, 0x28, 0x2C, 0x30, 0xAC, 0xB0,
                 0x40, 0x44, 0x48,
            // Compare / shifts
                 0x50, 0x54, 0x6C, 0x70,
                 0x60, 0x64, 0x68,
            // Nintendo primitives (binary, but STACKΔ may be 0 or -1)
                 0x80, 0x84, 0x88, 0x8C
                    -> true;
            default -> false;
        };
    }

    /** Prevent fusing across instructions that must remain visible as separate steps. */
    private static boolean isBarrierOpcode(int op) {
        return switch (op & 0xFF) {
            // Control flow / traps / system
            case 0x7F, // ESC
                 0x42, // JR
                 0x46, // JALR
                 0x73, // J
                 0x77, // JAL
                 0x22, 0x26, 0x2A, 0x2E, 0x32, 0x36, 0x3A, 0x3E, 0xA2, // branches
                 0x4B, 0x4F, // CSRR/CSRW
                 0x53, 0x57, // SETI/CLRI
                 0x5B, 0x5F, // EI/DI
                 0x63, // IRET
                 0x67, 0x6B, // ECALL/EBREAK
                 0x6F, // FENCE
                 0xFF  // HLT
                    -> true;
            default -> false;
        };
    }

    private static boolean canMicroFusePair(int opcode1, int opcode2Peek) {
        if (isBarrierOpcode(opcode1) || isBarrierOpcode(opcode2Peek)) return false;
        if (isImmProducerOpcode(opcode1) || isLoadProducerOpcode(opcode1)) {
            return isBinaryConsumerOpcode(opcode2Peek);
        }
        return false;
    }

    private int decodeAndExecute(int opcode, int insnPC) {
        int cycles = S_IFETCH + S_DECODE;

        // Fast patterns: LDL0..15 and STL0..15 use stride-4 encoding (low bits == 0b11).
        if ((opcode & 0x03) == 0x03) {
            // LDL0..15: 0x03..0x3F step 4
            if (opcode <= 0x3F) {
                int idx = (opcode >>> 2) & 0x0F;
                CReg = BReg;
                BReg = AReg;
                setAReg(wksp[idx]);
                return cycles;
            }
            // STL0..15: 0x83..0xBF step 4
            if ((opcode & 0xC3) == 0x83) {
                int idx = (opcode >>> 2) & 0x0F;
                wksp[idx] = AReg;
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
        }

        switch (opcode & 0xFF) {

            // -------------------------------------------------------------
            // Stack basics
            // -------------------------------------------------------------
            case 0x00 -> { // NOP
                return cycles;
            }
            case 0x04 -> { // DUP  (…,A -> …,A,A)
                CReg = BReg;
                BReg = AReg;
                return cycles;
            }
            case 0x08 -> { // SWAP (…,B,A -> …,A,B)
                int t = AReg;
                AReg = BReg;
                BReg = t;
                return cycles;
            }
            case 0x0C -> { // DROP1 (…,B,A -> …,B)
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
            case 0x10 -> { // DROP2 (…,C,B,A -> …,C)
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x14 -> { // I2B (sext8)
                setAReg((byte) AReg);
                return cycles;
            }

            // -------------------------------------------------------------
            // Immediates
            // -------------------------------------------------------------
            case 0x02 -> { // B imm8 (signed)
                cycles += S_MEM_READ;
                CReg = BReg;
                BReg = AReg;
                setAReg(sext8(fetchImm8()));
                return cycles;
            }
            case 0x06 -> { // U imm8 (unsigned)
                cycles += S_MEM_READ;
                CReg = BReg;
                BReg = AReg;
                setAReg(zext8(fetchImm8()));
                return cycles;
            }
            case 0x43 -> { // I immW (signed native word)
                cycles += memReadWordCycles();
                CReg = BReg;
                BReg = AReg;
                setAReg(normalizeWord(fetchImmW()));
                return cycles;
            }
            case 0x47 -> { // AIIP immW (PC-relative address)
                cycles += memReadWordCycles();
                CReg = BReg;
                BReg = AReg;
                int imm = normalizeWord(fetchImmW());
                setAReg(maskAddr(IPtr + imm)); // IPtr already points after immediate
                return cycles;
            }

            // -------------------------------------------------------------
            // ALU (binary) — modular WORD ops
            // -------------------------------------------------------------
            case 0x20 -> { // ADD
                setAReg(BReg + AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x24 -> { // SUB
                setAReg(BReg - AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x28 -> { // MUL (low WORD)
                setAReg(BReg * AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x2C -> { // DIV (signed)
                if (AReg == 0) {
                    raiseTrap(TRAP_DIV_ZERO, 0, insnPC);
                    return cycles;
                }
                setAReg(BReg / AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x30 -> { // REM (signed)
                if (AReg == 0) {
                    raiseTrap(TRAP_DIV_ZERO, 0, insnPC);
                    return cycles;
                }
                setAReg(BReg % AReg);
                BReg = CReg;
                return cycles;
            }
            case 0xAC -> { // DIVU (unsigned)
                long dividend = uword(BReg) & 0xFFFF_FFFFL;
                long divisor  = uword(AReg) & 0xFFFF_FFFFL;
                if (divisor == 0) {
                    raiseTrap(TRAP_DIV_ZERO, 0, insnPC);
                    return cycles;
                }
                long q = Long.divideUnsigned(dividend, divisor);
                setAReg((int) q);
                BReg = CReg;
                return cycles;
            }
            case 0xB0 -> { // REMU (unsigned)
                long dividend = uword(BReg) & 0xFFFF_FFFFL;
                long divisor  = uword(AReg) & 0xFFFF_FFFFL;
                if (divisor == 0) {
                    raiseTrap(TRAP_DIV_ZERO, 0, insnPC);
                    return cycles;
                }
                long rem = Long.remainderUnsigned(dividend, divisor);
                setAReg((int) rem);
                BReg = CReg;
                return cycles;
            }

            // -------------------------------------------------------------
            // ALU (unary)
            // -------------------------------------------------------------
            case 0x34 -> { // INC
                setAReg(AReg + 1);
                return cycles;
            }
            case 0x38 -> { // DEC
                setAReg(AReg - 1);
                return cycles;
            }
            case 0x3C -> { // NEG
                setAReg(-AReg);
                return cycles;
            }
            case 0x40 -> { // AND
                setAReg(BReg & AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x44 -> { // OR
                setAReg(BReg | AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x48 -> { // XOR
                setAReg(BReg ^ AReg);
                BReg = CReg;
                return cycles;
            }
            case 0x4C -> { // INV
                setAReg(~AReg);
                return cycles;
            }

            // -------------------------------------------------------------
            // Compare (produce 0/1)
            // -------------------------------------------------------------
            case 0x50 -> { // SEQ
                setAReg((BReg == AReg) ? 1 : 0);
                BReg = CReg;
                return cycles;
            }
            case 0x54 -> { // SNE
                setAReg((BReg != AReg) ? 1 : 0);
                BReg = CReg;
                return cycles;
            }
            case 0x6C -> { // SLT (signed)
                setAReg((BReg < AReg) ? 1 : 0);
                BReg = CReg;
                return cycles;
            }
            case 0x70 -> { // SLTU (unsigned)
                setAReg((Integer.compareUnsigned(uword(BReg), uword(AReg)) < 0) ? 1 : 0);
                BReg = CReg;
                return cycles;
            }

            // -------------------------------------------------------------
            // Variable shifts (mask = WORD_BITS-1)
            // -------------------------------------------------------------
            case 0x60 -> { // SHL
                int s = AReg & (WORD_BITS - 1);
                int x = uword(BReg);
                setAReg(uword(x << s));
                BReg = CReg;
                return cycles;
            }
            case 0x64 -> { // SHR (logical)
                int s = AReg & (WORD_BITS - 1);
                int x = uword(BReg);
                setAReg(uword(x >>> s));
                BReg = CReg;
                return cycles;
            }
            case 0x68 -> { // SAR (arithmetic)
                int s = AReg & (WORD_BITS - 1);
                int x = normalizeWord(BReg);
                setAReg(x >> s);
                BReg = CReg;
                return cycles;
            }

            // -------------------------------------------------------------
            // Jumps (register)
            // -------------------------------------------------------------
            case 0x42 -> { // JR (consume addr)
                setIPtr(AReg);
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
            case 0x46 -> { // JALR (replace addr with return)
                int target = AReg;
                int ret = maskAddr(IPtr);
                setIPtr(target);
                setAReg(ret);
                return cycles;
            }

            // -------------------------------------------------------------
            // Branches (imm8)
            // -------------------------------------------------------------
            case 0x22 -> { // BEQ (…,C,B,A,k -> …,C)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (BReg == AReg) setIPtr(IPtr + off);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x26 -> { // BNE
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (BReg != AReg) setIPtr(IPtr + off);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x2A -> { // BLT (signed)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (BReg < AReg) setIPtr(IPtr + off);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x2E -> { // BLTU (unsigned)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (Integer.compareUnsigned(uword(BReg), uword(AReg)) < 0) setIPtr(IPtr + off);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x32 -> { // BGE (signed)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (BReg >= AReg) setIPtr(IPtr + off);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x36 -> { // BGEU (unsigned)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (Integer.compareUnsigned(uword(BReg), uword(AReg)) >= 0) setIPtr(IPtr + off);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x3A -> { // BEQZ (consume A)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (AReg == 0) setIPtr(IPtr + off);
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
            case 0x3E -> { // BNEZ (consume A)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                if (AReg != 0) setIPtr(IPtr + off);
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
            case 0xA2 -> { // BRA (consume imm8 only)
                cycles += S_MEM_READ;
                int off = sext8(fetchImm8());
                setIPtr(IPtr + off);
                return cycles;
            }

            // -------------------------------------------------------------
            // PC-relative jump/call (immW)
            // -------------------------------------------------------------
            case 0x73 -> { // J (IPtr += immW)
                cycles += memReadWordCycles();
                int off = normalizeWord(fetchImmW());
                setIPtr(IPtr + off);
                return cycles;
            }
            case 0x77 -> { // JAL (push return; IPtr += immW)
                cycles += memReadWordCycles();
                int off = normalizeWord(fetchImmW());
                int ret = maskAddr(IPtr);
                CReg = BReg;
                BReg = AReg;
                setAReg(ret);
                setIPtr(IPtr + off);
                return cycles;
            }

            // -------------------------------------------------------------
            // Memory (native word) + narrow edges (byte/half/32)
            // -------------------------------------------------------------
            case 0x01 -> { // LD
                cycles += memReadWordCycles();
                setAReg(readWord(AReg));
                return cycles;
            }
            case 0x21 -> { // ST  (consume addr+val)
                cycles += memWriteWordCycles();
                writeWord(BReg, AReg);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x05 -> { // LB (sext8)
                cycles += S_MEM_READ;
                setAReg((byte) readByte(AReg));
                return cycles;
            }
            case 0x09 -> { // LU (zext8)
                cycles += S_MEM_READ;
                setAReg(Byte.toUnsignedInt(readByte(AReg)));
                return cycles;
            }
            case 0x25 -> { // SB (consume addr+val)
                cycles += S_MEM_WRITE;
                writeByte(BReg, (byte) AReg);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x0D -> { // LH (guard: WORD_BYTES>=4 && even)
                if (WORD_BYTES < 4 || (WORD_BYTES & 1) != 0) {
                    raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                    return cycles;
                }
                cycles += 2 * S_MEM_READ;
                int h = readHalf(AReg);
                setAReg((short) h); // sign-extend 16 -> int, then normalizeWord
                return cycles;
            }
            case 0x11 -> { // LHU (guard)
                if (WORD_BYTES < 4 || (WORD_BYTES & 1) != 0) {
                    raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                    return cycles;
                }
                cycles += 2 * S_MEM_READ;
                int h = readHalf(AReg);
                setAReg(h);
                return cycles;
            }
            case 0x29 -> { // SH (guard)
                if (WORD_BYTES < 4 || (WORD_BYTES & 1) != 0) {
                    raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                    return cycles;
                }
                cycles += 2 * S_MEM_WRITE;
                writeHalf(BReg, AReg);
                AReg = CReg;
                BReg = 0;
                CReg = 0;
                return cycles;
            }
            case 0x15 -> { // L32 (guard: WORD_BYTES>4)
                // Not reachable for this int-based core.
                raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                return cycles;
            }
            case 0x19 -> { // L32U (guard: WORD_BYTES>4)
                // Not reachable for this int-based core.
                raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                return cycles;
            }
            case 0x2D -> { // S32 (guard: WORD_BYTES>4)
                // Not reachable for this int-based core.
                raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                return cycles;
            }

            // -------------------------------------------------------------
            // Memory stack (native word)
            // -------------------------------------------------------------
            case 0x45 -> { // PUSH: SP-=W; memW[SP]=A; drop1
                cycles += memWriteWordCycles();
                decSP();
                writeWord(wksp[15], AReg);
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
            case 0x41 -> { // POP: val=memW[SP]; SP+=W; push val
                cycles += memReadWordCycles();
                int val = readWord(wksp[15]);
                incSP();
                CReg = BReg;
                BReg = AReg;
                setAReg(val);
                return cycles;
            }

            // -------------------------------------------------------------
            // Workspace extended (imm8 index)
            // -------------------------------------------------------------
            case 0x7B -> { // LDLX imm8
                cycles += S_MEM_READ;
                int idx = fetchImm8() & 0xFF;
                CReg = BReg;
                BReg = AReg;
                setAReg(wksp[idx]);
                return cycles;
            }
            case 0xFB -> { // STLX imm8
                cycles += S_MEM_READ;
                int idx = fetchImm8() & 0xFF;
                wksp[idx] = AReg;
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }

            // -------------------------------------------------------------
            // System / CSR / traps
            // -------------------------------------------------------------
            case 0x4B -> { // CSRR csr8
                cycles += S_MEM_READ;
                int csr = fetchImm8();
                CReg = BReg;
                BReg = AReg;
                setAReg(readCSR(csr));
                return cycles;
            }
            case 0x4F -> { // CSRW csr8  (consumes A)
                cycles += S_MEM_READ;
                int csr = fetchImm8();
                writeCSR(csr, AReg);
                AReg = BReg;
                BReg = CReg;
                return cycles;
            }
            case 0x53 -> { // SETI mask8
                cycles += S_MEM_READ;
                int mask = fetchImm8() & ASYNC_INTERRUPT_MASK;
                mie |= mask;
                return cycles;
            }
            case 0x57 -> { // CLRI mask8
                cycles += S_MEM_READ;
                int mask = fetchImm8() & ASYNC_INTERRUPT_MASK;
                mie &= ~mask;
                return cycles;
            }
            case 0x5B -> { // EI
                csrSetMie(true);
                return cycles;
            }
            case 0x5F -> { // DI
                csrSetMie(false);
                return cycles;
            }
            case 0x63 -> { // IRET
                mret();
                return cycles;
            }
            case 0x6F -> { // FENCE (serializing; emulator is naturally ordered)
                return cycles;
            }
            case 0x67 -> { // ECALL
                raiseTrap(TRAP_ECALL, 0, insnPC);
                return cycles;
            }
            case 0x6B -> { // EBREAK
                raiseTrap(TRAP_EBREAK, 0, insnPC);
                return cycles;
            }
            case 0xFF -> { // HLT
                halted = true;
                return cycles;
            }

            // -------------------------------------------------------------
            // Nintendo primitives block
            // -------------------------------------------------------------
            case 0x80 -> { // ADDC: ..., x, y -> ..., carry, sum
                long x = uword(BReg) & 0xFFFF_FFFFL;
                long y = uword(AReg) & 0xFFFF_FFFFL;
                long sum = x + y;
                int carry = (sum >>> WORD_BITS) != 0 ? 1 : 0;
                setAReg((int) sum);
                setBReg(carry); // replaces B with carry
                // C unchanged
                return cycles;
            }
            case 0x84 -> { // SUBB: ..., x, y -> ..., borrow, diff
                long x = uword(BReg) & 0xFFFF_FFFFL;
                long y = uword(AReg) & 0xFFFF_FFFFL;
                int borrow = Long.compareUnsigned(x, y) < 0 ? 1 : 0;
                long diff = (x - y) & (WORD_MASK & 0xFFFF_FFFFL);
                setAReg((int) diff);
                setBReg(borrow); // replaces B with borrow
                // C unchanged
                return cycles;
            }
            case 0x88 -> { // MULH (signed upper)
                long x = (long) normalizeWord(BReg);
                long y = (long) normalizeWord(AReg);
                long prod = x * y;
                int hi = (int) ((prod >> WORD_BITS) & (WORD_MASK & 0xFFFF_FFFFL));
                setAReg(hi);
                BReg = CReg;
                return cycles;
            }
            case 0x8C -> { // MULHU (unsigned upper)
                long x = uword(BReg) & 0xFFFF_FFFFL;
                long y = uword(AReg) & 0xFFFF_FFFFL;
                long prod = x * y;
                int hi = (int) ((prod >>> WORD_BITS) & (WORD_MASK & 0xFFFF_FFFFL));
                setAReg(hi);
                BReg = CReg;
                return cycles;
            }
            case 0x90 -> { // CLZ (CLZ(0)=WORD_BITS)
                int x = uword(AReg);
                if (x == 0) {
                    setAReg(WORD_BITS);
                    return cycles;
                }

                // WORD_BITS is 16/24/32 in this emulator. We align the WORD to the top of 32 bits.
                int shift = 32 - WORD_BITS;
                int n = (shift == 0)
                        ? Integer.numberOfLeadingZeros(x)
                        : Integer.numberOfLeadingZeros(x << shift);
                setAReg(n);
                return cycles;
            }

            // -------------------------------------------------------------
            // ESC to EXT0
            // -------------------------------------------------------------
            case 0x7F -> { // ESC
                cycles += S_MEM_READ;
                int ext0 = fetchImm8();
                cycles += execExt0(ext0, insnPC);
                return cycles;
            }

            default -> {
                raiseTrap(TRAP_ILLEGAL_INSN, opcode, insnPC);
                return cycles;
            }
        }
    }

    /**
     * Execute one EXT0 step.
     *
     * <p>EXT0 is entered by ESC. EXT0 opcodes are 1 byte. 0xFF is XESC (chains to EXT1).
     * For now, EXT1+ are reserved and trap as illegal.</p>
     *
     * @return additional cycles consumed (excluding ESC fetch/decode already counted)
     */
    private int execExt0(int ext0, int escPC) {
        int cycles = 0;
        switch (ext0 & 0xFF) {
            case 0x01 -> { // MOVB (workspace: w0=dst,w1=src,w2=nbytes)
                if (!hasR8Blk) { raiseTrap(TRAP_ILLEGAL_INSN, 0x7F, escPC); return 0; }
                cycles += execBlkStep(BlkKind.MOVB, escPC);
            }
            case 0x05 -> { // MOVW (w2=nwords)
                if (!hasR8Blk) { raiseTrap(TRAP_ILLEGAL_INSN, 0x7F, escPC); return 0; }
                cycles += execBlkStep(BlkKind.MOVW, escPC);
            }
            case 0x09 -> { // FILLB
                if (!hasR8Blk) { raiseTrap(TRAP_ILLEGAL_INSN, 0x7F, escPC); return 0; }
                cycles += execBlkStep(BlkKind.FILLB, escPC);
            }
            case 0x0D -> { // FILLW
                if (!hasR8Blk) { raiseTrap(TRAP_ILLEGAL_INSN, 0x7F, escPC); return 0; }
                cycles += execBlkStep(BlkKind.FILLW, escPC);
            }
            case 0xFF -> { // XESC (chain)
                raiseTrap(TRAP_ILLEGAL_INSN, 0xFF, escPC);
            }
            default -> raiseTrap(TRAP_ILLEGAL_INSN, ext0, escPC);
        }
        return cycles;
    }

    private enum BlkKind { MOVB, MOVW, FILLB, FILLW }

    /**
     * Restartable, Z80-style block step:
     *  - Uses wksp[0..2] operands (dst/src-or-val/count).
     *  - Executes exactly ONE element per call.
     *  - If w2 != 0 after the element, PC is rewound to the ESC instruction (repeat).
     *  - If w2 == 0 on entry, does nothing and falls through.
     *
     * <p>Interrupts are serviced between element steps by the normal post-instruction
     * servicePendingInterrupts() call in executeInstruction().</p>
     */
    private int execBlkStep(BlkKind kind, int escPC) {
        int count = uword(wksp[2]);
        if (count == 0) return 0;

        int dst = maskAddr(wksp[0]);
        int srcOrVal = wksp[1];

        switch (kind) {
            case MOVB -> {
                byte v = readByte(srcOrVal);
                writeByte(dst, v);
                wksp[0] = maskAddr(dst + 1);
                wksp[1] = maskAddr(srcOrVal + 1);
                wksp[2] = uword(count - 1);
            }
            case MOVW -> {
                // Architectural step size is WORD_BYTES even if internally byte-copied.
                int aSrc = maskAddr(srcOrVal);
                for (int i = 0; i < WORD_BYTES; i++) {
                    byte v = readByte(aSrc + i);
                    writeByte(dst + i, v);
                }
                wksp[0] = maskAddr(dst + WORD_BYTES);
                wksp[1] = maskAddr(aSrc + WORD_BYTES);
                wksp[2] = uword(count - 1);
            }
            case FILLB -> {
                byte v = (byte) srcOrVal; // low 8 bits
                writeByte(dst, v);
                wksp[0] = maskAddr(dst + 1);
                // wksp[1] unchanged (value)
                wksp[2] = uword(count - 1);
            }
            case FILLW -> {
                writeWord(dst, srcOrVal);
                wksp[0] = maskAddr(dst + WORD_BYTES);
                // wksp[1] unchanged (value)
                wksp[2] = uword(count - 1);
            }
        }

        // Restartable semantics: repeat until count reaches 0.
        if (uword(wksp[2]) != 0) {
            // Rewind PC to the ESC instruction so we re-fetch ESC + ext opcode.
            // This guarantees MEPC points to ESC if an IRQ fires between iterations.
            setIPtr(escPC);
        }

        // Timing estimate: memory-dominated (simple + monotonic).
        return switch (kind) {
            case MOVB  -> S_MEM_READ + S_MEM_WRITE;
            case FILLB -> S_MEM_WRITE;
            case MOVW  -> WORD_BYTES * (S_MEM_READ + S_MEM_WRITE);
            case FILLW -> memWriteWordCycles();
        };
    }

    // =====================================================================
    // Trap / interrupt machinery
    // =====================================================================

    public final void setInterruptPending(int interruptBit) {
        mip |= (interruptBit & ASYNC_INTERRUPT_MASK);
    }

    private void servicePendingInterrupts() {
        if (!csrMieEnabled()) return;

        int pending = (mip & mie) & ASYNC_INTERRUPT_MASK;
        if (pending == 0) return;

        int bit = pickHighestPriorityInterruptBit(pending);
        if (bit == 0) return;

        // acknowledge
        mip &= ~bit;

        final int cause = switch (bit) {
            case SOFTWARE_INTERRUPT_MASK -> IRQ_SW;
            case TIMER_INTERRUPT_MASK    -> IRQ_TIMER;
            case EXTERNAL_INTERRUPT_MASK -> IRQ_EXT;
            default -> throw new IllegalStateException("Unexpected interrupt bit: " + bit);
        };

        // IPtr is already the next instruction (post-execute) here.
        enterVector(true, cause, 0, IPtr);
    }

    protected final void raiseTrap(int trapCause, int tval, int insnPC) {
        enterVector(false, trapCause, tval, insnPC);
    }

    /** Common trap/interrupt entry (direct mode). */
    private void enterVector(boolean isInterrupt, int cause, int tval, int epc) {
        halted = false;
        vectorEntered = true;

        mtval = uword(tval);
        mepc = maskAddr(epc);
        mcause = uword((isInterrupt ? WORD_SIGNBIT : 0) | (cause & (WORD_SIGNBIT - 1)));

        // MPIE <- MIE; MIE <- 0
        if (csrMieEnabled()) mstatus |= MSTATUS_MPIE;
        else mstatus &= ~MSTATUS_MPIE;
        mstatus &= ~MSTATUS_MIE;

        if (!isInterrupt) {
            if (cause == TRAP_ECALL) handleECall();
            if (cause == TRAP_EBREAK) handleEBreak();
        }

        setIPtr(mtvec);
    }

    private void mret() {
        setIPtr(mepc);

        boolean prior = (mstatus & MSTATUS_MPIE) != 0;
        if (prior) mstatus |= MSTATUS_MIE;
        else mstatus &= ~MSTATUS_MIE;

        // per RISC-V style: MPIE set on return
        mstatus |= MSTATUS_MPIE;
    }

    private static int pickHighestPriorityInterruptBit(int pending) {
        if ((pending & SOFTWARE_INTERRUPT_MASK) != 0) return SOFTWARE_INTERRUPT_MASK;
        if ((pending & TIMER_INTERRUPT_MASK) != 0) return TIMER_INTERRUPT_MASK;
        if ((pending & EXTERNAL_INTERRUPT_MASK) != 0) return EXTERNAL_INTERRUPT_MASK;
        return 0;
    }

    // =====================================================================
    // Hooks
    // =====================================================================

    protected void handleEBreak() {
        Debugger dbg = this.debugger;
        if (dbg != null) dbg.onEbreak(this);
    }

    protected void handleECall() {
        // platform-defined (syscall ABI)
    }

    // =====================================================================
    // Debug / UI accessors (read-only)
    // =====================================================================

    private transient Debugger debugger;

    public final void setDebugger(Debugger dbg) {
        this.debugger = dbg;
    }

    public record CpuSnapshot(
            int ip, int sp, int a, int b, int c,
            int[] wksp,
            boolean halted,

            int mtvec, int mepc, int mcause, int mtval,
            int mstatus, int mie, int mip,

            int addrMask, int wordBytes, int wordMask, int wordSignBit,
            int mext
    ) {}

    public final CpuSnapshot snapshot() {
        int[] w = wksp.clone();
        return new CpuSnapshot(
                IPtr,
                w[15],
                AReg, BReg, CReg,
                w,
                halted,
                mtvec, mepc, mcause, mtval,
                mstatus, mie, mip,
                ADDR_MASK, WORD_BYTES, WORD_MASK, WORD_SIGNBIT,
                csrMextValue()
        );
    }

    public final int ip() { return IPtr; }
    public final int sp() { return wksp[15]; }

    public final int a() { return AReg; }
    public final int b() { return BReg; }
    public final int c() { return CReg; }

    public final int wksp(int index) { return wksp[index & 0xFF]; }

    public final boolean isHalted() { return halted; }

    public final int addrMask()    { return ADDR_MASK; }
    public final int wordBytes()   { return WORD_BYTES; }
    public final int wordMask()    { return WORD_MASK; }
    public final int wordSignBit() { return WORD_SIGNBIT; }

    public final int mtvec()  { return mtvec; }
    public final int mepc()   { return mepc; }
    public final int mcause() { return mcause; }
    public final int mtval()  { return mtval; }

    public final int mstatus() { return mstatus; }
    public final int mie()     { return mie; }
    public final int mip()     { return mip; }

    public final boolean mcauseIsInterrupt() { return (mcause & WORD_SIGNBIT) != 0; }

    public final int mcauseCode() { return mcause & (WORD_SIGNBIT - 1); }

    public final String mcauseText() {
        int code = mcauseCode();
        if (mcauseIsInterrupt()) {
            return switch (code) {
                case IRQ_SW    -> "IRQ_SW";
                case IRQ_TIMER -> "IRQ_TIMER";
                case IRQ_EXT   -> "IRQ_EXT";
                default -> "IRQ(" + code + ")";
            };
        }
        return switch (code) {
            case TRAP_INSN_MISALIGNED -> "TRAP_INSN_MISALIGNED";
            case TRAP_ILLEGAL_INSN    -> "TRAP_ILLEGAL_INSN";
            case TRAP_EBREAK          -> "TRAP_EBREAK";
            case TRAP_ECALL           -> "TRAP_ECALL";
            case TRAP_DIV_ZERO        -> "TRAP_DIV_ZERO";
            default -> "TRAP(" + code + ")";
        };
    }

    public final int debuggerStopPC() {
        // If the CPU is sitting at mtvec, show the trapped PC instead.
        if (maskAddr(IPtr) == maskAddr(mtvec)) return maskAddr(mepc);
        return maskAddr(IPtr);
    }
}
