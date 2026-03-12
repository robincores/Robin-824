package io.github.robincores.r8.cpu;

/**
 * Auto-generated opcode→mnemonic tables from the ISA spreadsheet.
 *
 * <p>Used for debugging, disassembly, and diagnostics (never for execution).</p>
 */
public final class R8IsaTables {
    private R8IsaTables() {}

    public static final String[] BASE = new String[256];
    static {
        BASE[0x00] = "NOP";
        BASE[0x01] = "LD";
        BASE[0x02] = "B";
        BASE[0x03] = "LDL0";
        BASE[0x04] = "DUP";
        BASE[0x05] = "LB";
        BASE[0x06] = "U";
        BASE[0x07] = "LDL1";
        BASE[0x08] = "SWAP";
        BASE[0x09] = "LU";
        BASE[0x0B] = "LDL2";
        BASE[0x0C] = "DROP1";
        BASE[0x0D] = "LH";
        BASE[0x0F] = "LDL3";
        BASE[0x10] = "DROP2";
        BASE[0x11] = "LHU";
        BASE[0x13] = "LDL4";
        BASE[0x14] = "I2B";
        BASE[0x15] = "L32";
        BASE[0x17] = "LDL5";
        BASE[0x19] = "L32U";
        BASE[0x1B] = "LDL6";
        BASE[0x1F] = "LDL7";
        BASE[0x20] = "ADD";
        BASE[0x21] = "ST";
        BASE[0x22] = "BEQ";
        BASE[0x23] = "LDL8";
        BASE[0x24] = "SUB";
        BASE[0x25] = "SB";
        BASE[0x26] = "BNE";
        BASE[0x27] = "LDL9";
        BASE[0x28] = "MUL";
        BASE[0x29] = "SH";
        BASE[0x2A] = "BLT";
        BASE[0x2B] = "LDL10";
        BASE[0x2C] = "DIV";
        BASE[0x2D] = "S32";
        BASE[0x2E] = "BLTU";
        BASE[0x2F] = "LDL11";
        BASE[0x30] = "REM";
        BASE[0x32] = "BGE";
        BASE[0x33] = "LDL12";
        BASE[0x34] = "INC";
        BASE[0x36] = "BGEU";
        BASE[0x37] = "LDL13";
        BASE[0x38] = "DEC";
        BASE[0x3A] = "BEQZ";
        BASE[0x3B] = "LDL14";
        BASE[0x3C] = "NEG";
        BASE[0x3E] = "BNEZ";
        BASE[0x3F] = "LDL15";
        BASE[0x40] = "AND";
        BASE[0x41] = "POP";
        BASE[0x42] = "JR";
        BASE[0x43] = "I";
        BASE[0x44] = "OR";
        BASE[0x45] = "PUSH";
        BASE[0x46] = "JALR";
        BASE[0x47] = "AIIP";
        BASE[0x48] = "XOR";
        BASE[0x4B] = "CSRR";
        BASE[0x4C] = "INV";
        BASE[0x4F] = "CSRW";
        BASE[0x50] = "SEQ";
        BASE[0x53] = "SETI";
        BASE[0x54] = "SNE";
        BASE[0x57] = "CLRI";
        BASE[0x5B] = "EI";
        BASE[0x5F] = "DI";
        BASE[0x60] = "SHL";
        BASE[0x63] = "IRET";
        BASE[0x64] = "SHR";
        BASE[0x67] = "ECALL";
        BASE[0x68] = "SAR";
        BASE[0x6B] = "EBREAK";
        BASE[0x6C] = "SLT";
        BASE[0x6F] = "FENCE";
        BASE[0x70] = "SLTU";
        BASE[0x73] = "J";
        BASE[0x77] = "JAL";
        BASE[0x7B] = "LDLX";
        BASE[0x7F] = "ESC";
        BASE[0x80] = "ADDC";
        BASE[0x83] = "STL0";
        BASE[0x84] = "SUBB";
        BASE[0x87] = "STL1";
        BASE[0x88] = "MULH";
        BASE[0x8B] = "STL2";
        BASE[0x8C] = "MULHU";
        BASE[0x8F] = "STL3";
        BASE[0x90] = "CLZ";
        BASE[0x93] = "STL4";
        BASE[0x97] = "STL5";
        BASE[0x9B] = "STL6";
        BASE[0x9F] = "STL7";
        BASE[0xA2] = "BRA";
        BASE[0xA3] = "STL8";
        BASE[0xA7] = "STL9";
        BASE[0xAB] = "STL10";
        BASE[0xAC] = "DIVU";
        BASE[0xAF] = "STL11";
        BASE[0xB0] = "REMU";
        BASE[0xB3] = "STL12";
        BASE[0xB7] = "STL13";
        BASE[0xBB] = "STL14";
        BASE[0xBF] = "STL15";
        BASE[0xFB] = "STLX";
        BASE[0xFF] = "HLT";
    }

    public static final String[] EXT0 = new String[256];
    static {
        EXT0[0x01] = "MOVB";
        EXT0[0x05] = "MOVW";
        EXT0[0x09] = "FILLB";
        EXT0[0x0D] = "FILLW";
        EXT0[0xFF] = "XESC";
    }
}
