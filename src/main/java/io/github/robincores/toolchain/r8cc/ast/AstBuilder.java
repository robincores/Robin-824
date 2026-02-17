package io.github.robincores.toolchain.r8cc.ast;

import io.github.robincores.toolchain.r8cc.antlr.R8CBaseVisitor;
import io.github.robincores.toolchain.r8cc.antlr.R8CParser;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;

public final class AstBuilder extends R8CBaseVisitor<Object> {

    public TranslationUnit build(R8CParser.TranslationUnitContext cu) {
        return (TranslationUnit) visitTranslationUnit(cu);
    }

    @Override
    public Object visitTranslationUnit(R8CParser.TranslationUnitContext ctx) {
        List<FunctionDef> fns = new ArrayList<>();
        for (var f : ctx.functionDef()) {
            fns.add((FunctionDef) visitFunctionDef(f));
        }
        return new TranslationUnit(List.copyOf(fns));
    }

    @Override
    public Object visitFunctionDef(R8CParser.FunctionDefContext ctx) {
        String name = ctx.IDENT().getText();

        List<FunctionDef.Param> params = new ArrayList<>();
        if (ctx.paramList() != null) {
            for (var p : ctx.paramList().param()) {
                params.add(new FunctionDef.Param(p.IDENT().getText()));
            }
        }

        Stmt body = (Stmt) visitCompoundStmt(ctx.compoundStmt());
        return new FunctionDef(name, List.copyOf(params), body);
    }

    @Override
    public Object visitCompoundStmt(R8CParser.CompoundStmtContext ctx) {
        List<Stmt> items = new ArrayList<>();

        // NEW: compoundStmt: '{' blockItem* '}'
        for (var bi : ctx.blockItem()) {
            if (bi.decl() != null) {
                items.add((Stmt) visitDecl(bi.decl()));
            } else if (bi.stmt() != null) {
                items.add((Stmt) visit(bi.stmt()));
            } else {
                throw new IllegalStateException("Unknown blockItem: " + bi.getText());
            }
        }

        return new BlockStmt(List.copyOf(items));
    }

    // NEW: decl : typeSpec IDENT (EQ expr)? SEMI
    @Override
    public Object visitDecl(R8CParser.DeclContext ctx) {
        String name = ctx.IDENT().getText();
        Expr init = (ctx.expr() != null) ? (Expr) visit(ctx.expr()) : null;
        return new DeclStmt(name, init);
    }

    @Override
    public Object visitReturnStmt(R8CParser.ReturnStmtContext ctx) {
        Expr e = ctx.expr() == null ? null : (Expr) visit(ctx.expr());
        return new ReturnStmt(e);
    }

    @Override
    public Object visitExprStmt(R8CParser.ExprStmtContext ctx) {
        Expr e = ctx.expr() == null ? null : (Expr) visit(ctx.expr());
        return new ExprStmt(e);
    }

    // Expr pipeline:
    @Override
    public Object visitExpr(R8CParser.ExprContext ctx) {
        return visit(ctx.assignment());
    }

    @Override
    public Object visitAssignment(R8CParser.AssignmentContext ctx) {
        // assignment: logicalOr ( '=' assignment )?
        var left = (Expr) visit(ctx.logicalOr());
        if (ctx.EQ() == null) return left;

        var right = (Expr) visit(ctx.assignment());
        return new BinOp("=", left, right);
    }

    @Override
    public Object visitLogicalOr(R8CParser.LogicalOrContext ctx) {
        return foldLeftBinary(ctx, ctx.logicalAnd());
    }

    @Override
    public Object visitLogicalAnd(R8CParser.LogicalAndContext ctx) {
        return foldLeftBinary(ctx, ctx.equality());
    }

    @Override
    public Object visitEquality(R8CParser.EqualityContext ctx) {
        return foldLeftBinary(ctx, ctx.relational());
    }

    @Override
    public Object visitRelational(R8CParser.RelationalContext ctx) {
        return foldLeftBinary(ctx, ctx.additive());
    }

    @Override
    public Object visitAdditive(R8CParser.AdditiveContext ctx) {
        return foldLeftBinary(ctx, ctx.multiplicative());
    }

    @Override
    public Object visitMultiplicative(R8CParser.MultiplicativeContext ctx) {
        return foldLeftBinary(ctx, ctx.unary());
    }

    @Override
    public Object visitUnary(R8CParser.UnaryContext ctx) {
        if (ctx.postfix() != null) return visit(ctx.postfix());
        String op = ctx.getChild(0).getText();
        Expr rhs = (Expr) visit(ctx.unary());
        return new UnaryOp(op, rhs);
    }

    @Override
    public Object visitPostfix(R8CParser.PostfixContext ctx) {
        Expr base = (Expr) visit(ctx.primary());

        // postfix: primary (LPAREN argList? RPAREN)*
        // ANTLR will generate argList() as a List<ArgListContext> (one per call suffix, excluding nulls),
        // while the number of call suffixes is ctx.LPAREN().size().
        //
        // We'll walk the *children* and consume argList contexts only when present.
        int argListIdx = 0;

        for (int i = 1; i < ctx.getChildCount(); i++) {
            if (!(ctx.getChild(i).getText().equals("("))) continue;

            // Determine if this suffix has an argList by looking at the next child:
            // "(" [argList] ")"
            List<Expr> args = new ArrayList<>();

            // If next token isn't ")", then we have an argList
            if (i + 1 < ctx.getChildCount() && !ctx.getChild(i + 1).getText().equals(")")) {
                var a = ctx.argList(argListIdx++);
                for (var e : a.expr()) args.add((Expr) visit(e));
            }

            if (base instanceof VarRef vr) {
                base = new CallExpr(vr.name(), List.copyOf(args));
            } else {
                throw new IllegalArgumentException("Only simple calls supported for now (IDENT(...))");
            }
        }

        return base;
    }

    @Override
    public Object visitPrimary(R8CParser.PrimaryContext ctx) {
        if (ctx.INT_LIT() != null) {
            String t = ctx.INT_LIT().getText();
            int v = (t.startsWith("0x") || t.startsWith("0X"))
                    ? Integer.parseInt(t.substring(2), 16)
                    : Integer.parseInt(t);
            return new IntLit(v);
        }
        if (ctx.IDENT() != null) return new VarRef(ctx.IDENT().getText());
        return visit(ctx.expr());
    }

    private Expr foldLeftBinary(ParserRuleContext ctx, List<? extends org.antlr.v4.runtime.tree.ParseTree> terms) {
        Expr acc = (Expr) visit(terms.get(0));
        for (int i = 1; i < terms.size(); i++) {
            // children are: term (op term)*  => op is at index (2*i - 1)
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(terms.get(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }
}
