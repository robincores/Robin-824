package io.github.robincores.r8.system;

/** Anything that advances with CPU time. */
public interface Tickable {
    void tick(int cycles);
}
