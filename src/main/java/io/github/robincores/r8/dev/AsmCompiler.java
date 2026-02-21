package io.github.robincores.r8.dev;

import io.github.robincores.toolchain.r8as.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class AsmCompiler {
    private static final Pattern HAS_ARCH = Pattern.compile("(?m)^\\s*\\.arch\\s+\\S+\\s*$");

    private AsmCompiler() {
    }

    public static byte[] assembleToBytes(Path asmFile, String defaultArch) throws IOException {
        String text = Files.readString(asmFile, StandardCharsets.UTF_8);

        // If user forgot ".arch ...", inject a default (r816).
        if (!HAS_ARCH.matcher(text).find()) {
            text = ".arch " + defaultArch + "\n" + text;
        }

        Assembler a = new Assembler();

        // Nice DX: resolve .include relative to the source file
        Path dir = asmFile.toAbsolutePath().normalize().getParent();
        if (dir != null) a.addIncludePath(dir);

        AssemblerState st = a.assembleFile(text);

        if (!st.getErrors().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (AssemblerError e : st.getErrors()) {
                sb.append(asmFile).append("(").append(e.line).append(",").append(e.col).append("): ")
                        .append(e.msg).append("\n");
            }
            throw new IllegalArgumentException(sb.toString().trim());
        }

        byte[] out = new byte[st.getOutput().size()];
        for (int i = 0; i < st.getOutput().size(); i++) out[i] = (byte) (st.getOutput().get(i) & 0xFF);
        return out;
    }
}