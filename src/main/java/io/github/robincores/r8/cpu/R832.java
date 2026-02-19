package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;

public class R832 extends R8Core {
    public R832(Bus bus) {
        super(bus, 0xFFFF_FFFF, 4, 0xFFFF_FFFF, 0x8000_0000);
    }
}
