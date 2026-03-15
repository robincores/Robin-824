package io.github.robincores.toolchain.r8cc.backend.r8;

public final class R816Dialect implements R8AsmDialect {

    private static final String SCR  = "w10";
    private static final String SCR2 = "w11";
    private static final int WORD_BYTES = 2;

    private int currentExtraParamSlots = 0;
    private int currentFrameBytes = 0;
    private int branchSeq = 0;

    @Override
    public String preamble() {
        return """
.arch r816
.once

; r8cc R816 asm
; ABI v0.1:
;   w15 = SP
;   w14 = LR
;   w0..w3 = arg0..arg3, return in w0
;   args 4+ passed on memory stack
;
; Locals are memory-backed (frame at [SP .. SP+frameBytes)).

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

    @Override
    public String funcLabel(String name) {
        return name + ":";
    }

    @Override
    public String funcPrologue(int paramCount, int frameBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("  stl w14\n");

        currentExtraParamSlots = Math.max(0, paramCount - 4);
        currentFrameBytes = Math.max(0, frameBytes);

        if (currentExtraParamSlots > 0) {
            for (int i = 0; i < currentExtraParamSlots; i++) {
                int slot = 4 + i;
                sb.append("  pop\n");
                sb.append("  stl w").append(slot).append("\n");
            }
        }

        if (currentFrameBytes > 0) {
            sb.append("  ldl sp\n");
            sb.append(pushConst(currentFrameBytes)).append("\n");
            sb.append("  sub\n");
            sb.append("  stl sp");
        }

        return sb.toString().stripTrailing();
    }

    @Override
    public String pushConst(int value) {
        if (value >= -128 && value <= 127) return "  b " + value;
        if (value >= 0 && value <= 255) return "  u " + value;
        int v = value & 0xFFFF;
        return String.format("  i 0x%04X", v);
    }

    @Override
    public String loadParam(int index) {
        if (index < 0) throw new IllegalArgumentException("param index < 0");
        if (index <= 3) return "  ldl w" + index;
        return "  ldl w" + (4 + (index - 4));
    }

    @Override
    public String loadGlobal(String name, int sizeBytes) {
        return "  i " + name + "\n" + (sizeBytes == 1 ? "  lu" : "  ld");
    }

    @Override
    public String storeGlobal(String name, int sizeBytes) {
        return "  stl " + SCR + "\n"
                + "  i " + name + "\n"
                + "  ldl " + SCR + "\n"
                + (sizeBytes == 1 ? "  sb" : "  st");
    }

    @Override
    public String addrGlobal(String name) {
        return "  i " + name;
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
            case "+" -> "";
            case "!" -> "  u 0\n  seq";
            default -> throw new IllegalArgumentException("R816 UN op not supported yet: " + op);
        };
    }

    @Override
    public String call(String name, int argc) {
        if (argc < 0) throw new IllegalArgumentException("argc < 0");

        StringBuilder sb = new StringBuilder();

        for (int i = argc - 1; i >= 4; i--) {
            sb.append("  push\n");
        }

        int regArgs = Math.min(argc, 4);
        for (int i = regArgs - 1; i >= 0; i--) {
            sb.append("  stl w").append(i).append("\n");
        }

        sb.append("  CALL ").append(name).append("\n");
        sb.append("  ldl w0");
        return sb.toString();
    }

    @Override
    public String ret(int frameBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("  stl w0\n");

        if (frameBytes > 0) {
            sb.append("  ldl sp\n");
            sb.append(pushConst(frameBytes)).append("\n");
            sb.append("  add\n");
            sb.append("  stl sp\n");
        }

        sb.append("  RET");
        return sb.toString();
    }

    private String addrFromSpOffset(int byteOffset) {
        if (byteOffset == 0) return "  ldl sp";
        return "  ldl sp\n" + pushConst(byteOffset) + "\n  add";
    }

    @Override
    public String addrLocal(int offsetBytes) {
        if (offsetBytes < 0) throw new IllegalArgumentException("local offset < 0");
        return addrFromSpOffset(offsetBytes);
    }

    @Override
    public String loadLocal(int offsetBytes, int sizeBytes) {
        return addrLocal(offsetBytes) + "\n" + (sizeBytes == 1 ? "  lu" : "  ld");
    }

    @Override
    public String storeLocal(int offsetBytes, int sizeBytes) {
        return "  stl " + SCR + "\n"
                + addrLocal(offsetBytes) + "\n"
                + "  ldl " + SCR + "\n"
                + (sizeBytes == 1 ? "  sb" : "  st");
    }

    @Override
    public String loadIndirect(int sizeBytes) {
        return sizeBytes == 1 ? "  lu" : "  ld";
    }

    @Override
    public String storeIndirectKeep(int sizeBytes) {
        return "  stl " + SCR + "\n"
                + "  stl " + SCR2 + "\n"
                + "  ldl " + SCR2 + "\n"
                + "  ldl " + SCR + "\n"
                + (sizeBytes == 1 ? "  sb\n" : "  st\n")
                + "  ldl " + SCR;
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
        String skip = "__br_nz_" + Integer.toUnsignedString(System.identityHashCode(this))
                + "_" + Integer.toUnsignedString(branchSeq++);
        return "  bnez " + skip + "\n  j " + target + "\n" + skip + ":";
    }

    @Override
    public String pop1() {
        return "  drop1";
    }
}