package org.robincores.r8.system;

import org.robincores.r8.cpu.Memory;
import org.robincores.r8.cpu.R824;

/**
 * Represents a timer device in the R8 family system that tracks CPU cycles.
 * The timer generates an interrupt when a certain comparison value (mtimecmp) is reached.
 * It uses a 24-bit timer (mtime) and comparison register (mtimecmp).
 *
 * The timer is automatically reset once an interrupt is triggered, and it will disable itself
 * until the comparison register (mtimecmp) is updated again to enable it.
 */
public class TimerDevice implements Memory {

  // The current timer value (mtime) which counts CPU cycles.
  private int mtime;

  // The comparison value (mtimecmp) used to trigger an interrupt when mtime reaches or exceeds it.
  private int mtimecmp;

  // Reference to the CPU instance to trigger interrupts when necessary.
  private final R824 cpu;

  /**
   * Constructs a TimerDevice instance for the R824 CPU.
   * Initializes the timer and comparison register.
   * The comparison register is set to its maximum value by default, meaning no interrupt will occur initially.
   *
   * @param cpu The CPU instance that this timer interacts with to trigger interrupts.
   */
  public TimerDevice(R824 cpu) {
    this.cpu = cpu;
    mtime = 0; // Start the timer at 0.
    mtimecmp = 0xFFFFFFFF;  // Initialize mtimecmp to max, disabling interrupts initially.
  }

  /**
   * Reads a byte from the comparison register (mtimecmp) depending on the address.
   * mtimecmp is 24-bit wide, so three bytes can be accessed.
   *
   * The timer itself (mtime) cannot be read or written to since its role is only
   * to track CPU cycles for interrupt generation.
   *
   * @param address The memory address to read from. It determines whether we read from mtimecmp.
   * @return The byte read from the specified address.
   */
  @Override
  public byte read(int address) {
    return switch (address) {
      case 0x00 -> // Read the low byte of mtimecmp (least significant byte).
          (byte) (mtimecmp & 0xFF);
      case 0x01 -> // Read the middle byte of mtimecmp.
          (byte) ((mtimecmp >> 8) & 0xFF);
      case 0x02 -> // Read the high byte of mtimecmp (most significant byte).
          (byte) ((mtimecmp >> 16) & 0xFF);
      default -> 0; // If an invalid address is requested, return 0.
    };
  }

  /**
   * Writes a byte to the comparison register (mtimecmp) based on the address.
   * Each register is 24-bits wide, so it accepts three different byte writes.
   * When the high byte of mtimecmp is written, the timer (mtime) is reset to 0.
   *
   * @param address The memory address to write to. This determines whether to write to mtimecmp.
   * @param value The byte value to write.
   */
  @Override
  public void write(int address, byte value) {
    switch (address) {
      case 0x00 -> // Write the low byte of mtimecmp (least significant byte).
          mtimecmp = (mtimecmp & 0xFFFF00) | (value & 0xFF);
      case 0x01 -> // Write the middle byte of mtimecmp.
          mtimecmp = (mtimecmp & 0xFF00FF) | ((value & 0xFF) << 8);
      case 0x02 -> { // Write the high byte of mtimecmp (most significant byte).
        mtimecmp = (mtimecmp & 0x00FFFF) | ((value & 0xFF) << 16);
        mtime = 0; // Reset the timer when the comparison value is fully updated.
      }
    }
  }

  /**
   * Increments the timer (mtime) by the specified number of CPU cycles.
   * If the timer exceeds the comparison value (mtimecmp), a timer interrupt is triggered.
   * The timer is reset to 0 and disabled after triggering an interrupt.
   *
   * @param cycles The number of CPU cycles to increment the timer by.
   */
  public void incrementTime(int cycles) {
    if (mtimecmp > 0) {
      mtime += cycles;

      // Check if the timer has reached or exceeded the comparison value (mtimecmp).
      // If so, trigger a timer interrupt.
      if (Integer.compareUnsigned(mtime, mtimecmp) >= 0) {
        cpu.setInterruptPending(R824.TIMER_INTERRUPT_MASK);  // Trigger the timer interrupt.
        mtimecmp |= 0x8000_0000; // Disable the timer after interrupt
        mtime = 0; // Reset the timer after triggering the interrupt.
      }

    } else {
      // Timer is disabled, no increment is necessary.
    }
  }
}
