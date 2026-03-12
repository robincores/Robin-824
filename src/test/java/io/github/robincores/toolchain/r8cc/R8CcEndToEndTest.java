package io.github.robincores.toolchain.r8cc;

import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.toolchain.r8as.Assembler;
import io.github.robincores.toolchain.r8as.AssemblerError;
import io.github.robincores.toolchain.r8as.AssemblerState;
import io.github.robincores.toolchain.r8cc.backend.r8.R816Dialect;
import io.github.robincores.toolchain.r8cc.backend.r8.R8AsmEmitter;
import io.github.robincores.toolchain.r8cc.ir.IrBuilder;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


import io.github.robincores.toolchain.r8cc.ast.*;

public class R8CcEndToEndTest {

    private static final class RamBus implements Bus {
        final byte[] mem;

        RamBus(int size) {
            this.mem = new byte[size];
        }

        @Override
        public byte read8(int address) {
            return mem[address & (mem.length - 1)];
        }

        @Override
        public void write8(int address, byte value) {
            mem[address & (mem.length - 1)] = value;
        }

        void load(int addr, List<Integer> bytes) {
            for (int i = 0; i < bytes.size(); i++) {
                write8(addr + i, (byte) (bytes.get(i) & 0xFF));
            }
        }
    }

    private static R8CParser.TranslationUnitContext parse(String src) {
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

    private static String compileToR816Asm(String cSource) {
        var cu = parse(cSource);
        var ast = new AstBuilder().build(cu);
        var ir = new IrBuilder().build(ast);
        return new R8AsmEmitter(new R816Dialect()).emit(ir);
    }

    private static String insertHarness(String compiledAsm, String harnessAsm) {
        String marker = "\n; ---- func ";
        int idx = compiledAsm.indexOf(marker);
        assertTrue(idx >= 0, "Could not find first function marker in emitted asm:\n" + compiledAsm);

        return compiledAsm.substring(0, idx)
                + "\n; ---- e2e harness\n"
                + "__start:\n"
                + harnessAsm.stripTrailing()
                + "\n\n"
                + compiledAsm.substring(idx);
    }

    private static String diagnostics(AssemblerState state) {
        if (state == null) return "<null assembler state>";
        if (state.getErrors() == null || state.getErrors().isEmpty()) return "<no diagnostics>";
        return state.getErrors().stream()
                .map(AssemblerError::format)
                .collect(Collectors.joining("\n"));
    }

    private static AssemblerState assembleOk(String asmSource) {
        Assembler assembler = new Assembler();
        AssemblerState state = assembler.assembleFile(asmSource);
        assertNotNull(state, "assembler returned null state");
        assertTrue(state.getErrors() == null || state.getErrors().isEmpty(), diagnostics(state));
        return state;
    }

    private static R816 cpuFromState(AssemblerState state) {
        RamBus bus = new RamBus(1 << 16);
        bus.load(0, state.getOutput());
        return new R816(bus);
    }

    private static void runUntilHalt(R816 cpu, int maxSteps) {
        for (int i = 0; i < maxSteps && !cpu.isHalted(); i++) {
            cpu.executeInstruction();
        }
    }

    @Test
    void constant_return_main_runs_end_to_end() {
        String cSource = """
                int main() { return 42; }
                """;

        String harness = """
                  i 0xFF00
                  stl sp
                  jal main
                  ldl w0
                  hlt
                """;

        String asm = insertHarness(compileToR816Asm(cSource), harness);
        AssemblerState state = assembleOk(asm);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 64);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(42, cpu.a() & 0xFFFF, "main() should return 42");
    }

    @Test
    void direct_two_arg_call_uses_w0_and_w1() {
        String cSource = """
                int add(int a, int b) { return a + b; }
                """;

        String harness = """
                  i 0xFF00
                  stl sp
                  b 7
                  stl w0
                  b 5
                  stl w1
                  jal add
                  ldl w0
                  hlt
                """;

        String asm = insertHarness(compileToR816Asm(cSource), harness);
        AssemblerState state = assembleOk(asm);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 64);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(12, cpu.a() & 0xFFFF, "add(7,5) should return 12");
    }

    @Test
    void compiled_function_call_runs_end_to_end() {
        String cSource = """
                int twice(int x) { return x + x; }
                int main() { return twice(7); }
                """;

        String harness = """
                  i 0xFF00
                  stl sp
                  jal main
                  ldl w0
                  hlt
                """;

        String asm = insertHarness(compileToR816Asm(cSource), harness);
        AssemblerState state = assembleOk(asm);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 128);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(14, cpu.a() & 0xFFFF, "main() should return twice(7) = 14");
    }

    @Test
    void six_arg_function_with_local_uses_abi_correctly() {
        String cSource = """
                int pick(int a, int b, int c, int d, int e, int g) {
                    int x = e;
                    return x;
                }
                """;

        String harness = """
                  i 0xFF00
                  stl sp

                  ; extra args on memory stack, arg4 on top at callee entry
                  b 77
                  push
                  b 99
                  push

                  ; arg0..arg3 in w0..w3
                  b 1
                  stl w0
                  b 2
                  stl w1
                  b 3
                  stl w2
                  b 4
                  stl w3

                  jal pick
                  ldl w0
                  hlt
                """;

        String asm = insertHarness(compileToR816Asm(cSource), harness);
        AssemblerState state = assembleOk(asm);

        R816 cpu = cpuFromState(state);
        runUntilHalt(cpu, 128);

        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(99, cpu.a() & 0xFFFF, "pick(..., e=99, g=77) should return e");
    }
}