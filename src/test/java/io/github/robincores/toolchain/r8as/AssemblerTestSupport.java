package io.github.robincores.toolchain.r8as;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AssemblerTestSupport {

    private AssemblerTestSupport() {}

    static String diagnostics(AssemblerState st) {
        if (st == null) return "<null assembler state>";
        if (st.getErrors() == null || st.getErrors().isEmpty()) return "<no diagnostics>";
        StringBuilder sb = new StringBuilder();
        for (AssemblerError e : st.getErrors()) sb.append(e.format()).append("\n");
        return sb.toString();
    }

    static void assertNoErrors(AssemblerState st) {
        assertNotNull(st, "AssemblerState is null");
        if (st.getErrors() != null && !st.getErrors().isEmpty()) {
            fail(diagnostics(st));
        }
    }

    static void assertHasErrorContaining(AssemblerState st, String fragment) {
        assertNotNull(st, "AssemblerState is null");
        assertNotNull(fragment, "fragment");
        assertNotNull(st.getErrors(), "Assembler errors list is null");
        boolean found = st.getErrors().stream().map(AssemblerError::format).anyMatch(s -> s.contains(fragment));
        assertTrue(found, () -> "Expected error containing '" + fragment + "' but got:\n" + diagnostics(st));
    }

    static void assertOutputEquals(AssemblerState st, int... expected) {
        assertNotNull(st, "AssemblerState is null");
        assertNotNull(st.getOutput(), "output is null");
        assertEquals(expected.length, st.getOutput().size(), "output length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i] & 0xFF, st.getOutput().get(i) & 0xFF, "byte " + i);
        }
    }

    static AssemblerState assembleRaw(String source) {
        Assembler a = new Assembler();
        AssemblerState st = a.assembleFile(source);
        assertNotNull(st, "assembler returned null state");
        return st;
    }

    static AssemblerState assembleRawOk(String source) {
        AssemblerState st = assembleRaw(source);
        assertNoErrors(st);
        return st;
    }

    static String resolveR816ArchRef() {
        List<String> candidates = List.of(
                "r816",
                "r816.json",
                "src/main/resources/io/github/robincores/toolchain/r8as/r816.json",
                "src/main/resources/io/github/robincores/toolchain/r8as/arch/r816.json",
                "src/main/resources/arch/r816.json",
                "src/test/resources/io/github/robincores/toolchain/r8as/r816.json",
                "src/test/resources/io/github/robincores/toolchain/r8as/arch/r816.json",
                "src/test/resources/arch/r816.json"
        );
        for (String candidate : candidates) {
            Assembler probe = new Assembler();
            String err = probe.loadArch(candidate);
            if (err == null) return candidate;
        }
        fail("Could not resolve r816 architecture JSON from known locations");
        return null;
    }

    static Assembler newR816Assembler() {
        Assembler a = new Assembler();
        String archRef = resolveR816ArchRef();
        String err = a.loadArch(archRef);
        assertNull(err, () -> "loadArch failed for '" + archRef + "': " + err);
        return a;
    }

    static AssemblerState assembleR816Ok(String sourceWithoutArchDirective) {
        Assembler a = newR816Assembler();
        AssemblerState st = a.assembleFile(sourceWithoutArchDirective);
        assertNoErrors(st);
        return st;
    }

    static String manyBytes(int count, int value) {
        StringBuilder sb = new StringBuilder();
        int v = value & 0xFF;
        for (int i = 0; i < count; i++) {
            if (i % 16 == 0) sb.append(".byte ");
            sb.append(v);
            if (i % 16 == 15 || i == count - 1) sb.append("\n");
            else sb.append(", ");
        }
        return sb.toString();
    }

    static Path writeTextFile(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    static Path writeBinaryFile(Path dir, String name, byte... data) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
        return p;
    }
}
