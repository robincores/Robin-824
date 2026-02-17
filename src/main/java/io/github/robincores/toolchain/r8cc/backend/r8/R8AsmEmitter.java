package io.github.robincores.toolchain.r8cc.backend.r8;

import io.github.robincores.toolchain.r8cc.ir.*;

public final class R8AsmEmitter {

    private final R8AsmDialect d;

    public R8AsmEmitter(R8AsmDialect dialect) {
        this.d = dialect;
    }

    public String emit(IrProgram program) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.preamble()).append('\n');

        for (IrFunction fn : program.functions()) {
            sb.append("\n; ---- func ").append(fn.name())
                    .append(" (params=").append(fn.paramCount()).append(")\n");
            sb.append(d.funcLabel(fn.name())).append('\n');

            String pro = d.funcPrologue(fn.paramCount());
            if (pro != null && !pro.isBlank()) sb.append(pro).append('\n');

            for (IrInstr ins : fn.code()) {
                String out = switch (ins) {
                    case IrInstr.PushConst x -> d.pushConst(x.value());
                    case IrInstr.LoadParam x -> d.loadParam(x.index());
                    case IrInstr.Bin x -> d.bin(x.op());
                    case IrInstr.Un x -> d.un(x.op());
                    case IrInstr.Call x -> d.call(x.name(), x.argc());
                    case IrInstr.Ret __ -> d.ret();
                    case IrInstr.LoadLocal x  -> d.loadLocal(x.wk());
                    case IrInstr.StoreLocal x -> d.storeLocal(x.wk());
                    case IrInstr.Pop1 __      -> d.pop1();
                };

                if (out == null || out.isBlank()) continue;
                sb.append(out).append('\n');
            }
        }
        return sb.toString();
    }
}
