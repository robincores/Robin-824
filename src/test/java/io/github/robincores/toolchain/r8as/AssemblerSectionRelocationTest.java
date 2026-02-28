package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AssemblerSectionRelocationTest {

    @Test
    public void crossSectionSymbolResolvesToFlatAddress() {
        String src = """
      .arch r816
      .width 8

      .text
      .org 0x0000
      start:
        i data_label
        hlt

      .data
      data_label:
        .byte 0xAA
      """;

        Assembler a = new Assembler();
        AssemblerState st = a.assembleFile(src);

        // No errors
        assertNotNull(st);
        assertNotNull(st.getErrors());
        assertTrue(st.getErrors().isEmpty(), () -> {
            StringBuilder sb = new StringBuilder();
            for (AssemblerError e : st.getErrors()) sb.append(e.format()).append("\n");
            return sb.toString();
        });

        // Expect:
        // i imm16  => 0x8B <lo> <hi>   (little endian in r816.json)
        // hlt      => 0xFF
        // data     => 0xAA
        //
        // .text emits 4 bytes total: 0x8B, lo, hi, 0xFF
        // so .data starts at flat word address 4 => immediate must be 0x0004 (lo=0x04, hi=0x00)
        List<Integer> out = st.getOutput();
        assertEquals(List.of(
                0x8B, 0x04, 0x00,
                0xFF,
                0xAA
        ), out);
    }
}