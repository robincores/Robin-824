package io.github.robincores.toolchain.r8cc.ir;

public final class IrPrinter {
    public String print(IrProgram p) {
        StringBuilder sb = new StringBuilder();
        for (IrGlobal g : p.globals()) {
            sb.append("GLOBAL ")
              .append(g.name())
              .append(" bytes=")
              .append(g.initBytes().length)
              .append('\n');
        }
        if (!p.globals().isEmpty()) {
            sb.append('\n');
        }
        for (IrFunction f : p.functions()) {
            sb.append("FUNC ")
              .append(f.name())
              .append(" (params=")
              .append(f.paramCount())
              .append(", frame=")
              .append(f.frameBytes())
              .append(")\n");
            int pc = 0;
            for (IrInstr ins : f.code()) {
                sb.append(String.format("  %04d  %s%n", pc++, fmt(ins)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String fmt(IrInstr i) {
        return switch (i) {
            case IrInstr.PushConst x -> "PUSH_CONST " + x.value();
            case IrInstr.LoadParam x -> "LOAD_PARAM " + x.index();
            case IrInstr.LoadGlobal x -> "LOAD_GLOBAL " + x.name() + "/" + x.sizeBytes();
            case IrInstr.StoreGlobal x -> "STORE_GLOBAL " + x.name() + "/" + x.sizeBytes();
            case IrInstr.AddrGlobal x -> "ADDR_GLOBAL " + x.name();
            case IrInstr.Bin x -> "BIN " + x.op();
            case IrInstr.Un x -> "UN " + x.op();
            case IrInstr.Call x -> "CALL " + x.name() + " " + x.argc();
            case IrInstr.Ret __ -> "RET";
            case IrInstr.LoadLocal x -> "LOAD_LOCAL @" + x.offsetBytes() + "/" + x.sizeBytes();
            case IrInstr.StoreLocal x -> "STORE_LOCAL @" + x.offsetBytes() + "/" + x.sizeBytes();
            case IrInstr.AddrLocal x -> "ADDR_LOCAL @" + x.offsetBytes();
            case IrInstr.LoadIndirect x -> "LOAD_INDIRECT/" + x.sizeBytes();
            case IrInstr.StoreIndirectKeep x -> "STORE_INDIRECT_KEEP/" + x.sizeBytes();
            case IrInstr.Pop1 __ -> "POP1";
            case IrInstr.Label x -> "LABEL " + x.name();
            case IrInstr.Jmp x -> "JMP " + x.target();
            case IrInstr.BrIfZero x -> "BR_IF_ZERO " + x.target();
        };
    }
}
