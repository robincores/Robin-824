package io.github.robincores.toolchain.r8cc.backend.r8;

import io.github.robincores.toolchain.r8cc.ir.*;

public final class R8AsmEmitter {
    private final R8AsmDialect d;
    public R8AsmEmitter(R8AsmDialect d) { this.d = d; }

    public String emit(IrProgram p) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.preamble()).append('\n');
        sb.append(".text\n");
        for (IrFunction fn : p.functions()) {
            sb.append("\n; ---- func ").append(fn.name()).append(" (params=").append(fn.paramCount()).append(")\n");
            sb.append(d.funcLabel(fn.name())).append('\n');
            String pro = d.funcPrologue(fn.paramCount(), fn.frameBytes());
            if (pro != null && !pro.isBlank()) sb.append(pro).append('\n');
            for (IrInstr ins : fn.code()) {
                String out = switch (ins) {
                    case IrInstr.PushConst x -> d.pushConst(x.value());
                    case IrInstr.LoadParam x -> d.loadParam(x.index());
                    case IrInstr.LoadGlobal x -> d.loadGlobal(x.name(), x.sizeBytes());
                    case IrInstr.StoreGlobal x -> d.storeGlobal(x.name(), x.sizeBytes());
                    case IrInstr.AddrGlobal x -> d.addrGlobal(x.name());
                    case IrInstr.Bin x -> d.bin(x.op());
                    case IrInstr.Un x -> d.un(x.op());
                    case IrInstr.Call x -> d.call(x.name(), x.argc());
                    case IrInstr.Ret __ -> d.ret(fn.frameBytes());
                    case IrInstr.LoadLocal x -> d.loadLocal(x.offsetBytes(), x.sizeBytes());
                    case IrInstr.StoreLocal x -> d.storeLocal(x.offsetBytes(), x.sizeBytes());
                    case IrInstr.AddrLocal x -> d.addrLocal(x.offsetBytes());
                    case IrInstr.LoadIndirect x -> d.loadIndirect(x.sizeBytes());
                    case IrInstr.StoreIndirectKeep x -> d.storeIndirectKeep(x.sizeBytes());
                    case IrInstr.Pop1 __ -> d.pop1();
                    case IrInstr.Label x -> d.label(x.name());
                    case IrInstr.Jmp x -> d.jmp(x.target());
                    case IrInstr.BrIfZero x -> d.brIfZero(x.target());
                };
                if (out == null || out.isBlank()) continue;
                sb.append(out).append('\n');
            }
        }
        if (!p.globals().isEmpty()) {
            sb.append("\n.data\n");
            for (IrGlobal g : p.globals()) {
                sb.append(g.name()).append(":\n");
                for (byte b : g.initBytes()) sb.append("  .byte ").append(b & 0xFF).append('\n');
            }
        }
        return sb.toString();
    }
}
