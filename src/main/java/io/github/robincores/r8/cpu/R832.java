package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;

public class R832 extends R8Core {
    public static Config.Builder config() {
        return Config.builder(0xFFFF_FFFF, 4, 0xFFFF_FFFF, 0x8000_0000);
    }

    public R832(Bus bus) {
        this(bus, config().build());
    }

    public R832(Bus bus, Config cfg) {
        super(bus, cfg);
    }

    public R832(Bus bus, boolean hasR8Blk) {
        this(bus, config().hasR8Blk(hasR8Blk).build());
    }

    public R832(Bus bus, boolean hasR8Blk, MicroFusionMode microFusionMode) {
        this(bus, config().hasR8Blk(hasR8Blk).microFusionMode(microFusionMode).build());
    }
}
