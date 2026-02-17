package io.github.robincores.toolchain.r8cc.ir;

public final class IrPrinter {

    public String print(IrProgram p) {
        StringBuilder sb = new StringBuilder();
        for (IrFunction f : p.functions()) {
            sb.append("FUNC ").append(f.name())
                    .append(" (params=").append(f.paramCount()).append(")\n");
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
            case IrInstr.Bin x -> "BIN " + x.op();
            case IrInstr.Un x -> "UN " + x.op();
            case IrInstr.Call x -> "CALL " + x.name() + " " + x.argc();
            case IrInstr.Ret __ -> "RET";
            case IrInstr.LoadLocal x  -> "LOAD_LOCAL w" + x.wk();
            case IrInstr.StoreLocal x -> "STORE_LOCAL w" + x.wk();
            case IrInstr.Pop1 __      -> "POP1";
        };
    }
}
