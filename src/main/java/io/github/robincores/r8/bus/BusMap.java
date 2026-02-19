package io.github.robincores.r8.bus;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Minimal address decoder for a {@link Bus}.
 *
 * <p>Maps {@link BusDevice}s into a flat byte-address space and routes reads/writes
 * by address. Uses {@code TreeMap + floorEntry()} (O(log n)). Rejects overlapping
 * mappings. Unmapped reads return {@code 0}; unmapped writes are ignored.</p>
 *
 * <p>Ordering and bounds checks are done as <b>unsigned 32-bit</b> values so mappings
 * above {@code 0x8000_0000} behave correctly.</p>
 */
public final class BusMap implements Bus {

    private record Region(int baseMasked, long endU, BusDevice device) {
        long baseU() { return baseMasked & 0xFFFF_FFFFL; }
        boolean contains(int addrMasked) {
            long a = addrMasked & 0xFFFF_FFFFL;
            return a >= baseU() && a < endU;
        }
        int offset(int addrMasked) {
            return (int) ((addrMasked & 0xFFFF_FFFFL) - baseU());
        }
    }

    private final int addrMask;
    private final NavigableMap<Integer, Region> regions =
            new TreeMap<>(Integer::compareUnsigned);

    /**
     * @param addrMask address mask (e.g. {@code 0xFFFF}, {@code 0xFF_FFFF}, {@code 0xFFFF_FFFF})
     */
    public BusMap(int addrMask) {
        this.addrMask = addrMask;
    }

    /** @return the address mask used by this bus map */
    public int addrMask() {
        return addrMask;
    }

    /**
     * Map a device at {@code base}. The mapped size is {@code device.size()} bytes.
     *
     * @throws IllegalArgumentException if the mapping overlaps an existing region
     */
    public void map(int base, BusDevice device) {
        int b = base & addrMask;

        long baseU = b & 0xFFFF_FFFFL;
        long endU = baseU + (device.size() & 0xFFFF_FFFFL);
        Region r = new Region(b, endU, device);

        var floor = regions.floorEntry(b);
        if (floor != null && floor.getValue().endU > r.baseU()) {
            throw new IllegalArgumentException("Region overlap at 0x" + Integer.toHexString(b));
        }

        var ceil = regions.ceilingEntry(b);
        if (ceil != null && r.endU > ceil.getValue().baseU()) {
            throw new IllegalArgumentException("Region overlap at 0x" + Integer.toHexString(b));
        }

        regions.put(b, r);
    }

    @Override
    public byte read8(int address) {
        int a = address & addrMask;
        var e = regions.floorEntry(a);
        if (e != null) {
            Region r = e.getValue();
            if (r.contains(a)) return r.device.read(r.offset(a));
        }
        return 0;
    }

    @Override
    public void write8(int address, byte value) {
        int a = address & addrMask;
        var e = regions.floorEntry(a);
        if (e != null) {
            Region r = e.getValue();
            if (r.contains(a)) r.device.write(r.offset(a), value);
        }
    }
}
