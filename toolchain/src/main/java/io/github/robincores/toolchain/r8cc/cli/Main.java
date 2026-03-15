package io.github.robincores.toolchain.r8cc.cli;

import io.github.robincores.toolchain.r8cc.R8CLexer;
import io.github.robincores.toolchain.r8cc.R8CParser;
import io.github.robincores.toolchain.r8cc.ast.AstBuilder;
import io.github.robincores.toolchain.r8cc.ast.TranslationUnit;
import io.github.robincores.toolchain.r8cc.backend.r8.R816Dialect;
import io.github.robincores.toolchain.r8cc.backend.r8.R8AsmEmitter;
import io.github.robincores.toolchain.r8cc.backend.r8.R8PseudoDialect;
import io.github.robincores.toolchain.r8cc.ir.IrBuilder;
import io.github.robincores.toolchain.r8cc.ir.IrPrinter;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;

import java.nio.file.Path;

public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }

        String mode = args[0];
        Path file = Path.of(args[1]);

        CharStream cs = CharStreams.fromPath(file);
        R8CLexer lexer = new R8CLexer(cs);

        switch (mode) {
            case "--tokens" -> dumpTokens(lexer);

            case "--parse" -> {
                var cu = parse(lexer);
                System.out.println(cu.toStringTree());
            }

            case "--ast" -> {
                var cu = parse(lexer);
                var ast = buildAst(cu);
                System.out.println(ast);
            }

            case "--ir" -> {
                var cu = parse(lexer);
                var ast = buildAst(cu);
                var ir = new IrBuilder().build(ast);
                System.out.print(new IrPrinter().print(ir));
            }

            case "--asm" -> {
                var cu = parse(lexer);
                var ast = buildAst(cu);
                var ir = new IrBuilder().build(ast);

                var asm = new R8AsmEmitter(new R8PseudoDialect()).emit(ir);
                System.out.print(asm);
            }

            case "--asm-r816" -> {
                var cu = parse(lexer);
                var ast = buildAst(cu);
                var ir = new IrBuilder().build(ast);

                var asm = new R8AsmEmitter(new R816Dialect()).emit(ir);
                System.out.print(asm);
            }

            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static TranslationUnit buildAst(R8CParser.TranslationUnitContext cu) {
        AstBuilder builder = new AstBuilder();
        return builder.build(cu);
    }

    private static void dumpTokens(R8CLexer lexer) {
        CommonTokenStream ts = new CommonTokenStream(lexer);
        ts.fill();

        Vocabulary v = lexer.getVocabulary();
        for (Token t : ts.getTokens()) {
            String name = v.getSymbolicName(t.getType());
            if (name == null) name = v.getDisplayName(t.getType());
            System.out.printf("%4d:%-3d  %-12s  %s%n",
                    t.getLine(), t.getCharPositionInLine() + 1, name, escape(t.getText()));
        }
    }

    private static R8CParser.TranslationUnitContext parse(R8CLexer lexer) {
        CommonTokenStream ts = new CommonTokenStream(lexer);
        R8CParser p = new R8CParser(ts);

        p.removeErrorListeners();
        p.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer,
                                    Object offendingSymbol,
                                    int line, int charPositionInLine,
                                    String msg, RecognitionException e) {
                throw new IllegalArgumentException(
                        "Parse error at " + line + ":" + (charPositionInLine + 1) + " - " + msg);
            }
        });

        return p.translationUnit();
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  r8cc --tokens   <file.c>");
        System.err.println("  r8cc --parse    <file.c>");
        System.err.println("  r8cc --ast      <file.c>");
        System.err.println("  r8cc --ir       <file.c>");
        System.err.println("  r8cc --asm      <file.c>");
        System.err.println("  r8cc --asm-r816 <file.c>");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}