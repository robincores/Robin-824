package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ast.TranslationUnit;
import io.github.robincores.toolchain.r8cc.ir.IrBuilder;
import io.github.robincores.toolchain.r8cc.ir.IrProgram;
import org.antlr.v4.runtime.*;


import io.github.robincores.toolchain.r8cc.ast.*;

abstract class R8CcTestSupport {

    static R8CParser.TranslationUnitContext parse(String src) {
        CharStream cs = CharStreams.fromString(src);
        R8CLexer lexer = new R8CLexer(cs);
        CommonTokenStream ts = new CommonTokenStream(lexer);
        R8CParser p = new R8CParser(ts);

        p.removeErrorListeners();
        p.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer,
                                    Object offendingSymbol,
                                    int line,
                                    int charPositionInLine,
                                    String msg,
                                    RecognitionException e) {
                throw new IllegalArgumentException(
                        "Parse error at " + line + ":" + (charPositionInLine + 1) + " - " + msg);
            }
        });

        return p.translationUnit();
    }

    static TranslationUnit ast(String src) {
        return new AstBuilder().build(parse(src));
    }

    static IrProgram ir(String src) {
        return new IrBuilder().build(ast(src));
    }
}
