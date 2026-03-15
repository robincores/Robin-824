package io.github.robincores.toolchain.r8as;

import java.util.List;
import java.util.Map;

/**
 * Assembler variable definition.
 *
 * - {@code bits}: bit-width of the operand.
 * - {@code toks}: optional token table mapping index -> textual token.
 * - {@code aliases}: optional map of alias token -> canonical token in {@code toks}.
 * - {@code iprel/ipofs/ipmul}: instruction-pointer-relative fixup settings.
 * - {@code endian}: optional "little" for multi-byte immediates.
 */
class AssemblerVar {

    public int bits;
    public List<String> toks;

    /** Optional alias -> canonical token mapping (e.g., "sp" -> "x2"). */
    public Map<String, String> aliases;

    // Fixup-related
    public boolean iprel = false;
    public int ipofs = 0;
    public int ipmul = 1;

    // Immediate endianness hint
    public String endian;

    public AssemblerVar(int bits, List<String> toks, boolean iprel, int ipofs, int ipmul, String endian) {
        this.bits = bits;
        this.toks = toks;
        this.iprel = iprel;
        this.ipofs = ipofs;
        this.ipmul = ipmul;
        this.endian = endian;
    }
}
