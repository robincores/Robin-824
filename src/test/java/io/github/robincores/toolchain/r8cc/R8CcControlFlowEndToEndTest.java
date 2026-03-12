package io.github.robincores.toolchain.r8cc;

import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.cpu.R816;
import io.github.robincores.toolchain.r8as.Assembler;
import io.github.robincores.toolchain.r8as.AssemblerError;
import io.github.robincores.toolchain.r8as.AssemblerState;
import io.github.robincores.toolchain.r8cc.backend.r8.R816Dialect;
import io.github.robincores.toolchain.r8cc.backend.r8.R8AsmEmitter;
import io.github.robincores.toolchain.r8cc.ir.IrBuilder;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;


import io.github.robincores.toolchain.r8cc.ast.*;

import static org.junit.jupiter.api.Assertions.*;

public class R8CcControlFlowEndToEndTest {

    private static final class RamBus implements Bus {
        final byte[] mem;
        RamBus(int size) { this.mem = new byte[size]; }
        @Override public byte read8(int address) { return mem[address & (mem.length - 1)]; }
        @Override public void write8(int address, byte value) { mem[address & (mem.length - 1)] = value; }
        void load(int addr, List<Integer> bytes) {
            for (int i = 0; i < bytes.size(); i++) write8(addr + i, (byte) (bytes.get(i) & 0xFF));
        }
    }

    private static R8CParser.TranslationUnitContext parse(String src) {
        CharStream cs = CharStreams.fromString(src);
        R8CLexer lexer = new R8CLexer(cs);
        CommonTokenStream ts = new CommonTokenStream(lexer);
        R8CParser p = new R8CParser(ts);
        p.removeErrorListeners();
        p.addErrorListener(new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                              String msg, RecognitionException e) {
                throw new IllegalArgumentException("Parse error at " + line + ":" + (charPositionInLine + 1) + " - " + msg);
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
        return state.getErrors().stream().map(AssemblerError::format).collect(Collectors.joining("\n"));
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
        for (int i = 0; i < maxSteps && !cpu.isHalted(); i++) cpu.executeInstruction();
    }

    @Test
    void if_else_returns_expected_value() {
        String cSource = """
                int main() {
                  int x = 5;
                  if (x < 3) return 11; else return 22;
                }
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
        runUntilHalt(cpu, 256);
        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(22, cpu.a() & 0xFFFF);
    }

    @Test
    void while_loop_counts_up() {
        String cSource = """
                int main() {
                  int x = 0;
                  while (x < 3) x = x + 1;
                  return x;
                }
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
        runUntilHalt(cpu, 512);
        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(3, cpu.a() & 0xFFFF);
    }

    @Test
    void nested_if_in_loop_executes() {
        String cSource = """
                int main() {
                  int x = 0;
                  int y = 0;
                  while (x < 5) {
                    if (x == 2) y = y + 7; else y = y + 1;
                    x = x + 1;
                  }
                  return y;
                }
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
        runUntilHalt(cpu, 1024);
        assertTrue(cpu.isHalted(), "program should halt");
        assertEquals(11, cpu.a() & 0xFFFF);
    }
}
