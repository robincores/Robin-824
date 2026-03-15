package io.github.robincores.toolchain.r8cc.ast;

import io.github.robincores.toolchain.r8cc.R8CBaseVisitor;
import io.github.robincores.toolchain.r8cc.R8CParser;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;

public final class AstBuilder extends R8CBaseVisitor<Object> {

    public TranslationUnit build(R8CParser.TranslationUnitContext cu) {
        return (TranslationUnit) visitTranslationUnit(cu);
    }

    @Override
    public Object visitTranslationUnit(R8CParser.TranslationUnitContext ctx) {
        List<GlobalDecl> globals = new ArrayList<>();
        List<FunctionDef> functions = new ArrayList<>();
        for (var tl : ctx.topLevel()) {
            if (tl.functionDef() != null) functions.add((FunctionDef) visit(tl.functionDef()));
            else globals.add((GlobalDecl) visit(tl.globalDecl()));
        }
        return new TranslationUnit(List.copyOf(globals), List.copyOf(functions));
    }

    private CType toType(R8CParser.TypeSpecContext ctx) {
        return ctx.K_CHAR() != null ? CType.CHAR : CType.INT;
    }

    private CType applyDeclarator(CType base, R8CParser.DeclaratorContext dctx) {
        int ptrDepth = dctx.pointerPrefix() == null ? 0 : dctx.pointerPrefix().STAR().size();
        return new CType(base.base(), base.ptrDepth() + ptrDepth);
    }

    private ArraySpec arraySpec(R8CParser.DeclaratorContext dctx) {
        if (dctx.arraySuffix() == null) return null;
        var s = dctx.arraySuffix();
        return new ArraySpec(s.INT_LIT() == null ? null : parseInt(s.INT_LIT().getText()));
    }

    @Override
    public Expr visitInitializer(R8CParser.InitializerContext ctx) {
        if (ctx.STRING_LIT() != null) return new StringLit(unquote(ctx.STRING_LIT().getText()));
        return (Expr) visit(ctx.expr());
    }

    @Override
    public Object visitFunctionDef(R8CParser.FunctionDefContext ctx) {
        CType returnType = applyDeclarator(toType(ctx.typeSpec()), ctx.declarator());
        String name = ctx.declarator().IDENT().getText();
        List<FunctionDef.Param> params = new ArrayList<>();
        if (ctx.paramList() != null) {
            for (var p : ctx.paramList().param()) {
                CType type = applyDeclarator(toType(p.typeSpec()), p.declarator());
                ArraySpec arr = arraySpec(p.declarator());
                // array parameters decay to pointer-to-element
                if (arr != null) { type = type.addressOf(); arr = null; }
                params.add(new FunctionDef.Param(type, p.declarator().IDENT().getText(), arr));
            }
        }
        return new FunctionDef(returnType, name, List.copyOf(params), (Stmt) visit(ctx.compoundStmt()));
    }

    @Override
    public Object visitGlobalDecl(R8CParser.GlobalDeclContext ctx) {
        CType type = applyDeclarator(toType(ctx.typeSpec()), ctx.declarator());
        String name = ctx.declarator().IDENT().getText();
        ArraySpec arr = arraySpec(ctx.declarator());
        Expr init = ctx.initializer() == null ? null : visitInitializer(ctx.initializer());
        return new GlobalDecl(type, name, arr, init);
    }

    @Override
    public Object visitCompoundStmt(R8CParser.CompoundStmtContext ctx) {
        List<Stmt> items = new ArrayList<>();
        for (var bi : ctx.blockItem()) {
            if (bi.decl() != null) items.add((Stmt) visitDecl(bi.decl()));
            else items.add((Stmt) visit(bi.stmt()));
        }
        return new BlockStmt(List.copyOf(items));
    }

    @Override
    public Object visitDecl(R8CParser.DeclContext ctx) {
        CType type = applyDeclarator(toType(ctx.typeSpec()), ctx.declarator());
        String name = ctx.declarator().IDENT().getText();
        ArraySpec arr = arraySpec(ctx.declarator());
        Expr init = ctx.initializer() == null ? null : visitInitializer(ctx.initializer());
        return new DeclStmt(type, name, arr, init);
    }

    @Override public Object visitExprStmt(R8CParser.ExprStmtContext ctx) { return new ExprStmt(ctx.expr()==null?null:(Expr) visit(ctx.expr())); }
    @Override public Object visitReturnStmt(R8CParser.ReturnStmtContext ctx) { return new ReturnStmt(ctx.expr()==null?null:(Expr) visit(ctx.expr())); }
    @Override public Object visitIfStmt(R8CParser.IfStmtContext ctx) { return new IfStmt((Expr)visit(ctx.expr()), (Stmt)visit(ctx.stmt(0)), ctx.stmt().size()>1?(Stmt)visit(ctx.stmt(1)):null); }
    @Override public Object visitWhileStmt(R8CParser.WhileStmtContext ctx) { return new WhileStmt((Expr)visit(ctx.expr()), (Stmt)visit(ctx.stmt())); }
    @Override public Object visitBreakStmt(R8CParser.BreakStmtContext ctx) { return new BreakStmt(); }
    @Override public Object visitContinueStmt(R8CParser.ContinueStmtContext ctx) { return new ContinueStmt(); }

    @Override
    public Object visitForStmt(R8CParser.ForStmtContext ctx) {
        Stmt init = null;
        if (ctx.forInit() != null) {
            if (ctx.forInit().declNoSemi() != null) {
                var d = ctx.forInit().declNoSemi();
                CType type = applyDeclarator(toType(d.typeSpec()), d.declarator());
                String name = d.declarator().IDENT().getText();
                ArraySpec arr = arraySpec(d.declarator());
                Expr ie = d.initializer() == null ? null : visitInitializer(d.initializer());
                init = new DeclStmt(type, name, arr, ie);
            } else if (ctx.forInit().expr() != null) {
                init = new ExprStmt((Expr) visit(ctx.forInit().expr()));
            }
        }
        Expr cond = ctx.expr(0) == null ? null : (Expr) visit(ctx.expr(0));
        Expr post = ctx.expr(1) == null ? null : (Expr) visit(ctx.expr(1));
        return new ForStmt(init, cond, post, (Stmt) visit(ctx.stmt()));
    }

    @Override
    public Object visitPrimary(R8CParser.PrimaryContext ctx) {
        if (ctx.INT_LIT() != null) return new IntLit(parseInt(ctx.INT_LIT().getText()));
        if (ctx.STRING_LIT() != null) return new StringLit(unquote(ctx.STRING_LIT().getText()));
        if (ctx.IDENT() != null) return new VarRef(ctx.IDENT().getText());
        return visit(ctx.expr());
    }

    @Override
    public Object visitPostfix(R8CParser.PostfixContext ctx) {
        Expr base = (Expr) visit(ctx.primary());
        int exprIndex = 0;
        int argListIndex = 0;

        for (int i = 1; i < ctx.getChildCount();) {
            String text = ctx.getChild(i).getText();
            if ("(".equals(text)) {
                List<Expr> args = new ArrayList<>();
                if (argListIndex < ctx.argList().size()) {
                    var al = ctx.argList(argListIndex++);
                    for (var e : al.expr()) args.add((Expr) visit(e));
                    i += 4; // '(' argList ')' and move past them
                } else {
                    i += 2; // '(' ')' and move past them
                }
                if (!(base instanceof VarRef vr)) throw error(ctx, "Only simple function calls supported");
                base = new CallExpr(vr.name(), List.copyOf(args));
            } else if ("[".equals(text)) {
                Expr idx = (Expr) visit(ctx.expr(exprIndex++));
                base = new IndexExpr(base, idx);
                i += 3; // '[' expr ']'
            } else {
                i++;
            }
        }
        return base;
    }

    @Override
    public Object visitUnary(R8CParser.UnaryContext ctx) {
        if (ctx.postfix() != null) return visit(ctx.postfix());
        String op = ctx.getChild(0).getText();
        return new UnaryOp(op, (Expr) visit(ctx.unary()));
    }

    @Override public Object visitMultiplicative(R8CParser.MultiplicativeContext ctx) { return foldLeft(ctx.unary(), ctx); }
    @Override public Object visitAdditive(R8CParser.AdditiveContext ctx) { return foldLeft(ctx.multiplicative(), ctx); }
    @Override public Object visitRelational(R8CParser.RelationalContext ctx) { return foldLeft(ctx.additive(), ctx); }
    @Override public Object visitEquality(R8CParser.EqualityContext ctx) { return foldLeft(ctx.relational(), ctx); }
    @Override public Object visitLogicalAnd(R8CParser.LogicalAndContext ctx) { return foldLeft(ctx.equality(), ctx); }
    @Override public Object visitLogicalOr(R8CParser.LogicalOrContext ctx) { return foldLeft(ctx.logicalAnd(), ctx); }

    @Override
    public Object visitAssignment(R8CParser.AssignmentContext ctx) {
        Expr lhs = (Expr) visit(ctx.logicalOr());
        if (ctx.assignment() == null) return lhs;
        return new BinOp("=", lhs, (Expr) visit(ctx.assignment()));
    }

    private Expr foldLeft(List<? extends ParserRuleContext> nodes, ParserRuleContext parent) {
        Expr acc = (Expr) visit(nodes.get(0));
        int exprIndex = 1;
        for (int child = 1; child < parent.getChildCount(); child += 2) {
            String op = parent.getChild(child).getText();
            Expr rhs = (Expr) visit(nodes.get(exprIndex++));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    private int parseInt(String text) {
        return text.startsWith("0x") || text.startsWith("0X") ? Integer.parseInt(text.substring(2), 16) : Integer.parseInt(text);
    }

    private String unquote(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() - 1) {
                char n = s.charAt(++i);
                out.append(switch (n) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '0' -> '\0';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> n;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static IllegalArgumentException error(ParserRuleContext ctx, String msg) {
        return new IllegalArgumentException("AST error at " + ctx.getStart().getLine() + ":" + (ctx.getStart().getCharPositionInLine() + 1) + " - " + msg);
    }
}
