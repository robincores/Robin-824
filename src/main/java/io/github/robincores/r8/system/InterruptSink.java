package io.github.robincores.r8.system;

/** Narrow CPU-facing interface: peripherals raise IRQs without knowing CPU type. */
public interface InterruptSink {
    void raise(int interruptBit);
}
