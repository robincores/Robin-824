package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.system.InterruptSink;

public abstract class R8Core implements InterruptSink {

    // =====================================================================
    // ISA-visible constants (interrupt bitmasks, CSR ids, trap causes)
    // =====================================================================

    // Async interrupt bitmasks (ONLY async sources)
    public static final int SOFTWARE_INTERRUPT_MASK = 0x01; // bit 0
    public static final int TIMER_INTERRUPT_MASK = 0x02; // bit 1
    public static final int EXTERNAL_INTERRUPT_MASK = 0x04; // bit 2

    // Only these are valid interrupt bits now
    private static final int ASYNC_INTERRUPT_MASK =
            SOFTWARE_INTERRUPT_MASK | TIMER_INTERRUPT_MASK | EXTERNAL_INTERRUPT_MASK;

    // Optional: interrupt cause numbers (these can overlap trap causes because tables differ)
    private static final int IRQ_SW = 0;
    private static final int IRQ_TIMER = 1;
    private static final int IRQ_EXT = 2;

    // Trap causes (synchronous) — RISC-V codes where applicable
    private static final int TRAP_INSN_MISALIGNED = 0x00; // instruction address misaligned (reserved)
    private static final int TRAP_ILLEGAL_INSN = 0x02; // unrecognized opcode
    private static final int TRAP_EBREAK = 0x03; // breakpoint
    private static final int TRAP_ECALL = 0x08; // environment call
    private static final int TRAP_DIV_ZERO = 0x18; // divide/remainder by zero (R8-specific)

    // CSR ids
    private static final int CSR_MSTATUS = 0x00;
    private static final int CSR_MIE = 0x01;
    private static final int CSR_MIP = 0x02;
    private static final int CSR_MTVEC = 0x03;
    private static final int CSR_MEPC = 0x04;
    private static final int CSR_MCAUSE = 0x05;
    private static final int CSR_MTVAL = 0x06;

    // mstatus bits (minimal)
    private static final int MSTATUS_MIE = 1;
    private static final int MSTATUS_MPIE = 1 << 1;

    // =====================================================================
    // Core dependencies + CPU shape (constructor-defined finals)
    // =====================================================================

    protected final Bus bus;

    protected final int ADDR_MASK;     // 0xFFFF / 0xFF_FFFF / 0xFFFF_FFFF
    protected final int WORD_BYTES;    // 2 / 3 / 4
    protected final int WORD_MASK;     // 0xFFFF / 0xFF_FFFF / 0xFFFF_FFFF
    protected final int WORD_SIGNBIT;  // 0x8000 / 0x80_0000 / 0x8000_0000

    protected R8Core(Bus bus, int addrMask, int wordBytes, int wordMask, int wordSignBit) {
        this.bus = bus;
        this.ADDR_MASK = addrMask;
        this.WORD_BYTES = wordBytes;
        this.WORD_MASK = wordMask;
        this.WORD_SIGNBIT = wordSignBit;
    }

    @Override
    public void raise(int interruptBit) {
        setInterruptPending(interruptBit);

        // strongly recommended: wake the CPU if it's halted/sleeping
        // (so timer/vblank IRQ resumes execution)
        this.halted = false;
    }

    // =====================================================================
    // Architectural state (registers, flags)
    // =====================================================================

    protected final int[] wksp = new int[16]; // workspace regs (wksp[15] = SP)
    protected int AReg, BReg, CReg;
    protected int IPtr = 0;

    private boolean halted = false;

    // =====================================================================
    // Timing model
    // =====================================================================

    private static final int S_IFETCH = 1;
    private static final int S_DECODE = 1;
    private static final int S_MEM_READ = 1;
    private static final int S_MEM_WRITE = 1;

    protected final int memReadWordCycles() {
        return S_MEM_READ * WORD_BYTES;
    }

    protected final int memWriteWordCycles() {
        return S_MEM_WRITE * WORD_BYTES;
    }

    protected int wordCycles() {
        return WORD_BYTES;
    }

    // =====================================================================
    // CSR / trap / interrupt registers
    // =====================================================================

    /**
     * MTVEC (Machine Trap-Vector Base Address).
     * <p>
     * Address jumped to on any trap or interrupt (direct mode, like RISC-V MODE=0).
     * The handler reads {@code mcause} to determine the event type and cause.
     * </p>
     */
    protected int mtvec = 0x0000;

    /**
     * MIP (Machine Interrupt Pending).
     * <p>
     * Bitmask of pending <b>asynchronous</b> interrupts (SW/TIMER/EXT only).
     * Effective pending interrupts are {@code (mip & mie)}.
     * </p>
     * <p>
     * Recommended write semantics:
     * SW bit is directly writable (software can set/clear it).
     * TIMER/EXT are W1C (write-1 clears) and otherwise set by hardware/peripherals.
     * </p>
     */
    private int mip = 0;

    /**
     * MIE (Machine Interrupt Enable).
     * <p>
     * Per-source enable mask for <b>asynchronous</b> interrupts (SW/TIMER/EXT only).
     * Global interrupt enable is controlled by {@code mstatus.MIE}.
     * </p>
     */
    private int mie = TIMER_INTERRUPT_MASK; // default enable (adjust as desired)

    /**
     * MSTATUS (Machine Status).
     * <p>
     * Minimal implementation: {@code MIE} (global enable) and {@code MPIE} (saved MIE on entry).
     * </p>
     */
    private int mstatus = 0;

    /**
     * MEPC (Machine Exception Program Counter).
     * <p>
     * Return address saved on trap/interrupt entry; restored by {@code IRET/mret()}.
     * For interrupts, points to the next instruction to execute.
     * </p>
     */
    private int mepc = 0;

    /**
     * MCAUSE (Machine Cause).
     * <p>
     * Encodes whether the event was an interrupt (high bit set) or trap (high bit clear),
     * plus the cause code in the low bits.
     * </p>
     */
    private int mcause = 0;

    /**
     * MTVAL (Machine Trap Value).
     * <p>
     * Optional trap-specific information (e.g., faulting value/address). Zero if unused.
     * </p>
     */
    private int mtval = 0;

    // =====================================================================
    // Width + register helpers
    // =====================================================================

    protected final int maskAddr(int a) {
        return a & ADDR_MASK;
    }

    protected final int uword(int v) {
        return v & WORD_MASK;
    }

    protected final int normalizeWord(int v) {
        v &= WORD_MASK;
        if ((v & WORD_SIGNBIT) != 0) v |= ~WORD_MASK;
        return v;
    }

    protected final void setIPtr(int address) {
        this.IPtr = maskAddr(address);
    }

    protected final void setAReg(int value) {
        this.AReg = normalizeWord(value);
    }

    protected final void incSP() {
        wksp[15] = maskAddr(wksp[15] + WORD_BYTES);
    }

    protected final void decSP() {
        wksp[15] = maskAddr(wksp[15] - WORD_BYTES);
    }

    int signExtend8to32(int value) {
        return (value & 0x80) != 0 ? value | 0xFFFFFF00 : value & 0xFF;
    }

    // =====================================================================
    // Memory helpers (byte/word + fetch)
    // =====================================================================

    protected final byte readByte(int address) {
        return bus.read8(maskAddr(address));
    }

    protected final void writeByte(int address, byte value) {
        bus.write8(maskAddr(address), value);
    }

    /**
     * Reads masked address and returns unsigned word value
     */
    protected final int readWord(int address) {
        int v = 0;
        int a = maskAddr(address);
        for (int i = 0; i < WORD_BYTES; i++) v |= (Byte.toUnsignedInt(readByte(a + i)) << (8 * i));
        return v & WORD_MASK;
    }

    /**
     * Writes masked word value to masked address
     */
    protected final void writeWord(int address, int value) {
        int a = maskAddr(address);
        int v = value & WORD_MASK;
        for (int i = 0; i < WORD_BYTES; i++) writeByte(a + i, (byte) (v >>> (8 * i)));
    }

    protected final int fetchNextInstruction() {
        int insn = Byte.toUnsignedInt(readByte(IPtr));
        setIPtr(IPtr + 1);
        return insn;
    }

    protected final int fetchByteOperand() {
        int op = Byte.toUnsignedInt(readByte(IPtr));
        setIPtr(IPtr + 1);
        return op;
    }

    protected final int fetchWordOperand() {
        int v = 0;
        for (int i = 0; i < WORD_BYTES; i++) v |= fetchByteOperand() << (8 * i);
        return v & WORD_MASK;
    }

    // =====================================================================
    // CSR accessors
    // =====================================================================

    private boolean csrMieEnabled() {
        return (mstatus & MSTATUS_MIE) != 0;
    }

    private void csrSetMie(boolean v) {
        if (v) mstatus |= MSTATUS_MIE;
        else mstatus &= ~MSTATUS_MIE;
    }

    private int readCSR(int csr) {
        return switch (csr & 0xFF) {
            case CSR_MSTATUS -> uword(mstatus);
            case CSR_MIE -> uword(mie) & ASYNC_INTERRUPT_MASK;
            case CSR_MIP -> uword(mip) & ASYNC_INTERRUPT_MASK;
            case CSR_MTVEC -> maskAddr(mtvec);
            case CSR_MEPC -> maskAddr(mepc);
            case CSR_MCAUSE -> uword(mcause);
            case CSR_MTVAL -> uword(mtval);
            default -> 0;
        };
    }

    private void writeCSR(int csr, int value) {
        switch (csr & 0xFF) {
            case CSR_MSTATUS -> mstatus = uword(value) & (MSTATUS_MIE | MSTATUS_MPIE);
            case CSR_MIE -> mie = (uword(value) & ASYNC_INTERRUPT_MASK);

            /**
             * CSR_MIP write semantics (tighter):
             *  - SW bit (bit0) is directly writable (set/clear)
             *  - TIMER/EXT bits are W1C only (write-1 clears), otherwise read-only to software
             */
            case CSR_MIP -> {
                int v = uword(value) & ASYNC_INTERRUPT_MASK;

                // SW pending: direct write (software can raise/clear SW interrupt)
                if ((v & SOFTWARE_INTERRUPT_MASK) != 0) mip |= SOFTWARE_INTERRUPT_MASK;
                else mip &= ~SOFTWARE_INTERRUPT_MASK;

                // TIMER/EXT: write-1-to-clear only
                int w1c = v & (TIMER_INTERRUPT_MASK | EXTERNAL_INTERRUPT_MASK);
                mip &= ~w1c;
            }

            case CSR_MTVEC -> mtvec = maskAddr(value);
            case CSR_MEPC -> mepc = maskAddr(value);
            case CSR_MCAUSE -> mcause = uword(value);
            case CSR_MTVAL -> mtval = uword(value);
            default -> {
            }
        }
    }

    // =====================================================================
    // Execution API
    // =====================================================================

    public int executeInstruction() {
        if (halted) {
            // allow IRQs to wake the CPU
            servicePendingInterrupts();
            return 1; // idle cycle
        }

        // Capture the PC of the instruction we're about to execute.
        // If we trap (e.g., ILLEGAL_INSN), this is the address we save in MEPC.
        int insnPC = IPtr;

        int instruction = fetchNextInstruction();
        int cycles = decodeAndExecute(instruction, insnPC);

        // After each instruction, service any pending interrupts.
        servicePendingInterrupts();

        return cycles;
    }

    private int decodeAndExecute(int instruction, int insnPC) {
        int cycles = 0, tReg;

        switch (instruction) {
            // -------------------------------------------------------------
            // --- 0b0_00000_00
            // -------------------------------------------------------------
            case 0b0_00000_00 -> {// 0x00: NOP
                cycles += S_IFETCH + S_DECODE;
            }
            case 0b0_00001_00 -> // 0x04: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00010_00 -> { // 0x08: DUP [A=A, B=A, C=B]
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
            }
            case 0b0_00011_00 -> { // 0x0C: SWAP [A=B, B=A, C=C]
                cycles += S_IFETCH + S_DECODE;
                tReg = BReg;
                BReg = AReg;
                AReg = tReg;
            }
            // -------------------------------------------------------------
            case 0b0_00100_00 -> { // 0x10: ADD [A=B+A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg + AReg);
                BReg = CReg;
            }
            case 0b0_00101_00 -> {  // 0x14: SUB [A=B-A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg - AReg);
                BReg = CReg;
            }
            case 0b0_00110_00 -> { // 0x18: MUL [A=B*A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg * AReg);
                BReg = CReg;
            }
            case 0b0_00111_00 -> { // 0x1C: DIV [A=B/A, B=c (A != 0)]
                cycles += S_IFETCH + S_DECODE;
                if (AReg == 0) {
                    raiseTrap(TRAP_DIV_ZERO, 0, insnPC);
                    return cycles; // IMPORTANT
                }
                setAReg(BReg / AReg);
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b0_01000_00 -> { // 0x20: AND [A=B&A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg & AReg);
                BReg = CReg;
            }
            case 0b0_01001_00 -> { // 0x24: OR [A=B|A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg | AReg);
                BReg = CReg;
            }
            case 0b0_01010_00 -> { // 0x28: XOR [A=B^A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg ^ AReg);
                BReg = CReg;
            }
            case 0b0_01011_00 -> { // 0x2C: REM [A=B%A, B=C]
                cycles += S_IFETCH + S_DECODE;
                if (AReg == 0) {
                    raiseTrap(TRAP_DIV_ZERO, 0, insnPC);
                    return cycles; // IMPORTANT
                }
                setAReg(BReg % AReg);
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b0_01100_00 -> { // 0x30: SLL 1 [A = A << 1]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 1); // Shift left by 1 and mask to 24 bits
            }
            case 0b0_01101_00 -> { // 0x34: SLL 2 [A = A << 2]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 2); // Shift left by 2 and mask to 24 bits
            }
            case 0b0_01110_00 -> { // 0x38: SLL 3 [A = A << 3]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 3); // Shift left by 3 and mask to 24 bits
            }
            case 0b0_01111_00 -> { // 0x3C: SLL 4 [A = A << 4]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 4); // Shift left by 4
            }
            // -------------------------------------------------------------
            // --- 0b0_10000_00
            // -------------------------------------------------------------
            case 0b0_10000_00 -> { // 0x40: INC [A=A+1]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg + 1);  // Increment
            }
            case 0b0_10001_00 -> {  // 0x44: DEC [A=A-1]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg - 1);  // Decrement
            }
            case 0b0_10010_00 -> { // 0x48: NEG [A=-A]
                cycles += S_IFETCH + S_DECODE;
                setAReg(-AReg);  // Negate
            }
            case 0b0_10011_00 -> { // 0x4C: INV [A=~A]
                cycles += S_IFETCH + S_DECODE;
                setAReg(~AReg);
            }
            // -------------------------------------------------------------
            case 0b0_10100_00 -> // 0x50: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10101_00 -> // 0x54: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10110_00 -> // 0x58: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10111_00 -> { // 0x5C: I2B [A=(byte)A]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg & 0xFF); // Mask to keep only the lower 8 bits (convert to byte)
            }
            // -------------------------------------------------------------
            case 0b0_11000_00 -> { // 0x60: SLT [A=(B<A)?1:0, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg((BReg < AReg) ? 1 : 0);
                BReg = CReg;
            }
            case 0b0_11001_00 -> { // 0x64: SLTU [A=(B<A)?1:0, B=C (unsigned)]
                cycles += S_IFETCH + S_DECODE;
                setAReg((Integer.compareUnsigned(BReg, AReg) < 0) ? 1 : 0); // Unsigned comparison
                BReg = CReg;
            }
            case 0b0_11010_00 -> // 0x68: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11011_00 -> // 0x6C: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_11100_00 -> // 0x70: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11101_00 -> // 0x74: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11110_00 -> { // 0x78: DROP1 [A=B,B=C]
                cycles += S_IFETCH + S_DECODE;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11111_00 -> { // 0x7C: DROP2 [A=C]
                cycles += S_IFETCH + S_DECODE;
                AReg = BReg = CReg;
            }
            // -------------------------------------------------------------
            // === 0b1_00000_00 (LD)
            // -------------------------------------------------------------
            case 0b1_00000_00 -> // 0x80: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00001_00 -> // 0x84: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00010_00 -> { // 0x88: LD [A=[A]]
                cycles += S_IFETCH + S_DECODE + memReadWordCycles();
                setAReg(readWord(AReg));
            }
            case 0b1_00011_00 -> // 0x8C: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_00100_00 -> // 0x90: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00101_00 -> // 0x94: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00110_00 -> // 0x98: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00111_00 -> // 0x9C: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01000_00 -> // 0xA0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01001_00 -> // 0xA4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01010_00 -> // 0xA8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01011_00 -> // 0xAC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01100_00 -> { // 0xB0: POP A=[SP], B=A (before read), C=B
                cycles += S_IFETCH + S_DECODE + memReadWordCycles();

                // Preserve the old values of AReg and BReg
                CReg = BReg;          // Move BReg into CReg
                BReg = AReg;          // Move the old AReg into BReg before reading

                // Read the word value from memory at the address pointed to by SP (wksp[15])
                setAReg(readWord(wksp[15]));  // Load and sign-extend

                // Increment SP to point to the next location
                incSP();
            }
            case 0b1_01101_00 -> // 0xB4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01110_00 -> // 0xB8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01111_00 -> // 0xBC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10000_00 -> // 0xC0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10001_00 -> // 0xC4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10010_00 -> // 0xC8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10011_00 -> // 0xCC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10100_00 -> // 0xD0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10101_00 -> // 0xD4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10110_00 -> // 0xD8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10111_00 -> // 0xDC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11000_00 -> // 0xE0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11001_00 -> // 0xE4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11010_00 -> // 0xE8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11011_00 -> // 0xEC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11100_00 -> { // 0xF0: PUSH [[SP]=A, A=B, B=C (SP=SP-3)]
                cycles += S_IFETCH + S_DECODE + memWriteWordCycles();

                // Calculate the new stack pointer address and write AReg's value
                decSP();  // Decrease SP and ensure it's within cpu width range
                writeWord(wksp[15], AReg);  // Write AReg's 24-bit value to memory at SP

                // Move the values from BReg and CReg
                AReg = BReg;  // AReg takes the value of BReg
                BReg = CReg;  // BReg takes the value of CReg
            }
            case 0b1_11101_00 -> // 0xF4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11110_00 -> { // 0xF8: ST [[B]=A, A=B, B=C]
                cycles += S_IFETCH + S_DECODE + memWriteWordCycles();

                // Write the 24-bit value of AReg into memory at the address in BReg
                writeWord(BReg, AReg);

                // Move the values from BReg and CReg
                AReg = BReg;  // AReg takes the value of BReg
                BReg = CReg;  // BReg takes the value of CReg
            }
            case 0b1_11111_00 -> // 0xFC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b0_00000_01 (LB)
            // -------------------------------------------------------------
            case 0b0_00000_01 -> // 0x01: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00001_01 -> // 0x05: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00010_01 -> { // 0x09: LB A=[A] (Load Byte and Sign-Extend)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                int addr = AReg;
                setAReg(readByte(addr));   // sign-extend 8-bit -> int, then normalize
            }
            case 0b0_00011_01 -> // 0x0D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_00100_01 -> // 0x11: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00101_01 -> // 0x15: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00110_01 -> // 0x19: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00111_01 -> // 0x1D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_01000_01 -> // 0x21: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01001_01 -> // 0x25: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01010_01 -> // 0x29: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01011_01 -> // 0x2D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_01100_01 -> // 0x31: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01101_01 -> // 0x35: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01110_01 -> // 0x39: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01111_01 -> // 0x3D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_10000_01 -> // 0x41: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10001_01 -> // 0x45: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10010_01 -> // 0x49: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10011_01 -> // 0x4D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_10100_01 -> // 0x51: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10101_01 -> // 0x55: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10110_01 -> // 0x59: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_10111_01 -> // 0x5D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_11000_01 -> // 0x61: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11001_01 -> // 0x65: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11010_01 -> // 0x69: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11011_01 -> // 0x6D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_11100_01 -> // 0x71: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11101_01 -> // 0x75: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11110_01 -> { // 0x79: SB [B] = A (signed 8-bit), A = B, B = C
                cycles += S_IFETCH + S_DECODE + S_MEM_WRITE;

                // Write only the lower 8 bits of AReg (signed byte) into memory at the address in BReg
                writeByte(BReg, (byte) AReg);

                // Move the values from BReg and CReg
                AReg = BReg;  // AReg takes the value of BReg
                BReg = CReg;  // BReg takes the value of CReg
            }
            case 0b0_11111_01 -> // 0x7D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b1_00000_01 (LU)
            // -------------------------------------------------------------
            case 0b1_00000_01 -> // 0x81: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00001_01 -> // 0x85: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00010_01 -> { // 0x89: LU [A=[A] (Load Unsigned Byte)]
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                int addr = AReg;
                setAReg(Byte.toUnsignedInt(readByte(addr)));  // 0..255
            }
            case 0b1_00011_01 -> // 0x8D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_00100_01 -> // 0x91: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00101_01 -> // 0x95: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00110_01 -> // 0x99: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00111_01 -> // 0x9D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01000_01 -> // 0xA1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01001_01 -> // 0xA5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01010_01 -> // 0xA9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01011_01 -> // 0xAD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01100_01 -> // 0xB1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01101_01 -> // 0xB5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01110_01 -> // 0xB9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01111_01 -> // 0xBD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10000_01 -> // 0xC1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10001_01 -> // 0xC5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10010_01 -> // 0xC9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10011_01 -> // 0xCD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10100_01 -> // 0xD1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10101_01 -> // 0xD5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10110_01 -> // 0xD9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10111_01 -> // 0xDD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11000_01 -> // 0xE1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11001_01 -> // 0xE5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11010_01 -> // 0xE9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11011_01 -> // 0xED: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11100_01 -> // 0xF1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11101_01 -> // 0xF5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11110_01 -> // 0xF9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11111_01 -> // 0xFD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b0_00000_10 (B k)
            // -------------------------------------------------------------
            case 0b0_00000_10 -> // 0x02: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00001_10 -> // 0x06: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00010_10 -> { // 0x0A: B k [A=imm8,B=A,C=B] (Load Immediate Signed Byte and Sign-Extend)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                // Preserve the old values of AReg and BReg
                CReg = BReg;              // Move BReg into CReg
                BReg = AReg;              // Move the old AReg into BReg before reading

                // Read the signed byte from memory and sign-extend it into AReg
                setAReg(signExtend8to32(fetchByteOperand()));
            }
            case 0b0_00011_10 -> // 0x0E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_00100_10 -> // 0x12: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00101_10 -> // 0x16: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00110_10 -> // 0x1A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_00111_10 -> // 0x1E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_01000_10 -> // 0x22: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01001_10 -> // 0x26: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01010_10 -> // 0x2A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_01011_10 -> // 0x2E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_01100_10 -> { // 0x32: SRL 1 (A = A >>> 1)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 1);
            }
            case 0b0_01101_10 -> { // 0x36: SRL 2 (A = A >>> 2)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 2);
            }
            case 0b0_01110_10 -> { // 0x3A: SRL 3 (A = A >>> 3)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 3);
            }
            case 0b0_01111_10 -> { // 0x3E: SRL 4 (A = A >>> 4)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 4);
            }
            // -------------------------------------------------------------
            case 0b0_10000_10 -> { // 0x42: BEQ k (IPtr = IPtr + k, B == A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                if (BReg == AReg) {
                    int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                } else {
                    setIPtr(IPtr + 1);
                }
                AReg = CReg; // A takes value of C
            }
            case 0b0_10001_10 -> { // 0x46: BNE k (IPtr = IPtr + k, B != A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                if (BReg != AReg) {
                    int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                } else {
                    setIPtr(IPtr + 1);
                }
                AReg = CReg; // A takes value of C
            }
            case 0b0_10010_10 -> {// 0x4A: BRA k (IPtr = IPtr + )
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                setIPtr(IPtr + offset);// Apply offset to instruction pointer
            }
            case 0b0_10011_10 -> // 0x4E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b0_10100_10 -> { // 0x52: BLT k (IPtr = IPtr + k, B < A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                if (BReg < AReg) {
                    int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                } else {
                    setIPtr(IPtr + 1);
                }
                AReg = CReg; // A takes value of C
            }
            case 0b0_10101_10 -> { // 0x56: BLTU k (IPtr = IPtr + k, B < A (unsigned), A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                if (Integer.compareUnsigned(BReg, AReg) < 0) {
                    int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                    setIPtr(IPtr + offset);// Apply offset to instruction pointers
                } else {
                    setIPtr(IPtr + 1);
                }
                AReg = CReg; // A takes value of C
            }
            case 0b0_10110_10 -> { // 0x5A: BGE k (IPtr = IPtr + k, B >= A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                if (BReg >= AReg) {
                    int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                    setIPtr(IPtr + offset);// Apply offset to instruction pointers
                } else {
                    setIPtr(IPtr + 1);
                }
                AReg = CReg; // A takes value of C
            }
            case 0b0_10111_10 -> { // 0x5E: BGEU k (IPtr = IPtr + k, B >= A (unsigned), A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                if (Integer.compareUnsigned(BReg, AReg) >= 0) {
                    int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                } else {
                    setIPtr(IPtr + 1);
                }
                AReg = CReg; // A takes value of C
            }
            // -------------------------------------------------------------
            case 0b0_11000_10 -> { // 0x62: J w (IPtr = IPtr + w)
                cycles += S_IFETCH + S_DECODE + memReadWordCycles();
                int offset = normalizeWord(fetchWordOperand());
                //int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                setIPtr(IPtr + offset);// Apply offset to instruction pointer
            }
            case 0b0_11001_10 -> { // 0x66: JAL w [IPtr = IPtr + w, A = PC + 1]
                cycles += S_IFETCH + S_DECODE + memReadWordCycles();
                int offset = normalizeWord(fetchWordOperand());
                //int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                setAReg(maskAddr(IPtr));      // return = next instruction (after imm8)
                setIPtr(IPtr + offset);// Apply offset to instruction pointer
            }
            case 0b0_11010_10 -> { // 0x6A: JR (PC = A, A = B, B = C)
                cycles += S_IFETCH + S_DECODE;
                setIPtr(AReg); // Jump to address in AReg
                AReg = BReg; // A takes value of B
                BReg = CReg; // B takes value of C
            }
            case 0b0_11011_10 -> { // 0x6E: JALR (PC = A, A = PC + 1)
                cycles += S_IFETCH + S_DECODE;
                tReg = maskAddr(IPtr); // Temporarily store the current PC
                setIPtr(AReg); // Jump to address in AReg
                setAReg(tReg);
            }
            // -------------------------------------------------------------
            case 0b0_11100_10 -> // 0x72: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11101_10 -> // 0x76: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b0_11110_10 -> { // 0x7A: ECALL: Environment/System Call
                // Increment the cycle count for instruction fetch and decode
                cycles += S_IFETCH + S_DECODE;
                raiseTrap(TRAP_ECALL, 0, insnPC);
                return cycles;
            }
            case 0b0_11111_10 -> // 0x7E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b1_00000_10 (U k)
            // -------------------------------------------------------------
            case 0b1_00000_10 -> // 0x82: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00001_10 -> // 0x86: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00010_10 -> { // 8x8A: U A=[A] (Load Immediate Unsigned Byte)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                // Preserve the old values of AReg and BReg
                CReg = BReg;              // Move BReg into CReg
                BReg = AReg;              // Move the old AReg into BReg before reading

                // Load the unsigned byte and zero-extend into AReg
                setAReg(fetchByteOperand());  // Load byte, mask to ensure it's unsigned
            }
            case 0b1_00011_10 -> // 0x8E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_00100_10 -> // 0x92: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00101_10 -> // 0x96: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00110_10 -> // 0x9A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00111_10 -> // 0x9E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01000_10 -> // 0xA2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01001_10 -> // 0xA6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01010_10 -> // 0xAA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01011_10 -> // 0xAE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01100_10 -> { // 0xB2: SRA 1 A = A >> 1
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 1);
            }
            case 0b1_01101_10 -> { // 0xB6: SRA 2 A = A >> 2
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 2);
            }
            case 0b1_01110_10 -> { // 0xBA: SRA 3 A = A >> 3
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 3);
            }
            case 0b1_01111_10 -> { // 0xBE: SRA 4 A = A >> 4
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 4);
            }
            // -------------------------------------------------------------
            case 0b1_10000_10 -> // 0xC2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10001_10 -> // 0xC6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10010_10 -> // 0xCA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10011_10 -> // 0xCE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10100_10 -> // 0xD2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10101_10 -> // 0xD6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10110_10 -> // 0xDA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10111_10 -> // 0xDE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11000_10 -> // 0xE2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11001_10 -> // 0xE6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11010_10 -> // 0xEA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11011_10 -> // 0xEE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11100_10 -> // 0xF2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11101_10 -> // 0xF6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_11110_10 -> { // 0xFA: EBREAK: Breakpoint for debugging or halting the CPU
                // Increment the cycle count for instruction fetch and decode
                cycles += S_IFETCH + S_DECODE;
                raiseTrap(TRAP_EBREAK, 0, insnPC);
                return cycles;
            }
            case 0b1_11111_10 -> // 0xFE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b0_00000_11 (LDL)
            // -------------------------------------------------------------
            case 0b0_00000_11 -> { // 0x03: LDL @0
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[0];
            }
            case 0b0_00001_11 -> { // 0x07: LDL @1
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[1];
            }
            case 0b0_00010_11 -> { // 0x0B: LDL @2
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[2];
            }
            case 0b0_00011_11 -> { // 0x0F: LDL @3
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[3];
            }
            // -------------------------------------------------------------
            case 0b0_00100_11 -> { // 0x13: LDL @4
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[4];
            }
            case 0b0_00101_11 -> { // 0x17: LDL @5
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[5];
            }
            case 0b0_00110_11 -> { // 0x1B: LDL @6
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[6];
            }
            case 0b0_00111_11 -> { // 0x1F: LDL @7
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[7];
            }
            // -------------------------------------------------------------
            case 0b0_01000_11 -> { // 0x23: LDL @8
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[8];
            }
            case 0b0_01001_11 -> { // 0x27: LDL @9
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[9];
            }
            case 0b0_01010_11 -> { // 0x2B: LDL @10
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[10];
            }
            case 0b0_01011_11 -> { // 0x2F: LDL @11
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[11];
            }
            // -------------------------------------------------------------
            case 0b0_01100_11 -> { // 0x33: LDL @12
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[12];
            }
            case 0b0_01101_11 -> { // 0x37: LDL @13
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[13];
            }
            case 0b0_01110_11 -> { // 0x3B: LDL @14
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[14];
            }
            case 0b0_01111_11 -> { // 0x3F: LDL @15
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[15];
            }
            // -------------------------------------------------------------
            case 0b0_10000_11 -> { // 0x43: STL @0
                cycles += S_IFETCH + S_DECODE;
                wksp[0] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_10001_11 -> { // 0x47: STL @1
                cycles += S_IFETCH + S_DECODE;
                wksp[1] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_10010_11 -> { // 0x4B: STL @2
                cycles += S_IFETCH + S_DECODE;
                wksp[2] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_10011_11 -> { // 0x4F: STL @3
                cycles += S_IFETCH + S_DECODE;
                wksp[3] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b0_10100_11 -> { // 0x53: STL @4
                cycles += S_IFETCH + S_DECODE;
                wksp[4] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_10101_11 -> { // 0x57: STL @5
                cycles += S_IFETCH + S_DECODE;
                wksp[5] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_10110_11 -> { // 0x5B: STL @6
                cycles += S_IFETCH + S_DECODE;
                wksp[6] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_10111_11 -> { // 0x5F: STL @7
                cycles += S_IFETCH + S_DECODE;
                wksp[7] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b0_11000_11 -> { // 0x63: STL @8
                cycles += S_IFETCH + S_DECODE;
                wksp[8] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11001_11 -> { // 0x67: STL @9
                cycles += S_IFETCH + S_DECODE;
                wksp[9] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11010_11 -> { // 0x6B: STL @10
                cycles += S_IFETCH + S_DECODE;
                wksp[10] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11011_11 -> { // 0x6F: STL @11
                cycles += S_IFETCH + S_DECODE;
                wksp[11] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b0_11100_11 -> { // 0x73: STL @12
                cycles += S_IFETCH + S_DECODE;
                wksp[12] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11101_11 -> { // 0x77: STL @13
                cycles += S_IFETCH + S_DECODE;
                wksp[13] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11110_11 -> { // 0x7B: STL @14
                cycles += S_IFETCH + S_DECODE;
                wksp[14] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b0_11111_11 -> { // 0x7F: STL @15
                cycles += S_IFETCH + S_DECODE;
                wksp[15] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            // === 0b1_00000_11 (I w)
            // -------------------------------------------------------------
            case 0b1_00000_11 -> { // 0x83: I_#0 A = 0, B = A, C = B
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;  // Move BReg into CReg
                BReg = AReg;  // Move AReg into BReg
                setAReg(0);   // Set AReg to 0
            }
            case 0b1_00001_11 -> { // 0x87: I_#1 A = 1, B = A, C = B
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;  // Move BReg into CReg
                BReg = AReg;  // Move AReg into BReg
                setAReg(1);   // Set AReg to 1
            }
            case 0b1_00010_11 -> { // 0x8B: I w, A = Immediate 24-bit value, B = A, C = B
                cycles += S_IFETCH + S_DECODE + memReadWordCycles();
                CReg = BReg;  // Move BReg into CReg
                BReg = AReg;  // Move AReg into BReg
                setAReg(fetchWordOperand());  // Sign-extend to 32 bits if necessary
            }
            case 0b1_00011_11 -> // 0x8F: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_00100_11 -> // 0x93: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00101_11 -> // 0x97: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00110_11 -> // 0x9B: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_00111_11 -> // 0x9F: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01000_11 -> // 0xA3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01001_11 -> // 0xA7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01010_11 -> // 0xAB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01011_11 -> // 0xAF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_01100_11 -> // 0xB3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01101_11 -> { // 0xB7: CSRW csr8
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int csr = fetchByteOperand();
                writeCSR(csr, AReg);
                AReg = BReg;
                BReg = CReg;
            }
            case 0b1_01110_11 -> // 0xBB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_01111_11 -> // 0xBF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10000_11 -> // 0xC3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10001_11 -> // 0xC7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10010_11 -> { // 0xCB: AIIP w [A = IPtr + w, B = A, C = B]
                cycles += S_IFETCH + S_DECODE + memReadWordCycles();

                CReg = BReg;
                BReg = AReg;

                int imm = normalizeWord(fetchWordOperand());   // signed word immediate
                setAReg(maskAddr(IPtr + imm));              // after immediate fetch
            }
            case 0b1_10011_11 -> // 0xCF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_10100_11 -> // 0xD3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10101_11 -> // 0xD7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10110_11 -> // 0xDB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b1_10111_11 -> // 0xDF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b1_11000_11 -> { // 0xE3: SETI [mie|=k, k=1,2,4,8 (mask)]
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int mask = fetchByteOperand() & ASYNC_INTERRUPT_MASK;
                mie |= mask;  // Set the corresponding bit(s) in the mie register
            }
            case 0b1_11001_11 -> { // 0xE7: CLRI k [mie&=k, k=1,2,4,8 (mask)]
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int mask = fetchByteOperand() & ASYNC_INTERRUPT_MASK;
                mie &= ~mask;  // Clear the corresponding bit(s) in the mie register
            }
            case 0b1_11010_11 -> { // 0xEB: (reserved)
                cycles += S_IFETCH + S_DECODE;
            }
            case 0b1_11011_11 -> { // 0xEF: CSRR csr8
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int csr = fetchByteOperand();
                CReg = BReg;
                BReg = AReg;
                setAReg(readCSR(csr));
            }
            // -------------------------------------------------------------
            case 0b1_11100_11 -> { // 0xF3: EI
                cycles += S_IFETCH + S_DECODE;
                csrSetMie(true);
            }
            case 0b1_11101_11 -> { // 0xF7: DI
                cycles += S_IFETCH + S_DECODE;
                csrSetMie(false);
            }
            case 0b1_11110_11 -> { // 0xFB: IRET
                cycles += S_IFETCH + S_DECODE;
                mret();
            }
            case 0b1_11111_11 -> { // 0xFF: HLT
                cycles += S_IFETCH + S_DECODE;
                // Implement the behavior for halting the CPU
                halted = true;  // Assuming there's a 'halted' flag in your CPU simulation
            }
            // -------------------------------------------------------------
            // More instructions...
            default -> {
                cycles += S_IFETCH + S_DECODE;
                raiseTrap(TRAP_ILLEGAL_INSN, instruction, insnPC);
                return cycles;
            }
        }

        return cycles;
    }

    // =====================================================================
    // Trap / interrupt machinery
    // =====================================================================

    // Set pending interrupt
    public void setInterruptPending(int interruptBit) {
        mip |= (interruptBit & ASYNC_INTERRUPT_MASK);
    }

    private void servicePendingInterrupts() {
        if (!csrMieEnabled()) return;

        int pending = (mip & mie) & ASYNC_INTERRUPT_MASK;
        if (pending == 0) return;

        int bit = pickHighestPriorityInterruptBit(pending);
        if (bit == 0) return;

        // acknowledge the interrupt we are taking (your chosen semantics)
        mip &= ~bit;

        final int cause = switch (bit) {
            case SOFTWARE_INTERRUPT_MASK -> IRQ_SW;
            case TIMER_INTERRUPT_MASK -> IRQ_TIMER;
            case EXTERNAL_INTERRUPT_MASK -> IRQ_EXT;
            default -> throw new IllegalStateException("Unexpected interrupt bit: " + bit);
        };

        enterVector(true, cause, 0, IPtr);
    }

    protected final void raiseTrap(int trapCause, int tval, int insnPC) {
        enterVector(false, trapCause, tval, insnPC);
    }

    /**
     * Common trap/interrupt entry (direct mode, like RISC-V mtvec MODE=0).
     * All events jump to mtvec; software reads mcause to dispatch.
     */
    private void enterVector(boolean isInterrupt, int cause, int tval, int epc) {
        halted = false; // any trap/irq wakes CPU into handler

        mtval = uword(tval); // trap specific value
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

        setIPtr(mtvec);  // direct mode: always jump to mtvec
    }

    private void mret() {
        setIPtr(mepc);

        boolean prior = (mstatus & MSTATUS_MPIE) != 0;
        if (prior) mstatus |= MSTATUS_MIE;
        else mstatus &= ~MSTATUS_MIE;

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

    /**
     * Handles ebreak instruction.
     */
    protected void handleEBreak() {
        // do nothing
    }

    /**
     * Handles ecall instruction.
     */
    protected void handleECall() {
        // do nothing
    }
}
