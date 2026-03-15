package io.github.robincores.toolchain.r8as;

/** Result of assembling a single instruction.
 *  Uses a 64-bit opcode so we can support 48-bit (and up to 64-bit) instructions.
 */
public class AssemblerInstruction implements AssemblerLineResult {

    public final long opcode;
    public final int nbits;

    public AssemblerInstruction(long opcode, int nbits) {
        this.opcode = opcode;
        this.nbits = nbits;
    }

    @Override
    public String toString() {
        return "AssemblerInstruction{opcode=" + opcode + ", nbits=" + nbits + "}";
    }
}
