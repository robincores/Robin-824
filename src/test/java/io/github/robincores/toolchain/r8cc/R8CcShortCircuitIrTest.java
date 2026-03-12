package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ast.AstBuilder;
import io.github.robincores.toolchain.r8cc.ir.IrBuilder;
import io.github.robincores.toolchain.r8cc.ir.IrPrinter;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class R8CcShortCircuitIrTest {

    private static R8CParser.TranslationUnitContext parse(String src) {
        R8CLexer lexer = new R8CLexer(CharStreams.fromString(src));
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

    @Test
    void logical_and_lowers_with_branches_not_eager_binop() {
        String src = "int main(){ int x=0; return x && (x = 1); }";
        var ast = new AstBuilder().build(parse(src));
        var ir = new IrBuilder().build(ast);
        String s = new IrPrinter().print(ir);

        assertFalse(s.contains("BIN &&"), s);
        assertTrue(s.contains("BR_IF_ZERO"), s);
        assertTrue(s.contains("JMP"), s);
        assertTrue(s.contains("LABEL"), s);
    }

    @Test
    void logical_or_lowers_with_branches_not_eager_binop() {
        String src = "int main(){ int x=1; return x || (x = 0); }";
        var ast = new AstBuilder().build(parse(src));
        var ir = new IrBuilder().build(ast);
        String s = new IrPrinter().print(ir);

        assertFalse(s.contains("BIN ||"), s);
        assertTrue(s.contains("BR_IF_ZERO"), s);
        assertTrue(s.contains("JMP"), s);
        assertTrue(s.contains("LABEL"), s);
    }
}
