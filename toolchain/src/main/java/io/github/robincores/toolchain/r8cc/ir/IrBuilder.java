package io.github.robincores.toolchain.r8cc.ir;

import io.github.robincores.toolchain.r8cc.ast.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class IrBuilder {

    public IrProgram build(TranslationUnit tu) {
        List<IrGlobal> globals = new ArrayList<>();
        Map<String, GlobalInfo> globalMap = new LinkedHashMap<>();
        for (GlobalDecl gd : tu.globals()) {
            byte[] init = initBytes(gd.type(), gd.array(), gd.init());
            globals.add(new IrGlobal(gd.name(), init, gd.type().alignBytes(), false));
            globalMap.put(gd.name(), new GlobalInfo(gd.type(), gd.array(), gd.name()));
        }
        StringPool pool = new StringPool(globals, globalMap);
        List<IrFunction> fns = new ArrayList<>();
        for (FunctionDef fn : tu.functions()) fns.add(lowerFunction(fn, globalMap, pool));
        return new IrProgram(List.copyOf(globals), List.copyOf(fns));
    }

    private byte[] initBytes(CType type, ArraySpec array, Expr init) {
        int elem = type.sizeBytes();
        if (array != null) {
            if (init instanceof StringLit s) {
                if (type.base() != CType.Base.CHAR || type.ptrDepth() != 0) throw new IllegalArgumentException("String initializer only valid for char array");
                byte[] bytes = Arrays.copyOf(s.value().getBytes(StandardCharsets.UTF_8), s.value().getBytes(StandardCharsets.UTF_8).length + 1);
                int len = array.length() == null ? bytes.length : array.length();
                byte[] out = new byte[len];
                System.arraycopy(bytes, 0, out, 0, Math.min(len, bytes.length));
                return out;
            }
            int len = array.length() == null ? 0 : array.length();
            byte[] out = new byte[len * elem];
            if (init != null) {
                int v = evalConst(init);
                if (elem == 1) out[0] = (byte)(v & 0xFF);
                else { out[0]=(byte)(v&0xFF); if (out.length>1) out[1]=(byte)((v>>>8)&0xFF); }
            }
            return out;
        }
        int v = evalConst(init);
        return elem == 1 ? new byte[]{(byte)(v&0xFF)} : new byte[]{(byte)(v&0xFF),(byte)((v>>>8)&0xFF)};
    }

    private int evalConst(Expr e) {
        if (e == null) return 0;
        return switch (e) {
            case IntLit lit -> lit.value();
            case UnaryOp u -> switch (u.op()) { case "+" -> evalConst(u.expr()); case "-" -> -evalConst(u.expr()); default -> throw new IllegalArgumentException("Initializer must be integer constant"); };
            case BinOp b -> {
                int l = evalConst(b.left()), r = evalConst(b.right());
                yield switch (b.op()) { case "+" -> l+r; case "-" -> l-r; case "*" -> l*r; case "/" -> r==0?0:l/r; case "%" -> r==0?0:l%r; default -> throw new IllegalArgumentException("Initializer must be integer constant"); };
            }
            default -> throw new IllegalArgumentException("Initializer must be integer constant");
        };
    }

    private IrFunction lowerFunction(FunctionDef fn, Map<String, GlobalInfo> globals, StringPool pool) {
        Ctx ctx = new Ctx(fn.params(), globals, pool);
        List<IrInstr> code = new ArrayList<>();
        lowerStmt(fn.body(), ctx, code);
        if (code.isEmpty() || !(code.get(code.size()-1) instanceof IrInstr.Ret)) { code.add(new IrInstr.PushConst(0)); code.add(new IrInstr.Ret()); }
        return new IrFunction(fn.name(), fn.params().size(), ctx.frameBytes(), List.copyOf(code));
    }

    private void lowerStmt(Stmt s, Ctx ctx, List<IrInstr> out) {
        switch (s) {
            case BlockStmt b -> { for (Stmt st : b.stmts()) lowerStmt(st, ctx, out); }
            case DeclStmt d -> {
                LocalInfo li = ctx.declareLocal(d.type(), d.array(), d.name());
                if (d.array() != null) {
                    if (d.init() instanceof StringLit sl) {
                        byte[] bytes = Arrays.copyOf(sl.value().getBytes(StandardCharsets.UTF_8), sl.value().getBytes(StandardCharsets.UTF_8).length + 1);
                        int len = d.array().length() == null ? bytes.length : d.array().length();
                        for (int i = 0; i < Math.min(len, bytes.length); i++) {
                            out.add(new IrInstr.AddrLocal(li.offsetBytes + i));
                            out.add(new IrInstr.PushConst(bytes[i] & 0xFF));
                            out.add(new IrInstr.StoreIndirectKeep(1));
                            out.add(new IrInstr.Pop1());
                        }
                    }
                } else {
                    if (d.init() == null) out.add(new IrInstr.PushConst(0)); else lowerExpr(d.init(), ctx, out);
                    out.add(new IrInstr.StoreLocal(li.offsetBytes, li.type.sizeBytes()));
                }
            }
            case ReturnStmt r -> { if (r.expr()==null) out.add(new IrInstr.PushConst(0)); else lowerExpr(r.expr(), ctx, out); out.add(new IrInstr.Ret()); }
            case ExprStmt e -> { if (e.expr()!=null) { lowerExpr(e.expr(), ctx, out); out.add(new IrInstr.Pop1()); } }
            case IfStmt i -> {
                String elseLabel=ctx.newLabel("if_else"), endLabel=ctx.newLabel("if_end");
                lowerExpr(i.condition(), ctx, out); out.add(new IrInstr.BrIfZero(i.elseBranch()!=null?elseLabel:endLabel));
                lowerStmt(i.thenBranch(), ctx, out);
                if (i.elseBranch()!=null) { out.add(new IrInstr.Jmp(endLabel)); out.add(new IrInstr.Label(elseLabel)); lowerStmt(i.elseBranch(), ctx, out);} out.add(new IrInstr.Label(endLabel));
            }
            case WhileStmt w -> { String head=ctx.newLabel("while_head"), end=ctx.newLabel("while_end"); out.add(new IrInstr.Label(head)); lowerExpr(w.condition(), ctx, out); out.add(new IrInstr.BrIfZero(end)); ctx.pushLoop(end, head); lowerStmt(w.body(), ctx, out); ctx.popLoop(); out.add(new IrInstr.Jmp(head)); out.add(new IrInstr.Label(end)); }
            case ForStmt f -> {
                if (f.init()!=null) lowerStmt(f.init(), ctx, out);
                String head=ctx.newLabel("for_head"), post=ctx.newLabel("for_post"), end=ctx.newLabel("for_end");
                out.add(new IrInstr.Label(head));
                if (f.cond()!=null) { lowerExpr(f.cond(), ctx, out); out.add(new IrInstr.BrIfZero(end)); }
                ctx.pushLoop(end, post); lowerStmt(f.body(), ctx, out); ctx.popLoop();
                out.add(new IrInstr.Label(post));
                if (f.post()!=null) { lowerExpr(f.post(), ctx, out); out.add(new IrInstr.Pop1()); }
                out.add(new IrInstr.Jmp(head)); out.add(new IrInstr.Label(end));
            }
            case BreakStmt __ -> out.add(new IrInstr.Jmp(ctx.currentBreakTarget()));
            case ContinueStmt __ -> out.add(new IrInstr.Jmp(ctx.currentContinueTarget()));
            default -> throw new IllegalArgumentException("Unsupported stmt: "+s);
        }
    }

    private void lowerExpr(Expr e, Ctx ctx, List<IrInstr> out) {
        switch (e) {
            case IntLit lit -> out.add(new IrInstr.PushConst(lit.value()));
            case StringLit s -> out.add(new IrInstr.AddrGlobal(ctx.internString(s.value())));
            case IndexExpr idx -> {
                lowerAddressOfIndex(idx, ctx, out);
                out.add(new IrInstr.LoadIndirect(exprType(e, ctx).sizeBytes()));
            }
            case VarRef vr -> {
                ResolvedVar rv = ctx.resolve(vr.name());
                if (rv.array != null) {
                    switch (rv.kind) {
                        case LOCAL -> out.add(new IrInstr.AddrLocal(rv.offsetOrIndex));
                        case GLOBAL -> out.add(new IrInstr.AddrGlobal(rv.name));
                        case PARAM -> out.add(new IrInstr.LoadParam(rv.offsetOrIndex));
                    }
                } else {
                    switch (rv.kind) {
                        case LOCAL -> out.add(new IrInstr.LoadLocal(rv.offsetOrIndex, rv.type.sizeBytes()));
                        case PARAM -> out.add(new IrInstr.LoadParam(rv.offsetOrIndex));
                        case GLOBAL -> out.add(new IrInstr.LoadGlobal(rv.name, rv.type.sizeBytes()));
                    }
                }
            }
            case UnaryOp u -> {
                switch (u.op()) {
                    case "&" -> {
                        if (!(u.expr() instanceof VarRef vr)) throw new IllegalArgumentException("Address-of supports named locals/globals only");
                        ResolvedVar rv = ctx.resolve(vr.name());
                        switch (rv.kind) {
                            case LOCAL -> out.add(new IrInstr.AddrLocal(rv.offsetOrIndex));
                            case GLOBAL -> out.add(new IrInstr.AddrGlobal(rv.name));
                            case PARAM -> throw new IllegalArgumentException("Address-of parameters not supported yet: " + vr.name());
                        }
                    }
                    case "*" -> { lowerExpr(u.expr(), ctx, out); out.add(new IrInstr.LoadIndirect(exprType(e, ctx).sizeBytes())); }
                    default -> { lowerExpr(u.expr(), ctx, out); out.add(new IrInstr.Un(u.op())); }
                }
            }
            case BinOp b -> {
                if ("=".equals(b.op())) {
                    if (b.left() instanceof VarRef lhs) {
                        lowerExpr(b.right(), ctx, out);
                        ResolvedVar rv = ctx.resolve(lhs.name());
                        if (rv.array != null) throw new IllegalArgumentException("Cannot assign to array object: " + lhs.name());
                        switch (rv.kind) {
                            case LOCAL -> { out.add(new IrInstr.StoreLocal(rv.offsetOrIndex, rv.type.sizeBytes())); out.add(new IrInstr.LoadLocal(rv.offsetOrIndex, rv.type.sizeBytes())); }
                            case GLOBAL -> { out.add(new IrInstr.StoreGlobal(rv.name, rv.type.sizeBytes())); out.add(new IrInstr.LoadGlobal(rv.name, rv.type.sizeBytes())); }
                            case PARAM -> throw new IllegalArgumentException("Cannot assign to parameter yet: " + lhs.name());
                        }
                        return;
                    }
                    if (b.left() instanceof UnaryOp u && "*".equals(u.op())) {
                        CType pointee = exprType(u.expr(), ctx).deref();
                        lowerExpr(u.expr(), ctx, out); lowerExpr(b.right(), ctx, out); out.add(new IrInstr.StoreIndirectKeep(pointee.sizeBytes())); return;
                    }
                    if (b.left() instanceof IndexExpr idx) {
                        CType pointee = exprType(idx.base(), ctx).deref();
                        lowerAddressOfIndex(idx, ctx, out); lowerExpr(b.right(), ctx, out); out.add(new IrInstr.StoreIndirectKeep(pointee.sizeBytes())); return;
                    }
                    throw new IllegalArgumentException("Assignment lhs must be identifier, dereference, or index");
                }
                if ("&&".equals(b.op())) { lowerLogicalAnd(b, ctx, out); return; }
                if ("||".equals(b.op())) { lowerLogicalOr(b, ctx, out); return; }
                lowerExpr(b.left(), ctx, out); lowerExpr(b.right(), ctx, out); out.add(new IrInstr.Bin(b.op()));
            }
            case CallExpr c -> { for (Expr a : c.args()) lowerExpr(a, ctx, out); out.add(new IrInstr.Call(c.fn(), c.args().size())); }
            default -> throw new IllegalArgumentException("Unsupported expr: "+e);
        }
    }

    private void lowerAddressOfIndex(IndexExpr idx, Ctx ctx, List<IrInstr> out) {
        lowerExpr(idx.base(), ctx, out);
        lowerExpr(idx.index(), ctx, out);
        int elem = exprType(idx.base(), ctx).deref().sizeBytes();
        if (elem != 1) { out.add(new IrInstr.PushConst(elem)); out.add(new IrInstr.Bin("*")); }
        out.add(new IrInstr.Bin("+"));
    }

    private CType exprType(Expr e, Ctx ctx) {
        return switch (e) {
            case IntLit __ -> CType.INT;
            case StringLit __ -> new CType(CType.Base.CHAR, 1);
            case VarRef vr -> {
                ResolvedVar rv = ctx.resolve(vr.name());
                yield rv.array != null ? rv.type.addressOf() : rv.type;
            }
            case UnaryOp u -> switch (u.op()) {
                case "&" -> exprType(u.expr(), ctx).addressOf();
                case "*" -> exprType(u.expr(), ctx).deref();
                case "!", "+", "-" -> CType.INT;
                default -> CType.INT;
            };
            case IndexExpr idx -> exprType(idx.base(), ctx).deref();
            case BinOp b -> switch (b.op()) { case "=" -> exprType(b.left(), ctx); default -> CType.INT; };
            case CallExpr __ -> CType.INT;
        };
    }

    private void lowerLogicalAnd(BinOp b, Ctx ctx, List<IrInstr> out) {
        String falseLabel = ctx.newLabel("and_false"), endLabel = ctx.newLabel("and_end");
        lowerExpr(b.left(), ctx, out); out.add(new IrInstr.BrIfZero(falseLabel)); lowerExpr(b.right(), ctx, out); out.add(new IrInstr.BrIfZero(falseLabel));
        out.add(new IrInstr.PushConst(1)); out.add(new IrInstr.Jmp(endLabel)); out.add(new IrInstr.Label(falseLabel)); out.add(new IrInstr.PushConst(0)); out.add(new IrInstr.Label(endLabel));
    }
    private void lowerLogicalOr(BinOp b, Ctx ctx, List<IrInstr> out) {
        String rhsLabel = ctx.newLabel("or_rhs"), falseLabel = ctx.newLabel("or_false"), endLabel = ctx.newLabel("or_end");
        lowerExpr(b.left(), ctx, out); out.add(new IrInstr.BrIfZero(rhsLabel)); out.add(new IrInstr.PushConst(1)); out.add(new IrInstr.Jmp(endLabel)); out.add(new IrInstr.Label(rhsLabel));
        lowerExpr(b.right(), ctx, out); out.add(new IrInstr.BrIfZero(falseLabel)); out.add(new IrInstr.PushConst(1)); out.add(new IrInstr.Jmp(endLabel)); out.add(new IrInstr.Label(falseLabel)); out.add(new IrInstr.PushConst(0)); out.add(new IrInstr.Label(endLabel));
    }

    private record LocalInfo(CType type, ArraySpec array, int offsetBytes) {}
    private record GlobalInfo(CType type, ArraySpec array, String name) {}
    private enum VarKind { PARAM, LOCAL, GLOBAL }
    private record ResolvedVar(VarKind kind, int offsetOrIndex, String name, CType type, ArraySpec array) {}
    private record LoopLabels(String breakTarget, String continueTarget) {}

    private static final class StringPool {
        private final List<IrGlobal> globals; private final Map<String, GlobalInfo> globalMap; private final Map<String, String> pool = new LinkedHashMap<>(); private int nextId = 0;
        StringPool(List<IrGlobal> globals, Map<String, GlobalInfo> globalMap) { this.globals=globals; this.globalMap=globalMap; }
        String intern(String s) {
            String ex = pool.get(s); if (ex != null) return ex;
            String label = "__str_" + (nextId++);
            byte[] bytes = Arrays.copyOf(s.getBytes(StandardCharsets.UTF_8), s.getBytes(StandardCharsets.UTF_8).length + 1);
            globals.add(new IrGlobal(label, bytes, 1, true));
            globalMap.put(label, new GlobalInfo(new CType(CType.Base.CHAR,0), new ArraySpec(bytes.length), label));
            pool.put(s, label); return label;
        }
    }

    private static final class Ctx {
        private final List<FunctionDef.Param> params;
        private final Map<String, LocalInfo> locals = new LinkedHashMap<>();
        private final Map<String, GlobalInfo> globals;
        private final StringPool stringPool;
        private final Deque<LoopLabels> loops = new ArrayDeque<>();
        private int nextLocalBytes = 0; private int nextLabelId = 0;
        Ctx(List<FunctionDef.Param> ps, Map<String, GlobalInfo> globals, StringPool stringPool) { this.params=ps; this.globals=globals; this.stringPool=stringPool; }
        LocalInfo declareLocal(CType type, ArraySpec array, String name) {
            if (locals.containsKey(name)) throw new IllegalArgumentException("Duplicate local: " + name);
            int size = array != null ? type.sizeBytes() * (array.length() == null ? 0 : array.length()) : type.sizeBytes();
            int align = Math.max(1, type.alignBytes());
            nextLocalBytes = (nextLocalBytes + align - 1) / align * align;
            LocalInfo li = new LocalInfo(type, array, nextLocalBytes);
            locals.put(name, li);
            nextLocalBytes += size;
            return li;
        }
        int frameBytes() { return nextLocalBytes; }
        String newLabel(String stem) { return "__" + stem + "_" + (nextLabelId++); }
        void pushLoop(String b, String c) { loops.push(new LoopLabels(b,c)); }
        void popLoop() { loops.pop(); }
        String currentBreakTarget() { if (loops.isEmpty()) throw new IllegalArgumentException("break used outside loop"); return loops.peek().breakTarget(); }
        String currentContinueTarget() { if (loops.isEmpty()) throw new IllegalArgumentException("continue used outside loop"); return loops.peek().continueTarget(); }
        ResolvedVar resolve(String name) {
            LocalInfo li = locals.get(name); if (li != null) return new ResolvedVar(VarKind.LOCAL, li.offsetBytes, name, li.type, li.array);
            for (int i = 0; i < params.size(); i++) { var p = params.get(i); if (p.name().equals(name)) return new ResolvedVar(VarKind.PARAM, i, name, p.type(), p.array()); }
            GlobalInfo g = globals.get(name); if (g != null) return new ResolvedVar(VarKind.GLOBAL, -1, g.name, g.type, g.array);
            throw new IllegalArgumentException("Unknown identifier: " + name);
        }
        String internString(String s) { return stringPool.intern(s); }
    }
}
