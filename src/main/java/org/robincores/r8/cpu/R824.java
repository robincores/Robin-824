package org.robincores.r8.cpu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class R824 {

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
  private static final int MTVEC = 0x00_0002;

  private static final int S_IFETCH = 1; // cycles
  private static final int S_DECODE = 1;
  private static final int S_MEM_READ = 1;
  private static final int S_MEM_WRITE = 1;

  private final int[] wksp = new int[16]; // 16 workspace registers
  private int AReg, BReg, CReg; // Stack-based registers
  private int IPtr = 0; // Instruction Pointer
  private final Memory memory;

  private boolean halted = false; // Flag to track if the CPU is halted

  // -----------------------------------------------------------------------
  // Machine Interrupt-related registers
  // -----------------------------------------------------------------------

  /**
   * Machine Interrupt Enable: Controls whether interrupts are globally enabled.
   * <p>
   * Assembly Command:
   *  - EI (Enable Interrupts): Sets MIE = true.
   *  - DI (Disable Interrupts): Sets MIE = false.
   */
  private boolean MIE = false;
  private int mip = 0;  // Machine Interrupt Pending Register (e.g., bit 0 for timer interrupt)
  private int mie = SYSTEM_CALL_INTERRUPT_MASK | TIMER_INTERRUPT_MASK;  // Machine Interrupt Enable Register
  private int currentInterrupt;  // Store the current interrupt cause

  // -----------------------------------------------------------------------

  public R824(Memory memory) {
    this.memory = memory;
  }

  public int executeInstruction() {
    if (halted) {
      //System.out.println("CPU is halted. Execution stopped.");
      return 0;
    }

    int instruction = fetchNextInstruction();
    return decodeAndExecute(instruction);
  }

  /**
   * Fetches the next instruction from memory using the Instruction Pointer (IPtr).
   * The R824 system uses a 24-bit address space, so the IPtr is masked at the end
   * to ensure it stays within that range.
   *
   * @return The next instruction byte, as an unsigned integer between 0 and 255.
   */
  private int fetchNextInstruction() {
    // Fetch the next byte from memory at the current IPtr
    int instruction = memory.read(IPtr) & 0xFF;  // Fetch and mask the instruction byte

    // Increment IPtr and mask to ensure it stays within 24-bit range
    IPtr = (IPtr + 1) & 0xFF_FFFF;

    return instruction;
  }

  /**
   * Fetches the 8-bit operand from memory using the Instruction Pointer (IPtr).
   * This method reads a single byte from memory, increments IPtr, and ensures that
   * the IPtr remains within the 24-bit address space.
   *
   * @return The next 8-bit operand, as an unsigned integer between 0 and 255.
   */
  private int fetch8BitOperand() {
    // Fetch the next byte from memory at the current IPtr
    int operand = memory.read(IPtr) & 0xFF;  // Fetch and mask the operand byte

    // Increment IPtr and mask to ensure it stays within 24-bit range
    IPtr = (IPtr + 1) & 0xFF_FFFF;

    return operand;
  }

  /**
   * Fetches the next 24-bit operand from memory using the Instruction Pointer (IPtr).
   * This method reads three consecutive bytes from memory, constructs a 24-bit value,
   * increments IPtr accordingly, and ensures that the IPtr remains within the 24-bit range.
   *
   * @return The next 24-bit operand, as an unsigned integer.
   */
  private int fetch24BitOperand() {
    // Fetch three consecutive bytes from memory and combine them into a 24-bit value
    int operand = memory.read(IPtr) & 0xFF;                 // Fetch the least significant byte
    operand |= (memory.read((IPtr + 1) & 0xFF_FFFF) & 0xFF) << 8;  // Fetch the middle byte
    operand |= (memory.read((IPtr + 2) & 0xFF_FFFF) & 0xFF) << 16; // Fetch the most significant byte

    // Increment IPtr by 3 and mask to ensure it stays within 24-bit range
    IPtr = (IPtr + 3) & 0xFF_FFFF;

    return operand;
  }

  /**
   * Reads a 24-bit value from memory at the specified address.
   * This method requires the address is aligned within the 24-bit range (0xFF_FFFF)
   * and reads the value in little-endian format (the least significant byte first).
   *
   * @param alignedAddress The memory address from which the 24-bit value should be read.
   * @return The 24-bit value read from the specified address, with the lower 24 bits populated.
   */
  private int read24BitValueFromMemory(int alignedAddress) {

    // Read the 24-bit value in little-endian order (LSB first).
    int lowerByte = Byte.toUnsignedInt(memory.read(alignedAddress));      // Read least significant byte
    int middleByte = Byte.toUnsignedInt(memory.read((alignedAddress + 1) & 0xFF_FFFF)); // Read middle byte
    int upperByte = Byte.toUnsignedInt(memory.read((alignedAddress + 2) & 0xFF_FFFF));  // Read most significant byte

    // Combine the bytes into a single 24-bit integer value.
    return (upperByte << 16) | (middleByte << 8) | lowerByte;
  }

  /**
   * Writes a 24-bit value to memory at the specified address.
   * This method requires the address is aligned within the 24-bit range (0xFF_FFFF)
   * and writes the value in little-endian format (the least significant byte first).
   *
   * @param alignedAddress The memory address where the 24-bit value should be written.
   * @param value   The 24-bit value to be written, where only the lower 24 bits are used.
   */
  private void write24BitValueToMemory(int alignedAddress, int value) {

    // Write the 24-bit value in little-endian order (LSB first).
    byte[] bytes = {
        (byte) (value & 0xFF),        // Least significant byte
        (byte) ((value >> 8) & 0xFF), // Middle byte
        (byte) ((value >> 16) & 0xFF) // Most significant byte
    };

    // Perform memory writes for the 3 bytes at consecutive addresses.
    memory.write(alignedAddress, bytes[0]);      // Write the least significant byte
    memory.write((alignedAddress + 1) & 0xFF_FFFF, bytes[1]);  // Write the middle byte
    memory.write((alignedAddress + 2) & 0xFF_FFFF, bytes[2]);  // Write the most significant byte
  }

  private int decodeAndExecute(int instruction) {
    int cycles = 0, tReg;

    switch (instruction) {

      // === 0b00_0000_00 (NOP)

      case 0b00_0000_00 -> // NOP
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0001_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0010_00 -> { // DUP
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
      }
      case 0b00_0011_00 -> { // SWAP
        cycles += S_IFETCH + S_DECODE;
        tReg = BReg;
        BReg = AReg;
        AReg = tReg;
      }

      case 0b00_0100_00 -> { // ADD
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg + AReg) & 0xFF_FFFF;
        AReg = signExtend24to32(AReg);     // Sign-extend if necessary
        BReg = CReg;
      }
      case 0b00_0101_00 -> {  // SUB
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg - AReg) & 0xFF_FFFF;
        AReg = signExtend24to32(AReg);     // Sign-extend if necessary
        BReg = CReg;
      }
      case 0b00_0110_00 -> { // MUL
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg * AReg) & 0xFF_FFFF;
        AReg = signExtend24to32(AReg);     // Sign-extend if necessary
        BReg = CReg;
      }
      case 0b00_0111_00 -> { // DIV
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg / AReg) & 0xFF_FFFF; // XXX DIVISION BY ZERO
        AReg = signExtend24to32(AReg);     // Sign-extend if necessary
        BReg = CReg;
      }

      case 0b00_1000_00 -> { // AND
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg & AReg) & 0xFF_FFFF;
        BReg = CReg;
      }
      case 0b00_1001_00 -> { // OR
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg | AReg) & 0xFF_FFFF;
        BReg = CReg;
      }
      case 0b00_1010_00 -> { // XOR
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg ^ AReg) & 0xFF_FFFF;
        BReg = CReg;
      }
      case 0b00_1011_00 -> { // REM
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg % AReg) & 0xFF_FFFF; // XXX DIVISION BY ZERO
        AReg = signExtend24to32(AReg);     // Sign-extend if necessary
        BReg = CReg;
      }

      case 0b00_1100_00 -> { // SLL 1 (A = A << 1)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg << 1) & 0xFFFFFF; // Shift left by 1 and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b00_1101_00 -> { // SLL 2 (A = A << 2)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg << 2) & 0xFFFFFF; // Shift left by 2 and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b00_1110_00 -> { // SLL 3 (A = A << 3)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg << 3) & 0xFFFFFF; // Shift left by 3 and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b00_1111_00 -> { // SLL 4 (A = A << 4)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg << 4) & 0xFFFFFF; // Shift left by 4 and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }

      // --- 0b01_0000_00

      case 0b01_0000_00 -> { // INC
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg + 1) & 0xFF_FFFF;  // Increment and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend if necessary
      }
      case 0b01_0001_00 -> {  // DEC
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg - 1) & 0xFF_FFFF;  // Decrement and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend if necessary
      }
      case 0b01_0010_00 -> { // NEG
        cycles += S_IFETCH + S_DECODE;
        AReg = (-AReg) & 0xFF_FFFF;  // Negate and mask to 24 bits
        AReg = signExtend24to32(AReg);  // Sign-extend if necessary
      }
      case 0b01_0011_00 -> { // INV
        cycles += S_IFETCH + S_DECODE;
        AReg = ~AReg & 0xFF_FFFF; // Perform bitwise NOT on AReg, keeping it within 24 bits
        AReg = signExtend24to32(AReg);     // Sign-extend if necessary
      }

      case 0b01_0100_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0101_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0110_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0111_00 -> { // I2B (int to byte)
        cycles += S_IFETCH + S_DECODE;
        AReg = AReg & 0xFF;               // Mask to keep only the lower 8 bits (convert to byte)
        AReg = signExtend8to32(AReg);
      }

      case 0b01_1000_00 -> { // SLT
        cycles += S_IFETCH + S_DECODE;
        AReg = (BReg < AReg) ? 1 : 0;
        BReg = CReg;
      }
      case 0b01_1001_00 -> { // SLTU
        cycles += S_IFETCH + S_DECODE;
        AReg = (Integer.compareUnsigned(BReg, AReg) < 0) ? 1 : 0; // Unsigned comparison
        BReg = CReg;
      }
      case 0b01_1010_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1011_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b01_1100_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1101_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1110_00 -> { // POP1
        cycles += S_IFETCH + S_DECODE;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1111_00 -> { // POP2
        cycles += S_IFETCH + S_DECODE;
        AReg = BReg = CReg;
      }

      // === 0b10_0000_00 (LD)

      case 0b10_0000_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0001_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0010_00 -> { // LD A=[A] (Load 24-bit value and sign-extend to 32-bit)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ * 3; // Combine multiple memory read cycles
        // Read the 24-bit value from memory at AReg and sign-extend it to 32 bits
        AReg = signExtend24to32(read24BitValueFromMemory(AReg & 0xFF_FFFF));
      }
      case 0b10_0011_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_0100_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0101_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0110_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0111_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1000_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1001_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1010_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1011_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1100_00 -> { // POP A=[SP], B=A (before read), C=B (SP=SP+3)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ * 3;  // Three memory reads for 24-bit value

        // Preserve the old values of AReg and BReg
        CReg = BReg;          // Move BReg into CReg
        BReg = AReg;          // Move the old AReg into BReg before reading

        // Read the 24-bit value from memory at the address pointed to by SP (wksp[15])
        AReg = signExtend24to32(read24BitValueFromMemory(wksp[15] & 0xFF_FFFF));  // Load and sign-extend

        // Increment SP to point to the next location (add 3 since we read 24 bits)
        wksp[15] = (wksp[15] + 3) & 0xFF_FFFF;
      }
      case 0b10_1101_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1110_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1111_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // ---

      case 0b11_0000_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0001_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0010_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0011_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_0100_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0101_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0110_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0111_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1000_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1001_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1010_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1011_00 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1100_00 -> { // PUSH [SP]=A, A=B, B=C (SP=SP-3)
        cycles += S_IFETCH + S_DECODE + S_MEM_WRITE * 3;  // Three memory writes for the 24-bit value

        // Calculate the new stack pointer address and write AReg's value
        wksp[15] = (wksp[15] - 3) & 0xFF_FFFF;  // Decrease SP by 3 and ensure it's within 24-bit range
        write24BitValueToMemory(wksp[15], AReg);  // Write AReg's 24-bit value to memory at SP

        // Move the values from BReg and CReg
        AReg = BReg;  // AReg takes the value of BReg
        BReg = CReg;  // BReg takes the value of CReg
      }
      case 0b11_1101_00 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1110_00 -> { // ST [B]=A, A=B, B=C
        cycles += S_IFETCH + S_DECODE + S_MEM_WRITE;  // One memory write operation that writes 3 bytes

        // Write the 24-bit value of AReg into memory at the address in BReg
        write24BitValueToMemory(BReg & 0xFF_FFFF, AReg);

        // Move the values from BReg and CReg
        AReg = BReg;  // AReg takes the value of BReg
        BReg = CReg;  // BReg takes the value of CReg
      }
      case 0b11_1111_00 -> // ???
          cycles += S_IFETCH + S_DECODE;


      // === 0b00_0000_01 (LB)

      case 0b00_0000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0010_01 -> { // LB A=[A] (Load Byte and Sign-Extend)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;

        // Preserve the old values of AReg and BReg
        CReg = BReg;              // Move BReg into CReg
        BReg = AReg;              // Move the old AReg into BReg before reading

        // Load the byte and sign-extend directly into AReg
        AReg = memory.read(AReg & 0xFF_FFFF);  // Read byte and cast to int for sign extension
      }
      case 0b00_0011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b00_0100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b00_1000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1010_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b00_1100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;


      // ---

      case 0b01_0000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0010_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b01_0100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b01_1000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1010_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b01_1100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1110_01 -> { // SB [B] = A (signed 8-bit), A = B, B = C
        cycles += S_IFETCH + S_DECODE + S_MEM_WRITE;

        // Write only the lower 8 bits of AReg (signed byte) into memory at the address in BReg
        memory.write(BReg & 0xFF_FFFF, (byte) (AReg & 0xFF));

        // Move the values from BReg and CReg
        AReg = BReg;  // AReg takes the value of BReg
        BReg = CReg;  // BReg takes the value of CReg
      }
      case 0b01_1111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // === 0b10_0000_01 (LU)

      case 0b10_0000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0010_01 -> { // LU A=[A] (Load Unsigned Byte)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;

        // Preserve the old values of AReg and BReg
        CReg = BReg;              // Move BReg into CReg
        BReg = AReg;              // Move the old AReg into BReg before reading

        // Load the unsigned byte and zero-extend into AReg
        AReg = memory.read(AReg & 0xFF_FFFF) & 0xFF;  // Load byte, mask to ensure it's unsigned
      }
      case 0b10_0011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_0100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1010_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // ---

      case 0b11_0000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0010_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_0100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1000_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1001_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1010_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1011_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1100_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1101_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1110_01 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1111_01 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // === 0b00_0000_10 (B k)

      case 0b00_0000_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0001_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0010_10 -> { // B A=[A] (Load Immediate Signed Byte and Sign-Extend)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;

        // Preserve the old values of AReg and BReg
        CReg = BReg;              // Move BReg into CReg
        BReg = AReg;              // Move the old AReg into BReg before reading

        // Read the signed byte from memory and sign-extend it into AReg
        AReg = signExtend8to32(fetch8BitOperand());
      }
      case 0b00_0011_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b00_0100_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0101_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0110_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_0111_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b00_1000_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1001_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1010_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b00_1011_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b00_1100_10 -> { // SRL 1 (A = A >>> 1)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >>> 1) & 0xFF_FFFF; // Result is in the 24-bit range
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b00_1101_10 -> { // SRL 2 (A = A >>> 2)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >>> 2) & 0xFF_FFFF; // Result is in the 24-bit range
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b00_1110_10 -> { // SRL 3 (A = A >>> 3)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >>> 3) & 0xFF_FFFF; // Result is in the 24-bit range
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b00_1111_10 -> { // SRL 4 (A = A >>> 4)
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >>> 4) & 0xFF_FFFF; // Result is in the 24-bit range
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }

      // ---

      case 0b01_0000_10 -> { // BEQ k (IPtr = IPtr + k, B == A, A = C)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        if (BReg == AReg) {
          IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointer
        }
        AReg = CReg; // A takes value of C
      }
      case 0b01_0001_10 -> { // BNE k (IPtr = IPtr + k, B != A, A = C)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        if (BReg != AReg) {
          IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointer
        }
        AReg = CReg; // A takes value of C
      }
      case 0b01_0010_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_0011_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b01_0100_10 -> { // BLT k (IPtr = IPtr + k, B < A, A = C)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        if (BReg < AReg) {
          IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointer
        }
        AReg = CReg; // A takes value of C
      }
      case 0b01_0101_10 -> { // BLTU k (IPtr = IPtr + k, B < A (unsigned), A = C)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        if (Integer.compareUnsigned(BReg, AReg) < 0) {
          IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointers
        }
        AReg = CReg; // A takes value of C
      }
      case 0b01_0110_10 -> { // BGE k (IPtr = IPtr + k, B >= A, A = C)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        if (BReg >= AReg) {
          IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointers
        }
        AReg = CReg; // A takes value of C
      }
      case 0b01_0111_10 -> { // BGEU k (IPtr = IPtr + k, B >= A (unsigned), A = C)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        if (Integer.compareUnsigned(BReg, AReg) >= 0) {
          IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointer
        }
        AReg = CReg; // A takes value of C
      }

      case 0b01_1000_10 -> { // J k (IPtr = IPtr + k)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointer
      }
      case 0b01_1001_10 -> { // JAL k (IPtr = IPtr + k, A = PC + 1)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int offset = signExtend8to32(fetch8BitOperand()); // signed byte for branch offset
        AReg = IPtr; // Save the return address (IPtr + 1) in AReg
        IPtr = (IPtr  + offset) & 0xFF_FFFF; // Apply offset to instruction pointer
      }
      case 0b01_1010_10 -> { // JR (PC = A, A = B, B = C)
        cycles += S_IFETCH + S_DECODE;
        IPtr = AReg & 0xFF_FFFF; // Jump to address in AReg
        AReg = BReg; // A takes value of B
        BReg = CReg; // B takes value of C
      }
      case 0b01_1011_10 -> { // JALR (PC = A, A = PC + 1)
        cycles += S_IFETCH + S_DECODE;
        tReg = IPtr; // Temporarily store the current PC
        IPtr = AReg & 0xFF_FFFF; // Jump to address in AReg
        AReg = tReg; // A takes the return address (PC + 1)
      }

      case 0b01_1100_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1101_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b01_1110_10 -> { // ECALL: Environment/System Call
        // Increment the cycle count for instruction fetch and decode
        cycles += S_IFETCH + S_DECODE;
        //setInterruptPending(SYSTEM_CALL_INTERRUPT_MASK);
        handleECall();
      }
      case 0b01_1111_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // === 0b10_0000_10 (U k)

      case 0b10_0000_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0001_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0010_10 -> { // U A=[A] (Load Immediate Unsigned Byte)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;

        // Preserve the old values of AReg and BReg
        CReg = BReg;              // Move BReg into CReg
        BReg = AReg;              // Move the old AReg into BReg before reading

        // Load the unsigned byte and zero-extend into AReg
        AReg = fetch8BitOperand();  // Load byte, mask to ensure it's unsigned
      }
      case 0b10_0011_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_0100_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0101_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0110_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0111_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1000_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1001_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1010_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_10111_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1100_10 -> { // SRA 1 A = A >> 1
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >> 1) | (AReg & 0x800000);  // Preserve the 24th bit (sign bit)
        AReg = signExtend24to32(AReg);           // Sign-extend to 32-bit
      }
      case 0b10_1101_10 -> { // SRA 2 A = A >> 2
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >> 2) | (AReg & 0x800000);  // Preserve the 24th bit (sign bit)
        AReg = signExtend24to32(AReg);           // Sign-extend to 32-bit
      }
      case 0b10_1110_10 -> { // SRA 3 A = A >> 3
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >> 3) | (AReg & 0x800000);  // Preserve the 24th bit (sign bit)
        AReg = signExtend24to32(AReg);           // Sign-extend to 32-bit
      }
      case 0b10_1111_10 -> { // SRA 4 A = A >> 4
        cycles += S_IFETCH + S_DECODE;
        AReg = (AReg >> 4) | (AReg & 0x800000);  // Preserve the 24th bit (sign bit)
        AReg = signExtend24to32(AReg);           // Sign-extend to 32-bit
      }

      // ---

      case 0b11_0000_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0001_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0010_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0011_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_0100_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0101_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0110_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0111_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1000_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1001_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1010_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1011_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1100_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1101_10 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1110_10 -> { // EBREAK: Breakpoint for debugging or halting the CPU
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
      case 0b11_1111_10 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // === 0b00_0000_11 (LDL)

      case 0b00_0000_11 -> { // LDL @0
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[0];
      }
      case 0b00_0001_11 -> { // LDL @1
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[1];
      }
      case 0b00_0010_11 -> { // LDL @2
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[2];
      }
      case 0b00_0011_11 -> { // LDL @3
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[3];
      }

      case 0b00_0100_11 -> { // LDL @4
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[4];
      }
      case 0b00_0101_11 -> { // LDL @5
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[5];
      }
      case 0b00_0110_11 -> { // LDL @6
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[6];
      }
      case 0b00_0111_11 -> { // LDL @7
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[7];
      }

      case 0b00_1000_11 -> { // LDL @8
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[8];
      }
      case 0b00_1001_11 -> { // LDL @9
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[9];
      }
      case 0b00_1010_11 -> { // LDL @10
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[10];
      }
      case 0b00_1011_11 -> { // LDL @11
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[11];
      }

      case 0b00_1100_11 -> { // LDL @12
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[12];
      }
      case 0b00_1101_11 -> { // LDL @13
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[13];
      }
      case 0b00_1110_11 -> { // LDL @14
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[14];
      }
      case 0b00_1111_11 -> { // LDL @15
        cycles += S_IFETCH + S_DECODE;
        CReg = BReg;
        BReg = AReg;
        AReg = wksp[15];
      }

      // ---

      case 0b01_0000_11 -> { // STL @0
        cycles += S_IFETCH + S_DECODE;
        wksp[0] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_0001_11 -> { // STL @1
        cycles += S_IFETCH + S_DECODE;
        wksp[1] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_0010_11 -> { // STL @2
        cycles += S_IFETCH + S_DECODE;
        wksp[2] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_0011_11 -> { // STL @3
        cycles += S_IFETCH + S_DECODE;
        wksp[3] = AReg;
        AReg = BReg;
        BReg = CReg;
      }

      case 0b01_0100_11 -> { // STL @4
        cycles += S_IFETCH + S_DECODE;
        wksp[4] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_0101_11 -> { // STL @5
        cycles += S_IFETCH + S_DECODE;
        wksp[5] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_0110_11 -> { // STL @6
        cycles += S_IFETCH + S_DECODE;
        wksp[6] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_0111_11 -> { // STL @7
        cycles += S_IFETCH + S_DECODE;
        wksp[7] = AReg;
        AReg = BReg;
        BReg = CReg;
      }

      case 0b01_1000_11 -> { // STL @8
        cycles += S_IFETCH + S_DECODE;
        wksp[8] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1001_11 -> { // STL @9
        cycles += S_IFETCH + S_DECODE;
        wksp[9] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1010_11 -> { // STL @10
        cycles += S_IFETCH + S_DECODE;
        wksp[10] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1011_11 -> { // STL @11
        cycles += S_IFETCH + S_DECODE;
        wksp[11] = AReg;
        AReg = BReg;
        BReg = CReg;
      }

      case 0b01_1100_11 -> { // STL @12
        cycles += S_IFETCH + S_DECODE;
        wksp[12] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1101_11 -> { // STL @13
        cycles += S_IFETCH + S_DECODE;
        wksp[13] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1110_11 -> { // STL @14
        cycles += S_IFETCH + S_DECODE;
        wksp[14] = AReg;
        AReg = BReg;
        BReg = CReg;
      }
      case 0b01_1111_11 -> { // STL @15
        cycles += S_IFETCH + S_DECODE;
        wksp[15] = AReg;
        AReg = BReg;
        BReg = CReg;
      }

      // === 0b10_0000_11 (I w)

      case 0b10_0000_11 -> { // I_#0 A = 0, B = A, C = B
        cycles += S_IFETCH + S_DECODE;
        BReg = AReg;  // Move AReg into BReg
        CReg = BReg;  // Move BReg into CReg
        AReg = 0;  // Set AReg to 0
      }
      case 0b10_0001_11 -> { // I_#1 A = 1, B = A, C = B
        cycles += S_IFETCH + S_DECODE;
        BReg = AReg;  // Move AReg into BReg
        CReg = BReg;  // Move BReg into CReg
        AReg = 1;  // Set AReg to 1
      }
      case 0b10_0010_11 -> { // I w, A = Immediate 24-bit value, B = A, C = B
        cycles += S_IFETCH + S_DECODE + S_MEM_READ + S_MEM_READ + S_MEM_READ;

        BReg = AReg;  // Move AReg into BReg
        CReg = BReg;  // Move BReg into CReg

        // Fetch 24-bit immediate from memory, incrementing IPtrs
        AReg = signExtend24to32(fetch24BitOperand());  // Sign-extend to 32 bits if necessary
      }
      case 0b10_0011_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_0100_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0101_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0110_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_0111_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1000_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1001_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1010_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1011_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b10_1100_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1101_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1110_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b10_1111_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      // ---

      case 0b11_0000_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0001_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0010_11 -> { // AIIP w, A = IPtr + w, B = A, C = B
        cycles += S_IFETCH + S_DECODE + S_MEM_READ * 3;  // Three memory reads for 24-bit value

        // Copy AReg to BReg and CReg
        BReg = AReg;
        CReg = BReg;

        // Fetch 24-bit immediate from memory
        int immediate = signExtend24to32(fetch24BitOperand());    // Sign-extend if necessary

        // Add the immediate value to IPtr and store the result in AReg
        AReg = (IPtr + immediate) & 0xFF_FFFF;  // Result is a 24-bit value
        AReg = signExtend24to32(AReg);  // Sign-extend to 32 bits if necessary
      }
      case 0b11_0011_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_0100_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0101_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0110_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_0111_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1000_11 -> { // SETI mie|=k, k=1,2,4,8 (mask)
        cycles += S_IFETCH + S_DECODE + S_MEM_READ;
        int mask = fetch8BitOperand() & 0x07;  // Fetch the interrupt mask from the next byte
        mie |= mask;  // Set the corresponding bit(s) in the mie register
      }
      case 0b11_1001_11 -> { // CLRI mie&=k, k=1,2,4,8 (mask)
        cycles += S_IFETCH + S_DECODE;
        int mask = fetch8BitOperand() & 0x07;  // Fetch the interrupt mask from the next byte
        mie &= ~mask;  // Clear the corresponding bit(s) in the mie register
      }
      case 0b11_1010_11 -> // ???
          cycles += S_IFETCH + S_DECODE;
      case 0b11_1011_11 -> // ???
          cycles += S_IFETCH + S_DECODE;

      case 0b11_1100_11 -> { // EI
        cycles += S_IFETCH + S_DECODE;
        MIE = true;
      }
      case 0b11_1101_11 -> { // DI
        cycles += S_IFETCH + S_DECODE;
        MIE = false;
      }
      case 0b11_1110_11 -> { // IRET
        cycles += S_IFETCH + S_DECODE;

        // Acknowledge interrupt (clear pending flag)
        if (currentInterrupt != -1) {
          int _currentInterrupt = currentInterrupt;
          acknowledgeInterrupt(currentInterrupt);
          currentInterrupt = -1;  // Clear interrupt

          if (_currentInterrupt != SOFTWARE_INTERRUPT_MASK) {
            // Restore state (e.g., instruction pointer and registers)
            IPtr = wksp[14];
            AReg = wksp[13];
            BReg = wksp[12];
            CReg = wksp[11];
          }

          MIE = true;  // Re-enable interrupts
        }
      }
      case 0b11_1111_11 -> { // HLT
        cycles += S_IFETCH + S_DECODE;
        // Implement the behavior for halting the CPU
        halted = true;  // Assuming there's a 'halted' flag in your CPU simulation
      }

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
      IPtr = MTVEC;
    }
  }

  // Function to prioritize interrupts based on mip
  private int prioritizeInterrupt() {
    // Check the highest priority interrupt first (Software Interrupt)
    if ((mip & SOFTWARE_INTERRUPT_MASK) != 0) return SOFTWARE_INTERRUPT_MASK;
    if ((mip & TIMER_INTERRUPT_MASK) != 0) return TIMER_INTERRUPT_MASK;
    if ((mip & EXTERNAL_INTERRUPT_MASK) != 0) return EXTERNAL_INTERRUPT_MASK;
    if ((mip & DIV_ZERO_INTERRUPT_MASK) != 0) return DIV_ZERO_INTERRUPT_MASK;
    if ((mip & SYSTEM_CALL_INTERRUPT_MASK) != 0) return SYSTEM_CALL_INTERRUPT_MASK;

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

  // Helper method for 24-bit sign extension
  private int signExtend24to32(int value) {
    // If the 24th bit (sign bit) is set, sign-extend to 32 bits
    if ((value & 0x80_0000) != 0) {
      value |= 0xFF000000;  // Fill the upper 8 bits with 1s for sign-extension
    }
    return value;
  }

  // ---

  private static final int EXIT = 0x00;
  private static final int REGISTER_DUMP = 0x01;
  private static final int MEMORY_DUMP = 0x02;
  private static final int PRINT_INT = 0x03;
  private static final int PRINT_CHAR = 0x04;
  private static final int READ_CHAR = 0x05;
  private static final int PRINT_STRING = 0x06;
  private static final int READ_STRING = 0x07;

  /**
   * Handles ebreak instruction.
   */
  private void handleEBreak() {
    // TODO
  }

  /**
   * Handles ecall instruction.
   */
  private void handleECall() {
    //System.out.println(format("ECALL 0x%02x", AReg));

    switch (AReg) {
      case EXIT -> {
        System.out.println("Exiting program...");
        System.exit(0);
      }
      case REGISTER_DUMP -> {
        System.out.println("------------");
        System.out.printf("AReg: %06x%n", AReg & 0xFF_FFFF);
        System.out.printf("BReg: %06x%n", BReg & 0xFF_FFFF);
        System.out.printf("CReg: %06x%n", CReg & 0xFF_FFFF);
        System.out.println("------------");
        for (int i = 0; i < 16; i++) {
          System.out.printf(" @%x : %06x%n", i, wksp[i] & 0xFF_FFFF);
        }
        System.out.println("------------\n");

        // Stack shift: discard AReg
        AReg = BReg;
        BReg = CReg;
      }
      case MEMORY_DUMP -> {
        int m = (BReg - BReg % 16) & 0xFF_FFFF;
        System.out.println();
        for (int i = 0; i < 16; i++) {
          System.out.printf("%06x", m);
          for (int j = 0; j < 16; j++) {
            System.out.printf(" | %02x", memory.read(m));
            m = (m + 1) & 0xFF_FFFF;
          }
          System.out.println();

          // Stack shift: discard AReg
          AReg = BReg;
          BReg = CReg;
        }
      }
      case PRINT_INT -> {
        System.out.print(BReg);

        // Stack shift: discard AReg
        AReg = BReg;
        BReg = CReg;
      }
      case PRINT_CHAR -> {
        System.out.print((char) (BReg & 0xFF)); // ASCII

        // Stack shift: discard AReg
        AReg = BReg;
        BReg = CReg;
      }
      case READ_CHAR -> {
        try {
          AReg = System.in.read() & 0xFF;
        } catch (IOException e) {
          AReg = -1; // Error reading input
        }
      }
      case PRINT_STRING -> {
        int s = BReg & 0xFF_FFFF, c;
        while ((c = memory.read(s++)) != 0) {
          System.out.print((char) (c & 0xFF)); // ASCII
        }
        System.out.println();

        // Stack shift: discard AReg
        AReg = BReg;
        BReg = CReg;
      }
      case READ_STRING -> {
        int buffer = CReg & 0xFF_FFFF;  // Starting memory address
        int maxlen = BReg & 0xFF;  // Maximum length of string (1 byte)

        try {
          String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
          int length = Math.min(line.length(), maxlen - 1);  // Ensure string fits in maxlen

          for (int i = 0; i < length; i++) {
            memory.write(buffer + i, (byte) line.charAt(i));  // Write each character to memory
          }
          memory.write(buffer + length, (byte) 0);  // Null-terminate string

          AReg = length;  // Success
        } catch (IOException e) {
          AReg = -1;  // Error handling
        }
      }
    }
  }
}
