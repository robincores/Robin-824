package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;

public final class R816 extends R8Core {
    public static Config.Builder config() {
        return Config.builder(0xFFFF, 2, 0xFFFF, 0x8000);
    }

    public R816(Bus bus) {
        this(bus, config().build());
    }

    public R816(Bus bus, Config cfg) {
        super(bus, cfg);
    }

    public R816(Bus bus, boolean hasR8Blk) {
        this(bus, config().hasR8Blk(hasR8Blk).build());
    }

    public R816(Bus bus, boolean hasR8Blk, MicroFusionMode microFusionMode) {
        this(bus, config().hasR8Blk(hasR8Blk).microFusionMode(microFusionMode).build());
    }
}
