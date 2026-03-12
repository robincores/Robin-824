package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ast.*;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


import io.github.robincores.toolchain.r8cc.ast.*;

public class R8CcControlFlowParserAstTest {

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
    void ast_contains_if_else_stmt() {
        String src = """
                int main() {
                  if (1) return 2; else return 3;
                }
                """;
        var ast = (TranslationUnit) new AstBuilder().build(parse(src));
        var fn = ast.functions().get(0);
        var body = (BlockStmt) fn.body();
        assertTrue(body.stmts().get(0) instanceof IfStmt);
        var ifs = (IfStmt) body.stmts().get(0);
        assertNotNull(ifs.elseBranch());
    }

    @Test
    void ast_contains_while_stmt() {
        String src = """
                int main() {
                  int x = 0;
                  while (x < 3) x = x + 1;
                  return x;
                }
                """;
        var ast = (TranslationUnit) new AstBuilder().build(parse(src));
        var fn = ast.functions().get(0);
        var body = (BlockStmt) fn.body();
        assertTrue(body.stmts().get(1) instanceof WhileStmt);
    }
}
