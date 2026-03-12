package io.github.robincores.r8.bus;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Address decoder for a {@link Bus}.
 *
 * <p>Maps {@link BusDevice}s into a flat byte-address space and routes reads/writes
 * by address. Rejects overlapping mappings. Unmapped reads return {@code 0};
 * unmapped writes are ignored.</p>
 *
 * <h3>Dispatch strategy</h3>
 * <ul>
 *   <li><b>Small address spaces</b> (≤ 64K): per-byte flat lookup table — O(1) dispatch,
 *       zero branching beyond a null check. ~768KB for 16-bit. This is the common case
 *       for the R816 (16-bit address bus).</li>
 *   <li><b>Large address spaces</b> (&gt; 64K): {@code TreeMap + floorEntry()} —
 *       O(log n) where n = number of mapped regions.</li>
 * </ul>
 *
 * <p>Ordering and bounds checks are done as <b>unsigned 32-bit</b> values so mappings
 * above {@code 0x8000_0000} behave correctly.</p>
 */
public final class BusMap implements Bus {

    /** Threshold: address spaces at or below this size use the flat table. */
    private static final long FLAT_LIMIT = 0x1_0000L; // 64K

    private final int addrMask;
    private final boolean flat;
    private final long addrSpace;

    // --- Flat-table path (addrSpace <= 64K) ---
    private final BusDevice[] flatDevice;
    private final int[] flatOffset;

    // --- TreeMap path (addrSpace > 64K) ---
    private final NavigableMap<Integer, Region> regions;

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

    /**
     * @param addrMask address mask (e.g. {@code 0xFFFF}, {@code 0xFF_FFFF}, {@code 0xFFFF_FFFF})
     */
    public BusMap(int addrMask) {
        this.addrMask = addrMask;

        this.addrSpace = (addrMask & 0xFFFF_FFFFL) + 1L;
        this.flat = (this.addrSpace <= FLAT_LIMIT);

        if (flat) {
            int size = (int) this.addrSpace;
            flatDevice = new BusDevice[size];
            flatOffset = new int[size];
            regions = null;
        } else {
            flatDevice = null;
            flatOffset = null;
            regions = new TreeMap<>(Integer::compareUnsigned);
        }
    }

    /** @return the address mask used by this bus map */
    public int addrMask() {
        return addrMask;
    }


    /** @return address space size (addrMask+1) as unsigned long */
    public long addrSpace() { return addrSpace; }

    // ================================================================
    // Mapping (setup time — not performance-critical)
    // ================================================================

    /**
     * Map a device at {@code base}. The mapped size is {@code device.size()} bytes.
     *
     * @throws IllegalArgumentException if the mapping overlaps an existing region
     */
    public void map(int base, BusDevice device) {
        if (device == null) throw new NullPointerException("device");
        int sz = device.size();
        if (sz <= 0) throw new IllegalArgumentException("device.size() must be > 0");
        if ((sz & 0xFFFF_FFFFL) > addrSpace) throw new IllegalArgumentException("device.size() exceeds address space: " + sz);

        int b = base & addrMask;

        // Disallow wrap-around mappings (keeps decoding unambiguous).
        long baseU = b & 0xFFFF_FFFFL;
        long endU = baseU + (sz & 0xFFFF_FFFFL);
        if (endU > addrSpace) {
            throw new IllegalArgumentException(
                    "Mapping wraps address space: base=0x" + Integer.toHexString(b) + " size=" + sz);
        }

        if (flat) {
            mapFlat(b, sz, device);
        } else {
            mapTree(b, sz, device);
        }
    }

    private void mapFlat(int base, int size, BusDevice device) {
        // Overlap check: every byte in the range must be unmapped.
        for (int i = 0; i < size; i++) {
            int addr = (base + i) & addrMask;
            if (flatDevice[addr] != null) {
                throw new IllegalArgumentException(
                        "Region overlap at 0x" + Integer.toHexString(addr)
                                + " (mapping 0x" + Integer.toHexString(base)
                                + " size " + size + ")");
            }
        }

        // Fill the table.
        for (int i = 0; i < size; i++) {
            int addr = (base + i) & addrMask;
            flatDevice[addr] = device;
            flatOffset[addr] = i;
        }
    }

    private void mapTree(int b, int size, BusDevice device) {
        long baseU = b & 0xFFFF_FFFFL;
        long endU = baseU + (size & 0xFFFF_FFFFL);
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

    // ================================================================
    // Hot path: read / write
    // ================================================================

    @Override
    public byte read8(int address) {
        if (flat) {
            int a = address & addrMask;
            BusDevice d = flatDevice[a];
            return (d != null) ? d.read(flatOffset[a]) : 0;
        }
        return readTree(address);
    }

    @Override
    public void write8(int address, byte value) {
        if (flat) {
            int a = address & addrMask;
            BusDevice d = flatDevice[a];
            if (d != null) d.write(flatOffset[a], value);
            return;
        }
        writeTree(address, value);
    }

    private byte readTree(int address) {
        int a = address & addrMask;
        var e = regions.floorEntry(a);
        if (e != null) {
            Region r = e.getValue();
            if (r.contains(a)) return r.device.read(r.offset(a));
        }
        return 0;
    }

    private void writeTree(int address, byte value) {
        int a = address & addrMask;
        var e = regions.floorEntry(a);
        if (e != null) {
            Region r = e.getValue();
            if (r.contains(a)) r.device.write(r.offset(a), value);
        }
    }
}