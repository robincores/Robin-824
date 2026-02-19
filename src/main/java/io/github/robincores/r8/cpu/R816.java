package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;

public final class R816 extends R8Core {
    public R816(Bus bus) {
        super(bus, 0xFFFF, 2, 0xFFFF, 0x8000);
    }
}
