package io.github.robincores.r8.cpu;

public abstract class R8Core {

    // Define interrupt bitmasks
    public static final int SOFTWARE_INTERRUPT_MASK = 0x01;     // Bit 0: This has the highest priority.
    public static final int TIMER_INTERRUPT_MASK = 0x02;        // Bit 1: Second highest.
    public static final int EXTERNAL_INTERRUPT_MASK = 0x04;     // Bit 2: Third highest.
    public static final int DIV_ZERO_INTERRUPT_MASK = 0x08;     // Bit 3: Fourth highest.
    public static final int SYSTEM_CALL_INTERRUPT_MASK = 0x10;  // Bit 4: Lowest priority.

    /**
     * Machine Trap-Vector Base Address (MTVEC):
     * This register holds the base address of the trap/interrupt vector table.
     * It is used to determine the address where the CPU should jump when a trap or interrupt occurs.
     * In this case, MTVEC is set to address 0x00_0002, which is where the interrupt vector table begins.
     */
    protected int mtvec = 0x0002;

    protected final void setMTVEC(int address) {
        this.mtvec = maskAddr(address);
    }

    private static final int S_IFETCH = 1; // cycles
    private static final int S_DECODE = 1;
    private static final int S_MEM_READ = 1;
    private static final int S_MEM_WRITE = 1;

    protected final int[] wksp = new int[16]; // 16 workspace registers
    protected int AReg, BReg, CReg; // Stack-based registers
    protected int IPtr = 0; // Instruction Pointer

    protected final Memory memory;

    // CPU shape (final => JIT can constant-fold)
    protected final int ADDR_MASK;     // 0xFFFF / 0xFF_FFFF / 0xFFFF_FFFF
    protected final int WORD_BYTES;    // 2 / 3 / 4
    protected final int WORD_MASK;     // 0xFFFF / 0xFF_FFFF / 0xFFFF_FFFF
    protected final int WORD_SIGNBIT;  // 0x8000 / 0x80_0000 / 0x8000_0000

    private boolean halted = false; // Flag to track if the CPU is halted

    // -----------------------------------------------------------------------
    // Machine Interrupt-related registers
    // -----------------------------------------------------------------------

    /**
     * Machine Interrupt Enable: Controls whether interrupts are globally enabled.
     * <p>
     * Assembly Command:
     * - EI (Enable Interrupts): Sets MIE = true.
     * - DI (Disable Interrupts): Sets MIE = false.
     */
    private boolean MIE = false;
    private int mip = 0;  // Machine Interrupt Pending Register (e.g., bit 0 for timer interrupt)
    private int mie = SYSTEM_CALL_INTERRUPT_MASK | TIMER_INTERRUPT_MASK;  // Machine Interrupt Enable Register
    private int currentInterrupt = -1;  // Store the current interrupt cause

    // -----------------------------------------------------------------------

    protected R8Core(Memory memory, int addrMask, int wordBytes, int wordMask, int wordSignBit) {
        this.memory = memory;
        this.ADDR_MASK = addrMask;
        this.WORD_BYTES = wordBytes;
        this.WORD_MASK = wordMask;
        this.WORD_SIGNBIT = wordSignBit;
    }

    // --- width helpers (final) ---
    protected final int maskAddr(int a) {
        return a & ADDR_MASK;
    }

    protected final int uword(int v) {
        return v & WORD_MASK;
    }

    protected final int normalizeWord(int v) {
        v &= WORD_MASK;
        if ((v & WORD_SIGNBIT) != 0) v |= ~WORD_MASK; // sign-extend to 32-bit
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

    // --- memory access (final) ---
    protected final byte readByte(int address) {
        return memory.read(maskAddr(address));
    }

    protected final void writeByte(int address, byte value) {
        memory.write(maskAddr(address), value);
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

    // --- word ops (final, modelled by WORD_BYTES) ---
    protected final int fetchWordOperand() {
        int v = 0;
        for (int i = 0; i < WORD_BYTES; i++) {
            v |= fetchByteOperand() << (8 * i);
        }
        return v & WORD_MASK;
    }

    protected final int readWord(int address) {
        int v = 0;
        int a = maskAddr(address);
        for (int i = 0; i < WORD_BYTES; i++) {
            v |= (Byte.toUnsignedInt(readByte(a + i)) << (8 * i));
        }
        return v & WORD_MASK;
    }

    protected final void writeWord(int address, int value) {
        int a = maskAddr(address);
        int v = value & WORD_MASK;
        for (int i = 0; i < WORD_BYTES; i++) {
            writeByte(a + i, (byte) (v >>> (8 * i)));
        }
    }

    public int executeInstruction() {
        if (halted) {
            //System.out.println("CPU is halted. Execution stopped.");
            return 0;
        }

        int instruction = fetchNextInstruction();
        return decodeAndExecute(instruction);
    }

    protected int wordCycles() {
        return WORD_BYTES;
    }

    private int decodeAndExecute(int instruction) {
        int cycles = 0, tReg;

        switch (instruction) {
            // -------------------------------------------------------------
            // --- 0b00_0000_00
            // -------------------------------------------------------------
            case 0b00_0000_00 -> // 0x00: NOP
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0001_00 -> // 0x04: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0010_00 -> { // 0x08: DUP [A=A, B=A, C=B]
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
            }
            case 0b00_0011_00 -> { // 0x0C: SWAP [A=B, B=A, C=C]
                cycles += S_IFETCH + S_DECODE;
                tReg = BReg;
                BReg = AReg;
                AReg = tReg;
            }
            // -------------------------------------------------------------
            case 0b00_0100_00 -> { // 0x10: ADD [A=B+A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg + AReg);
                BReg = CReg;
            }
            case 0b00_0101_00 -> {  // 0x14: SUB [A=B-A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg - AReg);
                BReg = CReg;
            }
            case 0b00_0110_00 -> { // 0x18: MUL [A=B*A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg * AReg);
                BReg = CReg;
            }
            case 0b00_0111_00 -> { // 0x1C: DIV [A=B/A, B=c (A != 0)]
                cycles += S_IFETCH + S_DECODE;
                if (AReg == 0) {
                    setInterruptPending(DIV_ZERO_INTERRUPT_MASK);
                } else {
                    setAReg(BReg / AReg);
                }
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b00_1000_00 -> { // 0x20: AND [A=B&A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg & AReg);
                BReg = CReg;
            }
            case 0b00_1001_00 -> { // 0x24: OR [A=B|A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg | AReg);
                BReg = CReg;
            }
            case 0b00_1010_00 -> { // 0x28: XOR [A=B^A, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg(BReg ^ AReg);
                BReg = CReg;
            }
            case 0b00_1011_00 -> { // 0x2C: REM [A=B%A, B=C]
                cycles += S_IFETCH + S_DECODE;
                if (AReg == 0) {
                    setInterruptPending(DIV_ZERO_INTERRUPT_MASK);
                } else {
                    setAReg(BReg % AReg);
                }
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b00_1100_00 -> { // 0x30: SLL 1 [A = A << 1]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 1); // Shift left by 1 and mask to 24 bits
            }
            case 0b00_1101_00 -> { // 0x34: SLL 2 [A = A << 2]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 2); // Shift left by 2 and mask to 24 bits
            }
            case 0b00_1110_00 -> { // 0x38: SLL 3 [A = A << 3]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 3); // Shift left by 3 and mask to 24 bits
            }
            case 0b00_1111_00 -> { // 0x3C: SLL 4 [A = A << 4]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg << 4); // Shift left by 4
            }
            // -------------------------------------------------------------
            // --- 0b01_0000_00
            // -------------------------------------------------------------
            case 0b01_0000_00 -> { // 0x40: INC [A=A+1]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg + 1);  // Increment
            }
            case 0b01_0001_00 -> {  // 0x44: DEC [A=A-1]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg - 1);  // Decrement
            }
            case 0b01_0010_00 -> { // 0x48: NEG [A=-A]
                cycles += S_IFETCH + S_DECODE;
                setAReg(-AReg);  // Negate
            }
            case 0b01_0011_00 -> { // 0x4C: INV [A=~A]
                cycles += S_IFETCH + S_DECODE;
                setAReg(~AReg);
            }
            // -------------------------------------------------------------
            case 0b01_0100_00 -> // 0x50: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0101_00 -> // 0x54: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0110_00 -> // 0x58: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0111_00 -> { // 0x5C: I2B [A=(byte)A]
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg & 0xFF); // Mask to keep only the lower 8 bits (convert to byte)
            }
            // -------------------------------------------------------------
            case 0b01_1000_00 -> { // 0x60: SLT [A=(B<A)?1:0, B=C]
                cycles += S_IFETCH + S_DECODE;
                setAReg((BReg < AReg) ? 1 : 0);
                BReg = CReg;
            }
            case 0b01_1001_00 -> { // 0x64: SLTU [A=(B<A)?1:0, B=C (unsigned)]
                cycles += S_IFETCH + S_DECODE;
                setAReg((Integer.compareUnsigned(BReg, AReg) < 0) ? 1 : 0); // Unsigned comparison
                BReg = CReg;
            }
            case 0b01_1010_00 -> // 0x68: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1011_00 -> // 0x6C: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b01_1100_00 -> // 0x70: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1101_00 -> // 0x74: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1110_00 -> { // 0x78: DROP1 [A=B,B=C]
                cycles += S_IFETCH + S_DECODE;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1111_00 -> { // 0x7C: DROP2 [A=C]
                cycles += S_IFETCH + S_DECODE;
                AReg = BReg = CReg;
            }
            // -------------------------------------------------------------
            // === 0b10_0000_00 (LD)
            // -------------------------------------------------------------
            case 0b10_0000_00 -> // 0x80: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0001_00 -> // 0x84: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0010_00 -> { // 0x88: LD [A=[A]]
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                setAReg(readWord(AReg));
            }
            case 0b10_0011_00 -> // 0x8C: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_0100_00 -> // 0x90: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0101_00 -> // 0x94: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0110_00 -> // 0x98: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0111_00 -> // 0x9C: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1000_00 -> // 0xA0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1001_00 -> // 0xA4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1010_00 -> // 0xA8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1011_00 -> // 0xAC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1100_00 -> { // 0xB0: POP A=[SP], B=A (before read), C=B (SP=SP+3)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                // Preserve the old values of AReg and BReg
                CReg = BReg;          // Move BReg into CReg
                BReg = AReg;          // Move the old AReg into BReg before reading

                // Read the word value from memory at the address pointed to by SP (wksp[15])
                setAReg(readWord(wksp[15]));  // Load and sign-extend

                // Increment SP to point to the next location
                incSP();
            }
            case 0b10_1101_00 -> // 0xB4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1110_00 -> // 0xB8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1111_00 -> // 0xBC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0000_00 -> // 0xC0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0001_00 -> // 0xC4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0010_00 -> // 0xC8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0011_00 -> // 0xCC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0100_00 -> // 0xD0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0101_00 -> // 0xD4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0110_00 -> // 0xD8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0111_00 -> // 0xDC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1000_00 -> // 0xE0: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1001_00 -> // 0xE4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1010_00 -> // 0xE8: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1011_00 -> // 0xEC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1100_00 -> { // 0xF0: PUSH [[SP]=A, A=B, B=C (SP=SP-3)]
                cycles += S_IFETCH + S_DECODE + S_MEM_WRITE;  // FIXME Three memory writes for the 24-bit value

                // Calculate the new stack pointer address and write AReg's value
                decSP();  // Decrease SP and ensure it's within cpu width range
                writeWord(wksp[15], AReg);  // Write AReg's 24-bit value to memory at SP

                // Move the values from BReg and CReg
                AReg = BReg;  // AReg takes the value of BReg
                BReg = CReg;  // BReg takes the value of CReg
            }
            case 0b11_1101_00 -> // 0xF4: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1110_00 -> { // 0xF8: ST [[B]=A, A=B, B=C]
                cycles += S_IFETCH + S_DECODE + S_MEM_WRITE;  // One memory write operation that writes 3 bytes

                // Write the 24-bit value of AReg into memory at the address in BReg
                writeWord(BReg, AReg);

                // Move the values from BReg and CReg
                AReg = BReg;  // AReg takes the value of BReg
                BReg = CReg;  // BReg takes the value of CReg
            }
            case 0b11_1111_00 -> // 0xFC: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b00_0000_01 (LB)
            // -------------------------------------------------------------
            case 0b00_0000_01 -> // 0x01: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0001_01 -> // 0x05: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0010_01 -> { // 0x09: LB A=[A] (Load Byte and Sign-Extend)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                int addr = AReg;
                setAReg((byte) readByte(addr));   // sign-extend 8-bit -> int, then normalize
            }
            case 0b00_0011_01 -> // 0x0D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b00_0100_01 -> // 0x11: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0101_01 -> // 0x15: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0110_01 -> // 0x19: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0111_01 -> // 0x1D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b00_1000_01 -> // 0x21: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1001_01 -> // 0x25: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1010_01 -> // 0x29: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1011_01 -> // 0x2D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b00_1100_01 -> // 0x31: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1101_01 -> // 0x35: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1110_01 -> // 0x39: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1111_01 -> // 0x3D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b01_0000_01 -> // 0x41: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0001_01 -> // 0x45: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0010_01 -> // 0x49: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0011_01 -> // 0x4D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b01_0100_01 -> // 0x51: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0101_01 -> // 0x55: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0110_01 -> // 0x59: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0111_01 -> // 0x5D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b01_1000_01 -> // 0x61: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1001_01 -> // 0x65: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1010_01 -> // 0x69: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1011_01 -> // 0x6D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b01_1100_01 -> // 0x71: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1101_01 -> // 0x75: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1110_01 -> { // 0x79: SB [B] = A (signed 8-bit), A = B, B = C
                cycles += S_IFETCH + S_DECODE + S_MEM_WRITE;

                // Write only the lower 8 bits of AReg (signed byte) into memory at the address in BReg
                writeByte(BReg, (byte) AReg);

                // Move the values from BReg and CReg
                AReg = BReg;  // AReg takes the value of BReg
                BReg = CReg;  // BReg takes the value of CReg
            }
            case 0b01_1111_01 -> // 0x7D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b10_0000_01 (LU)
            // -------------------------------------------------------------
            case 0b10_0000_01 -> // 0x81: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0001_01 -> // 0x55: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0010_01 -> { // 0x89: LU [A=[A] (Load Unsigned Byte)]
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                int addr = AReg;
                setAReg(Byte.toUnsignedInt(readByte(addr)));  // 0..255
            }
            case 0b10_0011_01 -> // 0x8D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_0100_01 -> // 0x91: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0101_01 -> // 0x95: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0110_01 -> // 0x99: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0111_01 -> // 0x9D: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1000_01 -> // 0xA1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1001_01 -> // 0xA5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1010_01 -> // 0xA9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1011_01 -> // 0xAD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1100_01 -> // 0xB1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1101_01 -> // 0xB5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1110_01 -> // 0xB9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1111_01 -> // 0xBD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0000_01 -> // 0xC1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0001_01 -> // 0xC5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0010_01 -> // 0xC9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0011_01 -> // 0xCD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0100_01 -> // 0xD1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0101_01 -> // 0xD5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0110_01 -> // 0xD9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0111_01 -> // 0xDD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1000_01 -> // 0xE1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1001_01 -> // 0xE5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1010_01 -> // 0xE9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1011_01 -> // 0xED: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1100_01 -> // 0xF1: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1101_01 -> // 0xF5: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1110_01 -> // 0xF9: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1111_01 -> // 0xFD: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b00_0000_10 (B k)
            // -------------------------------------------------------------
            case 0b00_0000_10 -> // 0x02: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0001_10 -> // 0x06: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0010_10 -> { // 0x0A: B k [A=imm8,B=A,C=B] (Load Immediate Signed Byte and Sign-Extend)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                // Preserve the old values of AReg and BReg
                CReg = BReg;              // Move BReg into CReg
                BReg = AReg;              // Move the old AReg into BReg before reading

                // Read the signed byte from memory and sign-extend it into AReg
                AReg = signExtend8to32(fetchByteOperand());
            }
            case 0b00_0011_10 -> // 0x0E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b00_0100_10 -> // 0x12: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0101_10 -> // 0x16: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0110_10 -> // 0x1A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_0111_10 -> // 0x1E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b00_1000_10 -> // 0x22: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1001_10 -> // 0x26: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1010_10 -> // 0x2A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b00_1011_10 -> // 0x2E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b00_1100_10 -> { // 0x32: SRL 1 (A = A >>> 1)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 1);
            }
            case 0b00_1101_10 -> { // 0x36: SRL 2 (A = A >>> 2)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 2);
            }
            case 0b00_1110_10 -> { // 0x3A: SRL 3 (A = A >>> 3)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 3);
            }
            case 0b00_1111_10 -> { // 0x3E: SRL 4 (A = A >>> 4)
                cycles += S_IFETCH + S_DECODE;
                setAReg(uword(AReg) >>> 4);
            }
            // -------------------------------------------------------------
            case 0b01_0000_10 -> { // 0x42: BEQ k (IPtr = IPtr + k, B == A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                if (BReg == AReg) {
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                }
                AReg = CReg; // A takes value of C
            }
            case 0b01_0001_10 -> { // 0x46: BNE k (IPtr = IPtr + k, B != A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                if (BReg != AReg) {
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                }
                AReg = CReg; // A takes value of C
            }
            case 0b01_0010_10 -> // 0x4A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_0011_10 -> // 0x4E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b01_0100_10 -> { // 0x52: BLT k (IPtr = IPtr + k, B < A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                if (BReg < AReg) {
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                }
                AReg = CReg; // A takes value of C
            }
            case 0b01_0101_10 -> { // 0x56: BLTU k (IPtr = IPtr + k, B < A (unsigned), A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                if (Integer.compareUnsigned(BReg, AReg) < 0) {
                    setIPtr(IPtr + offset);// Apply offset to instruction pointers
                }
                AReg = CReg; // A takes value of C
            }
            case 0b01_0110_10 -> { // 0x5A: BGE k (IPtr = IPtr + k, B >= A, A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                if (BReg >= AReg) {
                    setIPtr(IPtr + offset);// Apply offset to instruction pointers
                }
                AReg = CReg; // A takes value of C
            }
            case 0b01_0111_10 -> { // 0x5E: BGEU k (IPtr = IPtr + k, B >= A (unsigned), A = C)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                if (Integer.compareUnsigned(BReg, AReg) >= 0) {
                    setIPtr(IPtr + offset);// Apply offset to instruction pointer
                }
                AReg = CReg; // A takes value of C
            }
            // -------------------------------------------------------------
            case 0b01_1000_10 -> { // 0x62: J k (IPtr = IPtr + k)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                setIPtr(IPtr + offset);// Apply offset to instruction pointer
            }
            case 0b01_1001_10 -> { // 0x66: JAL k (IPtr = IPtr + k, A = PC + 1)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int offset = signExtend8to32(fetchByteOperand()); // signed byte for branch offset
                AReg = IPtr; // Save the return address (IPtr + 1) in AReg
                setIPtr(IPtr + offset);// Apply offset to instruction pointer
            }
            case 0b01_1010_10 -> { // 0x6A: JR (PC = A, A = B, B = C)
                cycles += S_IFETCH + S_DECODE;
                setIPtr(AReg); // Jump to address in AReg
                AReg = BReg; // A takes value of B
                BReg = CReg; // B takes value of C
            }
            case 0b01_1011_10 -> { // 0x6E: JALR (PC = A, A = PC + 1)
                cycles += S_IFETCH + S_DECODE;
                tReg = IPtr; // Temporarily store the current PC
                setIPtr(AReg); // Jump to address in AReg
                AReg = tReg; // A takes the return address (PC + 1)
            }
            // -------------------------------------------------------------
            case 0b01_1100_10 -> // 0x72: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1101_10 -> // 0x76: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b01_1110_10 -> { // 0x7A: ECALL: Environment/System Call
                // Increment the cycle count for instruction fetch and decode
                cycles += S_IFETCH + S_DECODE;
                // TODO setInterruptPending(SYSTEM_CALL_INTERRUPT_MASK);
                handleECall();
            }
            case 0b01_1111_10 -> // 0x7E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b10_0000_10 (U k)
            // -------------------------------------------------------------
            case 0b10_0000_10 -> // 0x82: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0001_10 -> // 0x86: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0010_10 -> { // 8x8A: U A=[A] (Load Immediate Unsigned Byte)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;

                // Preserve the old values of AReg and BReg
                CReg = BReg;              // Move BReg into CReg
                BReg = AReg;              // Move the old AReg into BReg before reading

                // Load the unsigned byte and zero-extend into AReg
                AReg = fetchByteOperand();  // Load byte, mask to ensure it's unsigned
            }
            case 0b10_0011_10 -> // 0x8E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_0100_10 -> // 0x92: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0101_10 -> // 0x96: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0110_10 -> // 0x9A: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0111_10 -> // 0x9E: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1000_10 -> // 0xA2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1001_10 -> // 0xA6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1010_10 -> // 0xAA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1011_10 -> // 0xAE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1100_10 -> { // 0xB2: SRA 1 A = A >> 1
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 1);
            }
            case 0b10_1101_10 -> { // 0xB6: SRA 2 A = A >> 2
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 2);
            }
            case 0b10_1110_10 -> { // 0xBA: SRA 3 A = A >> 3
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 3);
            }
            case 0b10_1111_10 -> { // 0xBE: SRA 4 A = A >> 4
                cycles += S_IFETCH + S_DECODE;
                setAReg(AReg >> 4);
            }
            // -------------------------------------------------------------
            case 0b11_0000_10 -> // 0xC2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0001_10 -> // 0xC6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0010_10 -> // 0xCA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0011_10 -> // 0xCE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0100_10 -> // 0xD2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0101_10 -> // 0xD6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0110_10 -> // 0xDA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0111_10 -> // 0xDE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1000_10 -> // 0xE2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1001_10 -> // 0xE6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1010_10 -> // 0xEA: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1011_10 -> // 0xEE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1100_10 -> // 0xF2: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1101_10 -> // 0xF6: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1110_10 -> { // 0xFA: EBREAK: Breakpoint for debugging or halting the CPU
                // Increment the cycle count for instruction fetch and decode
                cycles += S_IFETCH + S_DECODE;

                // Call the handleEBreak function to handle the breakpoint or halt event
                // This function should perform the following tasks:
                // 1. Stop or halt the CPU execution.
                //    - Typically used for debugging purposes, allowing the system or debugger to take control.
                // 2. Optionally, signal the halt or breakpoint to an external debugger.
                //    - Depending on the system, this might interact with debugging hardware or software.
                // 3. If the EBREAK is meant to halt execution, ensure the CPU enters a halted state where it no longer executes instructions until further intervention.
                handleEBreak();
            }
            case 0b11_1111_10 -> // 0xFE: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            // === 0b00_0000_11 (LDL)
            // -------------------------------------------------------------
            case 0b00_0000_11 -> { // 0x03: LDL @0
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[0];
            }
            case 0b00_0001_11 -> { // 0x07: LDL @1
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[1];
            }
            case 0b00_0010_11 -> { // 0x0B: LDL @2
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[2];
            }
            case 0b00_0011_11 -> { // 0x0F: LDL @3
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[3];
            }
            // -------------------------------------------------------------
            case 0b00_0100_11 -> { // 0x13: LDL @4
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[4];
            }
            case 0b00_0101_11 -> { // 0x17: LDL @5
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[5];
            }
            case 0b00_0110_11 -> { // 0x1B: LDL @6
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[6];
            }
            case 0b00_0111_11 -> { // 0x1F: LDL @7
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[7];
            }
            // -------------------------------------------------------------
            case 0b00_1000_11 -> { // 0x23: LDL @8
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[8];
            }
            case 0b00_1001_11 -> { // 0x27: LDL @9
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[9];
            }
            case 0b00_1010_11 -> { // 0x2B: LDL @10
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[10];
            }
            case 0b00_1011_11 -> { // 0x2F: LDL @11
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[11];
            }
            // -------------------------------------------------------------
            case 0b00_1100_11 -> { // 0x33: LDL @12
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[12];
            }
            case 0b00_1101_11 -> { // 0x37: LDL @13
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[13];
            }
            case 0b00_1110_11 -> { // 0x3B: LDL @14
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[14];
            }
            case 0b00_1111_11 -> { // 0x3F: LDL @15
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;
                BReg = AReg;
                AReg = wksp[15];
            }
            // -------------------------------------------------------------
            case 0b01_0000_11 -> { // 0x43: STL @0
                cycles += S_IFETCH + S_DECODE;
                wksp[0] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_0001_11 -> { // 0x47: STL @1
                cycles += S_IFETCH + S_DECODE;
                wksp[1] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_0010_11 -> { // 0x4B: STL @2
                cycles += S_IFETCH + S_DECODE;
                wksp[2] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_0011_11 -> { // 0x4F: STL @3
                cycles += S_IFETCH + S_DECODE;
                wksp[3] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b01_0100_11 -> { // 0x53: STL @4
                cycles += S_IFETCH + S_DECODE;
                wksp[4] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_0101_11 -> { // 0x57: STL @5
                cycles += S_IFETCH + S_DECODE;
                wksp[5] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_0110_11 -> { // 0x5B: STL @6
                cycles += S_IFETCH + S_DECODE;
                wksp[6] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_0111_11 -> { // 0x5F: STL @7
                cycles += S_IFETCH + S_DECODE;
                wksp[7] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b01_1000_11 -> { // 0x63: STL @8
                cycles += S_IFETCH + S_DECODE;
                wksp[8] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1001_11 -> { // 0x67: STL @9
                cycles += S_IFETCH + S_DECODE;
                wksp[9] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1010_11 -> { // 0x6B: STL @10
                cycles += S_IFETCH + S_DECODE;
                wksp[10] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1011_11 -> { // 0x6F: STL @11
                cycles += S_IFETCH + S_DECODE;
                wksp[11] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            case 0b01_1100_11 -> { // 0x73: STL @12
                cycles += S_IFETCH + S_DECODE;
                wksp[12] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1101_11 -> { // 0x77: STL @13
                cycles += S_IFETCH + S_DECODE;
                wksp[13] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1110_11 -> { // 0x7B: STL @14
                cycles += S_IFETCH + S_DECODE;
                wksp[14] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            case 0b01_1111_11 -> { // 0x7F: STL @15
                cycles += S_IFETCH + S_DECODE;
                wksp[15] = AReg;
                AReg = BReg;
                BReg = CReg;
            }
            // -------------------------------------------------------------
            // === 0b10_0000_11 (I w)
            // -------------------------------------------------------------
            case 0b10_0000_11 -> { // 0x83: I_#0 A = 0, B = A, C = B
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;  // Move BReg into CReg
                BReg = AReg;  // Move AReg into BReg
                setAReg(0);   // Set AReg to 0
            }
            case 0b10_0001_11 -> { // 0x87: I_#1 A = 1, B = A, C = B
                cycles += S_IFETCH + S_DECODE;
                CReg = BReg;  // Move BReg into CReg
                BReg = AReg;  // Move AReg into BReg
                setAReg(1);   // Set AReg to 1
            }
            case 0b10_0010_11 -> { // 0x8B: I w, A = Immediate 24-bit value, B = A, C = B
                cycles += S_IFETCH + S_DECODE + S_MEM_READ + S_MEM_READ + S_MEM_READ;
                CReg = BReg;  // Move BReg into CReg
                BReg = AReg;  // Move AReg into BReg
                setAReg(fetchWordOperand());  // Sign-extend to 32 bits if necessary
            }
            case 0b10_0011_11 -> // 0x8F: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_0100_11 -> // 0x93: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0101_11 -> // 0x97: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0110_11 -> // 0x9B: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_0111_11 -> // 0x9F: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1000_11 -> // 0xA3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1001_11 -> // 0xA7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1010_11 -> // 0xAB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1011_11 -> // 0xAF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b10_1100_11 -> // 0xB3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1101_11 -> // 0xB7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1110_11 -> // 0xBB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b10_1111_11 -> // 0xBF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0000_11 -> // 0xC3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0001_11 -> // 0xC7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0010_11 -> { // 0xCB: AIIP w, A = IPtr + w, B = A, C = B
                cycles += S_IFETCH + S_DECODE + S_MEM_READ * 3;  // Three memory reads for 24-bit value

                // Copy AReg to BReg and CReg
                CReg = BReg;
                BReg = AReg;

                int base = IPtr; // decide if you want this BEFORE or AFTER reading immediate
                int immediate = normalizeWord(fetchWordOperand());    // Sign-extend if necessary

                // Add the immediate value to IPtr and store the result in AReg
                setAReg(maskAddr(IPtr + immediate)); // XXX
            }
            case 0b11_0011_11 -> // 0xCF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_0100_11 -> // 0xD3: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0101_11 -> // 0xD7: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0110_11 -> // 0xDB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_0111_11 -> // 0xDF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1000_11 -> { // 0xE3: SETI mie|=k, k=1,2,4,8 (mask)
                cycles += S_IFETCH + S_DECODE + S_MEM_READ;
                int mask = fetchByteOperand() & 0x07;  // Fetch the interrupt mask from the next byte
                mie |= mask;  // Set the corresponding bit(s) in the mie register
            }
            case 0b11_1001_11 -> { // 0xE7: CLRI mie&=k, k=1,2,4,8 (mask)
                cycles += S_IFETCH + S_DECODE;
                int mask = fetchByteOperand() & 0x07;  // Fetch the interrupt mask from the next byte
                mie &= ~mask;  // Clear the corresponding bit(s) in the mie register
            }
            case 0b11_1010_11 -> // 0xEB: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            case 0b11_1011_11 -> // 0xEF: (reserved)
                    cycles += S_IFETCH + S_DECODE;
            // -------------------------------------------------------------
            case 0b11_1100_11 -> { // 0xF3: EI
                cycles += S_IFETCH + S_DECODE;
                MIE = true;
            }
            case 0b11_1101_11 -> { // 0xF7: DI
                cycles += S_IFETCH + S_DECODE;
                MIE = false;
            }
            case 0b11_1110_11 -> { // 0xFB: IRET
                cycles += S_IFETCH + S_DECODE;

                // Acknowledge interrupt (clear pending flag)
                if (currentInterrupt != -1) {
                    int _currentInterrupt = currentInterrupt;
                    acknowledgeInterrupt(currentInterrupt);
                    currentInterrupt = -1;  // Clear interrupt

                    if (_currentInterrupt != SOFTWARE_INTERRUPT_MASK) {
                        // Restore state (e.g., instruction pointer and registers)
                        setIPtr(wksp[14]);
                        setAReg(wksp[13]);
                        BReg = normalizeWord(wksp[12]);
                        CReg = normalizeWord(wksp[11]);
                    }

                    MIE = true;  // Re-enable interrupts
                }
            }
            case 0b11_1111_11 -> { // 0xFF: HLT
                cycles += S_IFETCH + S_DECODE;
                // Implement the behavior for halting the CPU
                halted = true;  // Assuming there's a 'halted' flag in your CPU simulation
            }
            // -------------------------------------------------------------
            // More instructions...
            default -> throw new IllegalArgumentException("Unknown instruction: " + instruction);
        }

        // After executing an instruction, check for pending interrupts
        if (MIE && (mip & mie) != 0) {
            System.out.println("---" + mip);
            handleInterrupt();
        }

        return cycles;
    }

    // Handle Interrupt (Disable interrupts, save state, execute interrupt handler)
    private void handleInterrupt() {
        MIE = false;  // Disable interrupts

        currentInterrupt = prioritizeInterrupt();

        if (currentInterrupt == SOFTWARE_INTERRUPT_MASK) {
            handleEBreak();  // Handle breakpoints separately
        } else {
            // Save the CPU state (registers, instruction pointer)
            wksp[11] = CReg;
            wksp[12] = BReg;
            wksp[13] = AReg;
            wksp[14] = IPtr;

            // Handle other interrupts (system calls, timer, external, etc.)
            if (currentInterrupt == SYSTEM_CALL_INTERRUPT_MASK) {
                handleECall();
            }

            // Jump to the interrupt handler:
            setIPtr(mtvec);
        }
    }

    // Function to prioritize interrupts based on mip
    private int prioritizeInterrupt() {
        int pending = mip & mie;

        // Check the highest priority interrupt first (Software Interrupt)
        if ((pending & SOFTWARE_INTERRUPT_MASK) != 0) return SOFTWARE_INTERRUPT_MASK;
        if ((pending & TIMER_INTERRUPT_MASK) != 0) return TIMER_INTERRUPT_MASK;
        if ((pending & EXTERNAL_INTERRUPT_MASK) != 0) return EXTERNAL_INTERRUPT_MASK;
        if ((pending & DIV_ZERO_INTERRUPT_MASK) != 0) return DIV_ZERO_INTERRUPT_MASK;
        if ((pending & SYSTEM_CALL_INTERRUPT_MASK) != 0) return SYSTEM_CALL_INTERRUPT_MASK;

        return -1;  // No interrupt pending
    }

    // Set pending interrupt
    public void setInterruptPending(int interruptBit) {
        mip |= interruptBit;
    }

    // Acknowledge interrupt (clear pending flag)
    private void acknowledgeInterrupt(int interruptBit) {
        mip &= ~interruptBit;
    }

    // Helper method for 8-bit sign extension
    int signExtend8to32(int value) {
        return (value & 0x80) != 0 ? value | 0xFFFFFF00 : value & 0xFF;
    }

//    // Helper method for word sign extension
//    protected int signExtendWordTo32(int value) {
//        return normalizeWord(value);
//    }

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
