package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainSupportTest {

    private static Object invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = Main.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void parseIntAuto_handles_decimal_hex_and_dollar_hex() throws Exception {
        assertEquals(42, invokePrivate("parseIntAuto", new Class[]{String.class}, "42"));
        assertEquals(0x2A, invokePrivate("parseIntAuto", new Class[]{String.class}, "0x2a"));
        assertEquals(0x2A, invokePrivate("parseIntAuto", new Class[]{String.class}, "$2A"));
        assertEquals(1000, invokePrivate("parseIntAuto", new Class[]{String.class}, "1_000"));
    }

    @Test
    void writeFlat_width8_writes_bytes_directly() throws Exception {
        AssemblerState st = new AssemblerState();
        st.output = List.of(0x11, 0x22, 0xFF);

        AssemblerSpec spec = new AssemblerSpec("toy8", 8, new LinkedHashMap<>(), List.of());
        Assembler as = new Assembler(spec);

        Path out = Files.createTempFile("r8as-flat8", ".bin");
        try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
            invokePrivate("writeFlat", new Class[]{AssemblerState.class, Assembler.class, FileOutputStream.class, String.class},
                    st, as, fos, "big");
        }

        assertArrayEquals(new byte[]{0x11, 0x22, (byte) 0xFF}, Files.readAllBytes(out));
    }

    @Test
    void writeFlat_width16_respects_little_endian() throws Exception {
        AssemblerState st = new AssemblerState();
        st.output = List.of(0x1234, 0xABCD);

        AssemblerSpec spec = new AssemblerSpec("toy16", 16, new LinkedHashMap<>(), List.of());
        Assembler as = new Assembler(spec);

        Path out = Files.createTempFile("r8as-flat16", ".bin");
        try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
            invokePrivate("writeFlat", new Class[]{AssemblerState.class, Assembler.class, FileOutputStream.class, String.class},
                    st, as, fos, "little");
        }

        assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xCD, (byte) 0xAB}, Files.readAllBytes(out));
    }

    @Test
    void placeSectionsIntoImage_uses_metadata_and_skips_bss() throws Exception {
        AssemblerState st = new AssemblerState();
        st.output = List.of(0x11, 0x22, 0x33);

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("bss", false);
        text.put("origin_words", 0);
        text.put("origin_bytes", 0);
        text.put("size_words", 2);
        text.put("size_bytes", 2);
        text.put("flat_start_words", 0);
        text.put("flat_start_bytes", 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bss", false);
        data.put("origin_words", 4);
        data.put("origin_bytes", 4);
        data.put("size_words", 1);
        data.put("size_bytes", 1);
        data.put("flat_start_words", 2);
        data.put("flat_start_bytes", 2);

        Map<String, Object> bss = new LinkedHashMap<>();
        bss.put("bss", true);
        bss.put("origin_words", 8);
        bss.put("origin_bytes", 8);
        bss.put("size_words", 4);
        bss.put("size_bytes", 4);

        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put(".text", text);
        sections.put(".data", data);
        sections.put(".bss", bss);

        Map<String, Object> inter = new LinkedHashMap<>();
        inter.put("section_order", List.of(".text", ".data"));
        inter.put("sections", sections);
        st.intermediate = inter;

        AssemblerSpec spec = new AssemblerSpec("toy8", 8, new LinkedHashMap<>(), List.of());
        Assembler as = new Assembler(spec);
        byte[] img = new byte[8];
        invokePrivate("placeSectionsIntoImage", new Class[]{AssemblerState.class, Assembler.class, byte[].class, String.class},
                st, as, img, "big");

        assertArrayEquals(new byte[]{0x11, 0x22, 0x00, 0x00, 0x33, 0x00, 0x00, 0x00}, img);
    }
}
