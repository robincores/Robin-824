package io.github.robincores.toolchain.r8cc.backend.r8;

/**
 * R816 backend dialect for r8cc that matches the BIOS/BASIC conventions.
 *
 * <h2>ABI v0.1 (standard)</h2>
 * <ul>
 *   <li>w15 = SP (memory stack pointer)</li>
 *   <li>w14 = LR (link register: callee stores return PC from JAL/JALR on entry)</li>
 *   <li>w0..w3 = arg0..arg3 (also: return value in w0)</li>
 *   <li>args 4+ are passed on the memory stack (see {@link #call})</li>
 *   <li>caller-saved by default: w0..w13 (use locals/spills if you need to preserve)</li>
 * </ul>
 *
 * <p>R8 is a stack machine architecturally (A/B/C). The compiler IR leaves values on
 * the operand stack; this dialect moves values to/from wksp registers only at ABI boundaries.</p>
 */
public final class R816Dialect implements R8AsmDialect {

    // Scratch temps used by backend sequences (avoid w14/w15 and hot arg regs).
    private static final String SCR  = "w10";
    private static final String SCR2 = "w11";

    // Locals start at w4 so we don't clobber arg regs (w0..w3).
    // Hot locals available: w4..w13 (10 regs). Beyond that, use LDLX/STLX (imm8 index).
    private static final int LOCAL_BASE = 4;
    private static final int LOCAL_LAST = 13; // w4..w13
    private static final int LOCAL_HOT  = LOCAL_LAST - LOCAL_BASE + 1;

    // Number of wksp slots consumed by homed extra params (arg4+)
    // for the function currently being emitted.
    private int currentExtraParamSlots = 0;
    private int branchSeq = 0;

    @Override
    public String preamble() {
        return """
.arch r816
.once

; r8cc R816 asm
; ABI v0.1 (matches BIOS/BASIC):
;   w15 = SP (memory stack)
;   w14 = LR (link register)
;   w0..w3 = arg0..arg3, return value in w0
;   args 4+ passed on memory stack
;
; Note: A/B/C are the operand stack cache. Calls/clib may clobber them.

.macro CALL target
  ldl w14
  push
  jal \\target
  pop
  stl w14
.endm

.macro RET
  ldl w14
  jr
.endm
""";
    }

    @Override public String funcLabel(String name) { return name + ":"; }

    private int physLocalIndex(int localIndex) {
        if (localIndex < 0) throw new IllegalArgumentException("local index < 0");
        return LOCAL_BASE + currentExtraParamSlots + localIndex;
    }

    /**
     * Prologue:
     *  - store return PC (in A at entry) into w14 (LR)
     *  - if paramCount > 4, pop arg4.. from memory stack into local slots w4..
     */
    @Override
    public String funcPrologue(int paramCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("  stl w14\n");

        currentExtraParamSlots = Math.max(0, paramCount - 4);

        // Home args 4+ into wksp slots starting at w4.
        // Later locals must start after these slots.
        if (currentExtraParamSlots > 0) {
            for (int i = 0; i < currentExtraParamSlots; i++) {
                int slot = LOCAL_BASE + i; // w4, w5, ...
                sb.append("  pop\n");
                sb.append("  stl w").append(slot).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String pushConst(int value) {
        // Prefer imm8 when possible; otherwise use native word immediate.
        if (value >= -128 && value <= 127) {
            return "  b " + value;
        }
        if (value >= 0 && value <= 255) {
            return "  u " + value;
        }
        int v = value & 0xFFFF;
        return String.format("  i 0x%04X", v);
    }

    @Override
    public String loadParam(int index) {
        if (index < 0) throw new IllegalArgumentException("param index < 0");
        if (index <= 3) return "  ldl w" + index;

        // arg4+ are homed by the prologue into w4, w5, ...
        int slot = LOCAL_BASE + (index - 4);
        return "  ldl w" + slot;
    }

    @Override
    public String bin(String op) {
        return switch (op) {
            case "+" -> "  add";
            case "-" -> "  sub";
            case "*" -> "  mul";
            case "/" -> "  div";
            case "%" -> "  rem";
            case "&" -> "  and";
            case "|" -> "  or";
            case "^" -> "  xor";
            case "==" -> "  seq";
            case "!=" -> "  sne";
            case "<"  -> "  slt";
            case ">"  -> "  swap\n  slt";
            case "<=" -> String.join("\n",
                    "  stl " + SCR2,
                    "  stl " + SCR,
                    "  ldl " + SCR,
                    "  ldl " + SCR2,
                    "  slt",
                    "  ldl " + SCR,
                    "  ldl " + SCR2,
                    "  seq",
                    "  or");
            case ">=" -> String.join("\n",
                    "  stl " + SCR2,
                    "  stl " + SCR,
                    "  ldl " + SCR2,
                    "  ldl " + SCR,
                    "  slt",
                    "  ldl " + SCR,
                    "  ldl " + SCR2,
                    "  seq",
                    "  or");
            case "&&" -> String.join("\n",
                    "  u 0",
                    "  sne",
                    "  swap",
                    "  u 0",
                    "  sne",
                    "  and");
            case "||" -> String.join("\n",
                    "  u 0",
                    "  sne",
                    "  swap",
                    "  u 0",
                    "  sne",
                    "  or");
            default -> throw new IllegalArgumentException("R816 BIN op not supported yet: " + op);
        };
    }

    @Override
    public String un(String op) {
        return switch (op) {
            case "-" -> "  neg";
            case "+" -> ""; // no-op
            default -> throw new IllegalArgumentException("R816 UN op not supported yet: " + op);
        };
    }

    /**
     * Call sequence (ABI v0.1):
     * <ul>
     *   <li>args are on operand stack: ..., arg0, arg1, ..., argN-1 (top)</li>
     *   <li>if N>4: push extra args to memory stack so arg4 becomes topmost there</li>
     *   <li>move arg0..arg3 into w0..w3 (via stl w3..w0)</li>
     *   <li>CALL callee</li>
     *   <li>result in w0; push it back to operand stack via ldl w0</li>
     * </ul>
     */
    @Override
    public String call(String name, int argc) {
        if (argc < 0) throw new IllegalArgumentException("argc<0");
        if (argc > 4 + LOCAL_HOT) {
            // hard guard for now; can be lifted when we standardize large-arg spills
            throw new IllegalArgumentException("argc too large for ABI v0.1 backend: " + argc);
        }

        StringBuilder sb = new StringBuilder();

        // Spill extra args (arg4..argN-1) to memory stack.
        // We PUSH from top downward; after this loop, arg4 is at the top of memory stack.
        for (int i = argc - 1; i >= 4; i--) {
            sb.append("  push\n");
        }

        // Move up to 4 register args from operand stack into w0..w3.
        int regArgs = Math.min(argc, 4);
        for (int i = regArgs - 1; i >= 0; i--) {
            sb.append("  stl w").append(i).append("\n"); // store arg i and pop it from operand stack
        }

        sb.append("  CALL ").append(name).append("\n");

        // Push return value back to operand stack.
        sb.append("  ldl w0");

        return sb.toString();
    }

    /**
     * Return:
     *  - store top-of-stack value into w0 (return register)
     *  - return to LR (w14)
     */
    @Override
    public String ret() {
        return "  stl w0\n  RET";
    }

    @Override
    public String loadLocal(int wk) {
        int idx = physLocalIndex(wk);
        if (idx <= LOCAL_LAST) {
            return "  ldl w" + idx;
        }
        if (idx > 255) throw new IllegalArgumentException("local index too large for LDLX: " + idx);
        return "  ldlx " + idx;
    }

    @Override
    public String storeLocal(int wk) {
        int idx = physLocalIndex(wk);
        if (idx <= LOCAL_LAST) {
            return "  stl w" + idx;
        }
        if (idx > 255) throw new IllegalArgumentException("local index too large for STLX: " + idx);
        return "  stlx " + idx;
    }


    @Override
    public String label(String name) {
        return name + ":";
    }

    @Override
    public String jmp(String target) {
        return "  j " + target;
    }

    @Override
    public String brIfZero(String target) {
        String skip = "__br_nz_" + Integer.toUnsignedString(System.identityHashCode(this)) + "_" + Integer.toUnsignedString(branchSeq++);
        return "  bnez " + skip + "\n  j " + target + "\n" + skip + ":";
    }

    @Override
    public String pop1() {
        return "  drop1";
    }
}


