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
        List<FunctionDef> fns = new ArrayList<>();
        for (var f : ctx.functionDef()) {
            fns.add((FunctionDef) visit(f));
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

        Stmt body = (Stmt) visit(ctx.compoundStmt());
        return new FunctionDef(name, List.copyOf(params), body);
    }

    @Override
    public Object visitCompoundStmt(R8CParser.CompoundStmtContext ctx) {
        List<Stmt> items = new ArrayList<>();
        for (var bi : ctx.blockItem()) {
            if (bi.decl() != null) {
                items.add((Stmt) visit(bi.decl()));
            } else {
                items.add((Stmt) visit(bi.stmt()));
            }
        }
        return new BlockStmt(List.copyOf(items));
    }

    @Override
    public Object visitDecl(R8CParser.DeclContext ctx) {
        String name = ctx.IDENT().getText();
        Expr init = (ctx.expr() != null) ? (Expr) visit(ctx.expr()) : null;
        return new DeclStmt(name, init);
    }

    @Override
    public Object visitExprStmt(R8CParser.ExprStmtContext ctx) {
        Expr e = (ctx.expr() != null) ? (Expr) visit(ctx.expr()) : null;
        return new ExprStmt(e);
    }

    @Override
    public Object visitReturnStmt(R8CParser.ReturnStmtContext ctx) {
        Expr e = (ctx.expr() != null) ? (Expr) visit(ctx.expr()) : null;
        return new ReturnStmt(e);
    }

    @Override
    public Object visitIfStmt(R8CParser.IfStmtContext ctx) {
        Expr cond = (Expr) visit(ctx.expr());
        Stmt thenBranch = (Stmt) visit(ctx.stmt(0));
        Stmt elseBranch = (ctx.stmt().size() > 1) ? (Stmt) visit(ctx.stmt(1)) : null;
        return new IfStmt(cond, thenBranch, elseBranch);
    }

    @Override
    public Object visitWhileStmt(R8CParser.WhileStmtContext ctx) {
        Expr cond = (Expr) visit(ctx.expr());
        Stmt body = (Stmt) visit(ctx.stmt());
        return new WhileStmt(cond, body);
    }

    @Override
    public Object visitPrimary(R8CParser.PrimaryContext ctx) {
        if (ctx.INT_LIT() != null) {
            String text = ctx.INT_LIT().getText();
            int value = text.startsWith("0x") || text.startsWith("0X")
                    ? Integer.parseInt(text.substring(2), 16)
                    : Integer.parseInt(text);
            return new IntLit(value);
        }
        if (ctx.IDENT() != null) {
            return new VarRef(ctx.IDENT().getText());
        }
        return visit(ctx.expr());
    }

    @Override
    public Object visitPostfix(R8CParser.PostfixContext ctx) {
        Expr target = (Expr) visit(ctx.primary());

        int callCount = ctx.LPAREN().size();
        if (callCount == 0) return target;

        for (int i = 0; i < callCount; i++) {
            if (!(target instanceof VarRef vr)) {
                throw error(ctx, "Only simple function calls supported");
            }

            List<Expr> args = new ArrayList<>();
            if (ctx.argList(i) != null) {
                for (var e : ctx.argList(i).expr()) {
                    args.add((Expr) visit(e));
                }
            }
            target = new CallExpr(vr.name(), List.copyOf(args));
        }

        return target;
    }

    @Override
    public Object visitUnary(R8CParser.UnaryContext ctx) {
        if (ctx.postfix() != null) {
            return visit(ctx.postfix());
        }
        String op = ctx.getChild(0).getText();
        return new UnaryOp(op, (Expr) visit(ctx.unary()));
    }

    @Override
    public Object visitMultiplicative(R8CParser.MultiplicativeContext ctx) {
        Expr acc = (Expr) visit(ctx.unary(0));
        for (int i = 1; i < ctx.unary().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(ctx.unary(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    @Override
    public Object visitAdditive(R8CParser.AdditiveContext ctx) {
        Expr acc = (Expr) visit(ctx.multiplicative(0));
        for (int i = 1; i < ctx.multiplicative().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(ctx.multiplicative(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    @Override
    public Object visitRelational(R8CParser.RelationalContext ctx) {
        Expr acc = (Expr) visit(ctx.additive(0));
        for (int i = 1; i < ctx.additive().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(ctx.additive(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    @Override
    public Object visitEquality(R8CParser.EqualityContext ctx) {
        Expr acc = (Expr) visit(ctx.relational(0));
        for (int i = 1; i < ctx.relational().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(ctx.relational(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    @Override
    public Object visitLogicalAnd(R8CParser.LogicalAndContext ctx) {
        Expr acc = (Expr) visit(ctx.equality(0));
        for (int i = 1; i < ctx.equality().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(ctx.equality(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    @Override
    public Object visitLogicalOr(R8CParser.LogicalOrContext ctx) {
        Expr acc = (Expr) visit(ctx.logicalAnd(0));
        for (int i = 1; i < ctx.logicalAnd().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            Expr rhs = (Expr) visit(ctx.logicalAnd(i));
            acc = new BinOp(op, acc, rhs);
        }
        return acc;
    }

    @Override
    public Object visitAssignment(R8CParser.AssignmentContext ctx) {
        Expr lhs = (Expr) visit(ctx.logicalOr());
        if (ctx.assignment() == null) {
            return lhs;
        }
        Expr rhs = (Expr) visit(ctx.assignment());
        return new BinOp("=", lhs, rhs);
    }

    private static IllegalArgumentException error(ParserRuleContext ctx, String msg) {
        return new IllegalArgumentException(
                "AST error at " + ctx.getStart().getLine() + ":" + (ctx.getStart().getCharPositionInLine() + 1)
                        + " - " + msg
        );
    }
}