package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AssemblerIncludeTest {

    @Test
    void include_with_quotes_resolves_relative_to_including_file() throws Exception {
        Path dir = Files.createTempDirectory("r8as-inc");
        Path defs = dir.resolve("defs.asm");
        Path main = dir.resolve("main.asm");

        Files.writeString(defs, """
                .equ foo 0x10+1
                .data foo
                """);

        Files.writeString(main, """
                .arch r816
                .include "defs.asm"
                .data foo+1
                """);

        var as = new Assembler(null);
        AssemblerState st = as.assemblePath(main);

        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(0x11, 0x12), st.output);
    }

    @Test
    void include_can_use_search_paths() throws Exception {
        Path dir = Files.createTempDirectory("r8as-incpath");
        Path inc = Files.createDirectories(dir.resolve("inc"));
        Path defs = inc.resolve("defs.asm");
        Path main = dir.resolve("main.asm");

        Files.writeString(defs, """
                .data 7
                """);

        Files.writeString(main, """
                .arch r816
                .include "defs.asm"
                .data 8
                """);

        var as = new Assembler(null).addIncludePath(inc);
        AssemblerState st = as.assemblePath(main);

        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(7, 8), st.output);
    }

    @Test
    void include_nesting_works() throws Exception {
        Path dir = Files.createTempDirectory("r8as-incnest");
        Path defs2 = dir.resolve("defs2.asm");
        Path defs1 = dir.resolve("defs1.asm");
        Path main = dir.resolve("main.asm");

        Files.writeString(defs2, """
                .equ foo 3
                """);

        Files.writeString(defs1, """
                .include "defs2.asm"
                .data foo
                """);

        Files.writeString(main, """
                .arch r816
                .include "defs1.asm"
                .data foo+1
                """);

        var as = new Assembler(null);
        AssemblerState st = as.assemblePath(main);

        assertTrue(st.errors.isEmpty(), "Expected no assembler errors, got: " + st.errors);
        assertEquals(List.of(3, 4), st.output);
    }

    @Test
    void missing_include_is_a_clear_fatal_error() throws Exception {
        Path dir = Files.createTempDirectory("r8as-incmissing");
        Path main = dir.resolve("main.asm");

        Files.writeString(main, """
                .arch r816
                .include "nope.asm"
                .data 1
                """);

        var as = new Assembler(null);
        AssemblerState st = as.assemblePath(main);

        assertFalse(st.errors.isEmpty(), "Expected assembler errors");
        String msg = st.errors.getFirst().toString();
        assertTrue(msg.contains("Cannot find"), "Expected 'Cannot find' in message, got: " + msg);
    }

    @Test
    void recursive_include_is_detected() throws Exception {
        Path dir = Files.createTempDirectory("r8as-increc");
        Path a = dir.resolve("a.asm");
        Path b = dir.resolve("b.asm");

        Files.writeString(a, """
                .arch r816
                .include "b.asm"
                .data 1
                """);

        Files.writeString(b, """
                .include "a.asm"
                .data 2
                """);

        var as = new Assembler(null);
        AssemblerState st = as.assemblePath(a);

        assertFalse(st.errors.isEmpty(), "Expected assembler errors");
        String msg = st.errors.getFirst().toString();
        assertTrue(msg.contains("Recursive include"), "Expected recursion message, got: " + msg);
    }
}
