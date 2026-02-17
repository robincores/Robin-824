package io.github.robincores.toolchain.r8cc.backend.r8;

public final class R816Dialect implements R8AsmDialect {

    // Do NOT use w11..w14 (trap frame). Use w10 as temporary scratch.
    private static final String SCR = "w10";

    @Override
    public String preamble() {
        return """
      ; r8cc R816 asm (matches R8Core semantics)
      ; Calling convention v0:
      ;   - caller pushes args to memory stack (arg0 ends up on top)
      ;   - caller preserves w0 across nested calls by saving it on memory stack
      ;   - callee entry: AReg holds return PC from `jal`
      ;   - callee prologue: stl w0 ; pop+stl w1.. for params
      ;   - return: result in A, then `ldl w0 ; jr`
      """;
    }

    @Override public String funcLabel(String name) { return name + ":"; }

    @Override
    public String funcPrologue(int paramCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("  stl w0\n"); // save return address from A into w0

        // arg0 is on top (because caller pushes argc times from reg-stack)
        for (int i = 0; i < paramCount; i++) {
            sb.append("  pop\n");
            sb.append("  stl w").append(i + 1).append('\n'); // w1.. holds params
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String pushConst(int value) {
        int v = value & 0xFFFF;
        if (v == 0) return "  i0";
        if (v == 1) return "  i1";
        return String.format("  i #0x%04X", v);
    }

    @Override
    public String loadParam(int index) {
        return "  ldl w" + (index + 1);
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
            default -> throw new IllegalArgumentException("R816 BIN op not supported yet: " + op);
        };
    }

    @Override
    public String un(String op) {
        return switch (op) {
            case "-" -> "  neg";
            case "+" -> ""; // no-op
            // NOTE: '!' is logical-not in C, not bitwise invert; keep unsupported for now
            default -> throw new IllegalArgumentException("R816 UN op not supported yet: " + op);
        };
    }

    @Override
    public String call(String name, int argc) {
        if (argc > 3) throw new IllegalArgumentException("argc>3 not supported yet (needs spilling)");

        StringBuilder sb = new StringBuilder();

        // Save caller's w0 below args (nest-safe)
        sb.append("  ldl w0\n");
        sb.append("  push\n");

        // Spill args (argc pushes). With reg-stack A/B/C this yields arg0 on top.
        for (int i = 0; i < argc; i++) sb.append("  push\n");

        sb.append("  jal ").append(name).append('\n');

        // Preserve retval, restore w0 from memory stack, then restore retval
        sb.append("  stl ").append(SCR).append('\n'); // save retval
        sb.append("  pop\n");                         // pop saved w0 into A
        sb.append("  stl w0\n");                      // restore w0
        sb.append("  ldl ").append(SCR);              // reload retval into A

        return sb.toString().stripTrailing();
    }

    @Override
    public String ret() {
        return "  ldl w0\n  jr";
    }

    @Override public String loadLocal(int wk)  { return "  ldl w" + wk; }
    @Override public String storeLocal(int wk) { return "  stl w" + wk; }
    @Override public String pop1()             { return "  pop1"; }
}
