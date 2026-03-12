package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;

/**
 * System MMIO page.
 *
 * <p><b>Address map:</b> mapped at {@code 0xBFF0–0xBFFF} (16 bytes).</p>
 *
 * <p>This page currently exposes a single register, {@link #REG_VIDWIN} (offset {@code 0x00}),
 * used to control what appears in the {@code 0xC000–0xFFFF} VideoWindow:</p>
 *
 * <ul>
 *   <li>{@code WIN_MMIO=1} → VideoWindow exposes the VPU MMIO page.</li>
 *   <li>{@code WIN_MMIO=0} → VideoWindow exposes a 16K bank selected by {@code VBANK}.</li>
 * </ul>
 *
 * <h2>VIDWIN bit layout</h2>
 * <ul>
 *   <li>bits 0..3: {@code VBANK} — selects VideoWindow bank {@code 0..15}</li>
 *   <li>bit 4: {@code WIN_MMIO} — selects MMIO view when set</li>
 * </ul>
 *
 * <h2>Recommended bank usage (when {@code WIN_MMIO=0})</h2>
 * <ul>
 *   <li>{@code 0x0}: text overlay / reserved (e.g., 8K text + 8K reserved/font/future)</li>
 *   <li>{@code 0x1..0x8}: 8 bitplane framebuffer banks</li>
 *   <li>{@code 0x9}: copper program + tables/lists</li>
 *   <li>{@code 0xA..0xF}: sprite data banks</li>
 * </ul>
 */
public final class SysMMIO implements BusDevice {

    /** MMIO page size in bytes ({@code 0xBFF0..0xBFFF}). */
    public static final int SIZE = 0x10;

    /** Register offset: VideoWindow control register. */
    public static final int REG_VIDWIN = 0x00;

    /** VIDWIN bits 0..3: bank select (0..15). */
    public static final int VIDWIN_VBANK_MASK = 0x0F;

    /** VIDWIN bit 4: when set, VideoWindow exposes VPU MMIO instead of banked memory. */
    public static final int VIDWIN_WIN_MMIO = 0x10;

    /** Recommended bank ids (when {@code WIN_MMIO=0}). */
    public static final int BANK_TEXT_OVERLAY = 0x0;   // text overlay / reserved
    public static final int BANK_PLANES_FIRST = 0x1;   // planes 1..8
    public static final int BANK_PLANES_LAST  = 0x8;
    public static final int BANK_COPPER_TABLES = 0x9;  // copper program + tables
    public static final int BANK_SPRITES_FIRST = 0xA;  // sprites A..F
    public static final int BANK_SPRITES_LAST  = 0xF;

    private int vidwin;

    /** @return raw VIDWIN register value (0..255). */
    public int vidwin() {
        return vidwin & 0xFF;
    }

    /** @return selected VideoWindow bank (0..15), valid when {@link #winMmio()} is false. */
    public int vbank() {
        return vidwin & VIDWIN_VBANK_MASK;
    }

    /** @return true when the VideoWindow exposes VPU MMIO rather than banked memory. */
    public boolean winMmio() {
        return (vidwin & VIDWIN_WIN_MMIO) != 0;
    }

    @Override
    public byte read(int offset) {
        if ((offset & 0x0F) == REG_VIDWIN) return (byte) vidwin();
        return 0;
    }

    @Override
    public void write(int offset, byte value) {
        if ((offset & 0x0F) == REG_VIDWIN) {
            vidwin = Byte.toUnsignedInt(value);
        }
    }

    @Override
    public int size() {
        return SIZE;
    }
}