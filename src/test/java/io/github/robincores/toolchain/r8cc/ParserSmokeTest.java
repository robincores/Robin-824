package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.R8CLexer;
import io.github.robincores.toolchain.r8cc.R8CParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ParserSmokeTest {

    private static ParseTree parse(String src) {
        CharStream cs = CharStreams.fromString(src);
        R8CLexer lexer = new R8CLexer(cs);
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
    void parses_minimal_program() {
        assertDoesNotThrow(() -> parse("int main() { return 42; }"));
    }

    @Test
    void parses_precedence_program() {
        assertDoesNotThrow(() -> parse("int main() { return 2 + 3 * 4; }"));
    }

    @Test
    void parses_function_call() {
        String src = """
      int add(int a, int b) { return a + b; }
      int main() { return add(3, 4); }
      """;
        assertDoesNotThrow(() -> parse(src));
    }

    @Test
    void parses_comments() {
        String src = """
      // line comment
      int main() { /* block comment */ return 0; }
      """;
        assertDoesNotThrow(() -> parse(src));
    }

    @Test
    void rejects_syntax_errors() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("int main( { return 0; }"));
    }
}
