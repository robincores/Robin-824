package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ir.IrBuilder;
import io.github.robincores.toolchain.r8cc.ir.IrInstr;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import io.github.robincores.toolchain.r8cc.ast.*;

import static org.junit.jupiter.api.Assertions.*;

public class IrSmokeTest {

    private static R8CParser.TranslationUnitContext parse(String src) {
        var cs = CharStreams.fromString(src);
        var lexer = new R8CLexer(cs);
        var ts = new CommonTokenStream(lexer);
        var p = new R8CParser(ts);

        p.removeErrorListeners();
        p.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                throw new IllegalArgumentException("Parse error at " + line + ":" + (charPositionInLine + 1) + " - " + msg);
            }
        });

        return p.translationUnit();
    }

    @Test
    void ir_contains_add_for_a_plus_b() {
        String src = """
      int add(int a, int b) { return a + b; }
      int main() { return add(3, 4); }
      """;

        var cu = parse(src);
        var ast = new AstBuilder().build(cu);
        var ir = new IrBuilder().build(ast);

        var addFn = ir.functions().stream()
                .filter(f -> f.name().equals("add"))
                .findFirst()
                .orElseThrow();

        assertTrue(addFn.code().stream().anyMatch(i -> i instanceof IrInstr.Bin b && b.op().equals("+")),
                "Expected BIN + in add()");
    }
}
