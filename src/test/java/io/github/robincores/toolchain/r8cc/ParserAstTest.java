package io.github.robincores.toolchain.r8cc;

import io.github.robincores.toolchain.r8cc.ast.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ParserAstTest extends R8CcTestSupport {

    @Test
    void parses_minimal_program() {
        assertDoesNotThrow(() -> parse("int main() { return 42; }"));
    }

    @Test
    void parses_comments_and_multiple_functions() {
        String src = """
                // line comment
                int add(int a, int b) { return a + b; }
                int main() { /* block comment */ return add(3, 4); }
                """;
        ParseTree tree = assertDoesNotThrow(() -> parse(src));
        assertNotNull(tree);
    }

    @Test
    void rejects_syntax_errors() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("int main( { return 0; }"));
    }

    @Test
    void builds_translation_unit_with_expected_function_shapes() {
        TranslationUnit tu = ast("""
                int add(int a, int b) { return a + b; }
                int main() { return add(3, 4); }
                """);

        assertEquals(2, tu.functions().size());

        FunctionDef add = tu.functions().get(0);
        assertEquals("add", add.name());
        assertEquals(List.of("a", "b"), add.params().stream().map(FunctionDef.Param::name).toList());
        assertInstanceOf(BlockStmt.class, add.body());
    }

    @Test
    void declaration_assignment_and_return_build_expected_ast() {
        TranslationUnit tu = ast("""
                int main() {
                  int x = 3;
                  x = x + 1;
                  return x;
                }
                """);

        FunctionDef fn = tu.functions().getFirst();
        BlockStmt body = (BlockStmt) fn.body();
        assertEquals(3, body.stmts().size());

        DeclStmt decl = (DeclStmt) body.stmts().get(0);
        assertEquals("x", decl.name());
        assertInstanceOf(IntLit.class, decl.init());
        assertEquals(3, ((IntLit) decl.init()).value());

        ExprStmt assignStmt = (ExprStmt) body.stmts().get(1);
        assertInstanceOf(BinOp.class, assignStmt.expr());
        BinOp assign = (BinOp) assignStmt.expr();
        assertEquals("=", assign.op());
        assertInstanceOf(VarRef.class, assign.left());
        assertEquals("x", ((VarRef) assign.left()).name());
        assertInstanceOf(BinOp.class, assign.right());

        ReturnStmt ret = (ReturnStmt) body.stmts().get(2);
        assertInstanceOf(VarRef.class, ret.expr());
        assertEquals("x", ((VarRef) ret.expr()).name());
    }

    @Test
    void precedence_and_unary_are_reflected_in_ast_shape() {
        TranslationUnit tu = ast("""
                int main() { return -a + b * 4; }
                """);

        FunctionDef fn = tu.functions().getFirst();
        BlockStmt body = (BlockStmt) fn.body();
        ReturnStmt ret = (ReturnStmt) body.stmts().getFirst();
        BinOp plus = (BinOp) ret.expr();

        assertEquals("+", plus.op());
        assertInstanceOf(UnaryOp.class, plus.left());
        assertEquals("-", ((UnaryOp) plus.left()).op());

        assertInstanceOf(BinOp.class, plus.right());
        BinOp mul = (BinOp) plus.right();
        assertEquals("*", mul.op());
        assertInstanceOf(VarRef.class, mul.left());
        assertInstanceOf(IntLit.class, mul.right());
        assertEquals(4, ((IntLit) mul.right()).value());
    }

    @Test
    void call_expression_collects_all_arguments() {
        TranslationUnit tu = ast("""
                int main() { return sum(1, 2, 3, 4, 5); }
                """);

        FunctionDef fn = tu.functions().getFirst();
        BlockStmt body = (BlockStmt) fn.body();
        ReturnStmt ret = (ReturnStmt) body.stmts().getFirst();
        CallExpr call = (CallExpr) ret.expr();

        assertEquals("sum", call.fn());
        assertEquals(5, call.args().size());
        assertEquals(1, ((IntLit) call.args().get(0)).value());
        assertEquals(5, ((IntLit) call.args().get(4)).value());
    }
}
