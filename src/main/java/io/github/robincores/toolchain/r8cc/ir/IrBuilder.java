package io.github.robincores.toolchain.r8cc.ir;

import io.github.robincores.toolchain.r8cc.ast.*;

import java.util.*;

public final class IrBuilder {

    public IrProgram build(TranslationUnit tu) {
        List<IrFunction> fns = new ArrayList<>();
        for (FunctionDef fn : tu.functions()) {
            fns.add(lowerFunction(fn));
        }
        return new IrProgram(List.copyOf(fns));
    }

    private IrFunction lowerFunction(FunctionDef fn) {
        Ctx ctx = new Ctx(fn.params());

        List<IrInstr> code = new ArrayList<>();
        lowerStmt(fn.body(), ctx, code);

        if (code.isEmpty() || !(code.get(code.size() - 1) instanceof IrInstr.Ret)) {
            code.add(new IrInstr.PushConst(0));
            code.add(new IrInstr.Ret());
        }

        return new IrFunction(fn.name(), fn.params().size(), List.copyOf(code));
    }

    private void lowerStmt(Stmt s, Ctx ctx, List<IrInstr> out) {
        switch (s) {
            case BlockStmt b -> {
                for (Stmt st : b.stmts()) lowerStmt(st, ctx, out);
            }
            case DeclStmt d -> {
                int slot = ctx.declareLocal(d.name());
                if (d.init() == null) out.add(new IrInstr.PushConst(0));
                else lowerExpr(d.init(), ctx, out);
                out.add(new IrInstr.StoreLocal(slot));
            }
            case ReturnStmt r -> {
                if (r.expr() == null) out.add(new IrInstr.PushConst(0));
                else lowerExpr(r.expr(), ctx, out);
                out.add(new IrInstr.Ret());
            }
            case ExprStmt e -> {
                if (e.expr() != null) {
                    lowerExpr(e.expr(), ctx, out);
                    out.add(new IrInstr.Pop1());
                }
            }
            case IfStmt i -> {
                String elseLabel = ctx.newLabel("if_else");
                String endLabel = ctx.newLabel("if_end");
                lowerExpr(i.condition(), ctx, out);
                out.add(new IrInstr.BrIfZero(i.elseBranch() != null ? elseLabel : endLabel));
                lowerStmt(i.thenBranch(), ctx, out);
                if (i.elseBranch() != null) {
                    out.add(new IrInstr.Jmp(endLabel));
                    out.add(new IrInstr.Label(elseLabel));
                    lowerStmt(i.elseBranch(), ctx, out);
                }
                out.add(new IrInstr.Label(endLabel));
            }
            case WhileStmt w -> {
                String head = ctx.newLabel("while_head");
                String end = ctx.newLabel("while_end");
                out.add(new IrInstr.Label(head));
                lowerExpr(w.condition(), ctx, out);
                out.add(new IrInstr.BrIfZero(end));
                lowerStmt(w.body(), ctx, out);
                out.add(new IrInstr.Jmp(head));
                out.add(new IrInstr.Label(end));
            }
            default -> throw new IllegalArgumentException("Unsupported stmt: " + s);
        }
    }

    private void lowerExpr(Expr e, Ctx ctx, List<IrInstr> out) {
        switch (e) {
            case IntLit lit -> out.add(new IrInstr.PushConst(lit.value()));
            case VarRef vr -> {
                ResolvedVar rv = ctx.resolve(vr.name());
                if (rv.kind == VarKind.LOCAL) out.add(new IrInstr.LoadLocal(rv.index));
                else out.add(new IrInstr.LoadParam(rv.index));
            }
            case UnaryOp u -> {
                lowerExpr(u.expr(), ctx, out);
                out.add(new IrInstr.Un(u.op()));
            }
            case BinOp b -> {
                if ("=".equals(b.op())) {
                    if (!(b.left() instanceof VarRef lhs))
                        throw new IllegalArgumentException("Assignment lhs must be identifier");
                    lowerExpr(b.right(), ctx, out);
                    ResolvedVar rv = ctx.resolve(lhs.name());
                    if (rv.kind != VarKind.LOCAL)
                        throw new IllegalArgumentException("Cannot assign to parameter yet: " + lhs.name());
                    out.add(new IrInstr.StoreLocal(rv.index));
                    out.add(new IrInstr.LoadLocal(rv.index));
                    return;
                }
                if ("&&".equals(b.op())) {
                    lowerShortCircuitAnd(b.left(), b.right(), ctx, out);
                    return;
                }
                if ("||".equals(b.op())) {
                    lowerShortCircuitOr(b.left(), b.right(), ctx, out);
                    return;
                }
                lowerExpr(b.left(), ctx, out);
                lowerExpr(b.right(), ctx, out);
                out.add(new IrInstr.Bin(b.op()));
            }
            case CallExpr c -> {
                for (Expr a : c.args()) lowerExpr(a, ctx, out);
                out.add(new IrInstr.Call(c.fn(), c.args().size()));
            }
            default -> throw new IllegalArgumentException("Unsupported expr: " + e);
        }
    }


    private void lowerShortCircuitAnd(Expr left, Expr right, Ctx ctx, List<IrInstr> out) {
        String falseLabel = ctx.newLabel("land_false");
        String endLabel = ctx.newLabel("land_end");

        lowerExpr(left, ctx, out);
        out.add(new IrInstr.BrIfZero(falseLabel));
        lowerExpr(right, ctx, out);
        out.add(new IrInstr.BrIfZero(falseLabel));
        out.add(new IrInstr.PushConst(1));
        out.add(new IrInstr.Jmp(endLabel));
        out.add(new IrInstr.Label(falseLabel));
        out.add(new IrInstr.PushConst(0));
        out.add(new IrInstr.Label(endLabel));
    }

    private void lowerShortCircuitOr(Expr left, Expr right, Ctx ctx, List<IrInstr> out) {
        String evalRight = ctx.newLabel("lor_rhs");
        String falseLabel = ctx.newLabel("lor_false");
        String endLabel = ctx.newLabel("lor_end");

        lowerExpr(left, ctx, out);
        out.add(new IrInstr.BrIfZero(evalRight));
        out.add(new IrInstr.PushConst(1));
        out.add(new IrInstr.Jmp(endLabel));

        out.add(new IrInstr.Label(evalRight));
        lowerExpr(right, ctx, out);
        out.add(new IrInstr.BrIfZero(falseLabel));
        out.add(new IrInstr.PushConst(1));
        out.add(new IrInstr.Jmp(endLabel));

        out.add(new IrInstr.Label(falseLabel));
        out.add(new IrInstr.PushConst(0));
        out.add(new IrInstr.Label(endLabel));
    }

    private enum VarKind { PARAM, LOCAL }

    private static final class ResolvedVar {
        final VarKind kind;
        final int index;
        ResolvedVar(VarKind kind, int index) { this.kind = kind; this.index = index; }
    }

    private static final class Ctx {
        private final Map<String, Integer> params = new HashMap<>();
        private final Map<String, Integer> locals = new HashMap<>();
        private int nextLocal = 0;
        private int nextLabelId = 0;

        Ctx(List<FunctionDef.Param> ps) {
            for (int i = 0; i < ps.size(); i++) params.put(ps.get(i).name(), i);
        }

        int declareLocal(String name) {
            if (locals.containsKey(name)) throw new IllegalArgumentException("Duplicate local: " + name);
            locals.put(name, nextLocal);
            return nextLocal++;
        }

        String newLabel(String stem) {
            return "__" + stem + "_" + (nextLabelId++);
        }

        ResolvedVar resolve(String name) {
            Integer l = locals.get(name);
            if (l != null) return new ResolvedVar(VarKind.LOCAL, l);
            Integer p = params.get(name);
            if (p != null) return new ResolvedVar(VarKind.PARAM, p);
            throw new IllegalArgumentException("Unknown identifier: " + name);
        }
    }
}
