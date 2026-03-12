package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ir.*;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


import io.github.robincores.toolchain.r8cc.ast.*;

public class R8CcControlFlowIrTest {

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
    void ir_contains_branching_for_if_else() {
        String src = """
                int main(int a) {
                  if (a < 0) return 1; else return 2;
                }
                """;
        var cu = parse(src);
        var ast = new AstBuilder().build(cu);
        var ir = new IrBuilder().build(ast);
        var fn = ir.functions().get(0);
        assertTrue(fn.code().stream().anyMatch(i -> i instanceof IrInstr.BrIfZero));
        assertTrue(fn.code().stream().anyMatch(i -> i instanceof IrInstr.Jmp));
        assertTrue(fn.code().stream().anyMatch(i -> i instanceof IrInstr.Label));
    }

    @Test
    void ir_contains_loop_labels_for_while() {
        String src = """
                int main() {
                  int x = 0;
                  while (x < 3) x = x + 1;
                  return x;
                }
                """;
        var cu = parse(src);
        var ast = new AstBuilder().build(cu);
        var ir = new IrBuilder().build(ast);
        var fn = ir.functions().get(0);
        long labels = fn.code().stream().filter(i -> i instanceof IrInstr.Label).count();
        assertTrue(labels >= 2);
        assertTrue(fn.code().stream().anyMatch(i -> i instanceof IrInstr.BrIfZero));
        assertTrue(fn.code().stream().anyMatch(i -> i instanceof IrInstr.Jmp));
    }
}
