package io.github.robincores.toolchain.r8as;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.robincores.toolchain.r8as.AssemblerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class AssemblerTestSuite {

    @Nested
    class AliasesAndLabels {
        @Test
        void workspace_aliases_are_accepted() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    ldl w1
                    ldl r1
                    ldl sp
                    ldl w15
                    ldl @1
                    """);
            assertOutputEquals(st, 0x07, 0x07, 0x3F, 0x3F, 0x07);
        }

        @Test
        void dot_local_labels_can_be_referenced() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    i .Lafter
                    .Lafter:
                    hlt
                    """);
            assertEquals(4, st.getOutput().size());
            assertEquals(0xFF, st.getOutput().get(3) & 0xFF);
        }

        @Test
        void numeric_local_labels_forward_and_backward_are_supported() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    bra 1f
                    .byte 0xAA
                    1:
                    bra 1b
                    """);
            assertEquals(4, st.getOutput().size());
        }
    }

    @Nested
    class ExpressionsAndData {
        @Test
        void byte_data_accepts_expressions() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .byte 0x10+1, 2*3, (7+1), ~0 & 0x0F
                    """);
            assertOutputEquals(st, 0x11, 0x06, 0x08, 0x0F);
        }

        @Test
        void string_and_asciz_emit_expected_bytes() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .ascii "A\\n"
                    .asciz "B"
                    """);
            assertOutputEquals(st, 'A', '\n', 'B', 0);
        }

        @Test
        void word_and_dword_emit_little_endian_bytes_at_width8() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .word 0x1234
                    .dword 0x89ABCDEF
                    """);
            assertOutputEquals(st, 0x34, 0x12, 0xEF, 0xCD, 0xAB, 0x89);
        }

        @Test
        void org_align_and_len_affect_layout() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .byte 1
                    .org 4
                    .byte 2
                    .align 8
                    .len 12
                    """);
            assertEquals(12, st.getOutput().size());
            assertEquals(List.of(1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0), st.getOutput());
        }

        @Test
        void incbin_can_slice_binary_file() throws Exception {
            Path dir = Files.createTempDirectory("r8as-incbin");
            writeBinaryFile(dir, "data.bin", (byte) 0x10, (byte) 0x11, (byte) 0x12, (byte) 0x13);
            Path main = writeTextFile(dir, "main.asm", """
                    .arch r816
                    .incbin "data.bin", 1, 2
                    """);

            Assembler a = new Assembler();
            AssemblerState st = a.assemblePath(main);
            assertNoErrors(st);
            assertOutputEquals(st, 0x11, 0x12);
        }
    }

    @Nested
    class EquSetDefineAndUndef {
        @Test
        void define_equ_set_and_undef_work_together() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .define FOO 3
                    .set BAR, FOO+1
                    .set BAR, BAR+1
                    .byte BAR
                    .undef BAR
                    .ifdef BAR
                      .byte 0xEE
                    .else
                      .byte 0x55
                    .endif
                    """);
            assertOutputEquals(st, 5, 0x55);
        }

        @Test
        void equ_redefinition_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .equ x, 1
                    .equ x, 2
                    """);
            assertHasErrorContaining(st, "already defined");
        }
    }

    @Nested
    class FixupsAndExpressions {
        @Test
        void forward_expression_in_instruction_is_patched() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    i target+2
                    target:
                    .byte 0xAA
                    """);
            assertOutputEquals(st, 0x43, 0x05, 0x00, 0xAA);
        }

        @Test
        void forward_expression_in_data_is_patched() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .byte target+1
                    target:
                    .byte 0xAA
                    """);
            assertOutputEquals(st, 0x01, 0xAA);
        }

        @Test
        void assemble_line_by_line_api_still_works() {
            Assembler a = new Assembler();
            a.assemble(".arch r816");
            a.assemble("b 7");
            a.assemble("hlt");
            AssemblerState st = a.finish();
            assertNoErrors(st);
            assertOutputEquals(st, 0x02, 0x07, 0xFF);
        }
    }

    @Nested
    class Macros {
        @Test
        void macro_expands_named_and_positional_parameters() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .macro EMIT a,b,c
                      .byte \\a, \\2, \\c
                    .endm
                    EMIT 1,2,3
                    """);
            assertOutputEquals(st, 1, 2, 3);
        }

        @Test
        void macro_local_unique_suffix_supports_multiple_expansions() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .macro M v
                    L\\@:
                      .byte \\v
                    .endm
                    M 1
                    M 2
                    """);
            assertOutputEquals(st, 1, 2);
        }

        @Test
        void macro_arity_mismatch_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .macro ONE a,b
                      .byte \\a, \\b
                    .endm
                    ONE 1
                    """);
            assertHasErrorContaining(st, "expects 2 args, got 1");
        }

        @Test
        void macro_recursion_is_detected() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .macro A x
                      A \\x
                    .endm
                    A 1
                    """);
            assertHasErrorContaining(st, "macro recursion detected");
        }
    }

    @Nested
    class IncludesModulesAndOnce {
        @Test
        void include_resolves_relative_to_the_current_source_file() throws Exception {
            Path dir = Files.createTempDirectory("r8as-inc-rel");
            writeTextFile(dir, "defs.asm", ".byte 7\n");
            Path main = writeTextFile(dir, "main.asm", """
                    .arch r816
                    .include "defs.asm"
                    .byte 8
                    """);

            AssemblerState st = new Assembler().assemblePath(main);
            assertNoErrors(st);
            assertOutputEquals(st, 7, 8);
        }

        @Test
        void include_search_path_and_module_search_path_work() throws Exception {
            Path dir = Files.createTempDirectory("r8as-incpath");
            Path inc = Files.createDirectories(dir.resolve("inc"));
            Path modules = Files.createDirectories(dir.resolve("mods"));
            writeTextFile(inc, "defs.asm", ".byte 9\n");
            writeTextFile(modules, "m.asm", ".byte 10\n");
            Path main = writeTextFile(dir, "main.asm", """
                    .arch r816
                    .include "defs.asm"
                    .module "m.asm"
                    .byte 11
                    """);

            Assembler a = new Assembler().addIncludePath(inc).addIncludePath(modules);
            AssemblerState st = a.assemblePath(main);
            assertNoErrors(st);
            assertOutputEquals(st, 9, 10, 11);
        }

        @Test
        void once_skips_repeated_include_of_same_source() throws Exception {
            Path dir = Files.createTempDirectory("r8as-once");
            writeTextFile(dir, "defs.asm", """
                    .once
                    .byte 0x55
                    """);
            Path main = writeTextFile(dir, "main.asm", """
                    .arch r816
                    .include "defs.asm"
                    .include "defs.asm"
                    .byte 0x66
                    """);

            AssemblerState st = new Assembler().assemblePath(main);
            assertNoErrors(st);
            assertOutputEquals(st, 0x55, 0x66);
        }

        @Test
        void recursive_include_and_depth_limit_are_reported() throws Exception {
            Path dir = Files.createTempDirectory("r8as-rec");
            Path a = writeTextFile(dir, "a.asm", ".arch r816\n.include \"b.asm\"\n");
            writeTextFile(dir, "b.asm", ".include \"a.asm\"\n");

            AssemblerState rec = new Assembler().assemblePath(a);
            assertHasErrorContaining(rec, "Recursive include");

            Path c = writeTextFile(dir, "c.asm", ".arch r816\n.include \"d.asm\"\n");
            writeTextFile(dir, "d.asm", ".include \"e.asm\"\n");
            writeTextFile(dir, "e.asm", ".byte 1\n");
            AssemblerState depth = new Assembler().setMaxIncludeDepth(1).assemblePath(c);
            assertHasErrorContaining(depth, "Include depth exceeded");
        }
    }

    @Nested
    class ConditionalCompilation {
        @Test
        void if_ifdef_ifndef_elif_else_and_nested_blocks_work() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .define X 1
                    .if 0
                      .byte 0xAA
                    .elif X == 1
                      .byte 0x11
                      .ifdef __arch_r816
                        .ifndef MISSING
                          .byte 0x22
                        .endif
                      .endif
                    .else
                      .byte 0xBB
                    .endif
                    """);
            assertOutputEquals(st, 0x11, 0x22);
        }

        @Test
        void labels_in_skipped_branch_do_not_exist() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .if 0
                    skipLabel:
                      .byte 1
                    .endif
                    .ifdef skipLabel
                      .byte 0xEE
                    .else
                      .byte 0x33
                    .endif
                    """);
            assertOutputEquals(st, 0x33);
        }
    }

    @Nested
    class SectionsAndRelocation {
        @Test
        void cross_section_symbol_resolves_to_flat_address() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .text
                    .org 0x0000
                    start:
                      i data_label
                      hlt
                    .data
                    data_label:
                      .byte 0xAA
                    """);
            assertOutputEquals(st, 0x43, 0x04, 0x00, 0xFF, 0xAA);
        }

        @Test
        void bss_reserves_space_without_emitting_bytes_but_metadata_records_it() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .text
                    .byte 1
                    .bss
                    .org 0x20
                    .byte 0, 0, 0, 0
                    .text
                    .byte 2
                    """);
            assertOutputEquals(st, 1, 2);

            @SuppressWarnings("unchecked")
            Map<String, Object> inter = (Map<String, Object>) st.getIntermediate();
            @SuppressWarnings("unchecked")
            Map<String, Object> secs = (Map<String, Object>) inter.get("sections");
            @SuppressWarnings("unchecked")
            Map<String, Object> bss = (Map<String, Object>) secs.get(".bss");
            assertEquals(Boolean.TRUE, bss.get("bss"));
            assertEquals(4, ((Number) bss.get("size_words")).intValue());
        }

        @Test
        void section_name_can_be_custom_and_len_pads_that_section() {
            AssemblerState st = assembleRawOk("""
                    .arch r816
                    .section extra
                    .byte 1
                    .len 4
                    .text
                    .byte 2
                    """);
            assertEquals(List.of(2, 1, 0, 0, 0), st.getOutput());
        }
    }

    @Nested
    class Relaxation {
        @Test
        void relax_off_far_bra_errors() {
            AssemblerState st = new Assembler().assembleFile("""
                    .arch r816
                    start:
                      bra far
                    """ + manyBytes(200, 0) + """
                    far:
                      .byte 1
                    """);
            assertHasErrorContaining(st, "Use 'j'");
        }

        @Test
        void relax_on_far_bra_widens_to_j() {
            AssemblerState st = new Assembler().setRelaxBranches(true).assembleFile("""
                    .arch r816
                    start:
                      bra far
                    """ + manyBytes(200, 0) + """
                    far:
                      .byte 1
                    """);
            assertNoErrors(st);
            assertEquals(0x73, st.getOutput().get(0) & 0xFF);
            assertEquals(204, st.getOutput().size());
        }

        @Test
        void relax_on_far_beq_widens_to_inverted_skip_plus_j() {
            AssemblerState st = new Assembler().setRelaxBranches(true).assembleFile("""
                    .arch r816
                    start:
                      beq far
                    """ + manyBytes(200, 0) + """
                    far:
                      .byte 1
                    """);
            assertNoErrors(st);
            assertEquals(0x26, st.getOutput().get(0) & 0xFF);
            assertEquals(0x03, st.getOutput().get(1) & 0xFF);
            assertEquals(0x73, st.getOutput().get(2) & 0xFF);
            assertEquals(206, st.getOutput().size());
        }

        @Test
        void relax_on_very_far_bra_upgrades_to_absolute_i_jr() {
            AssemblerState st = new Assembler().setRelaxBranches(true).setRelaxMaxPasses(20).assembleFile("""
                    .arch r816
                    start:
                      bra far
                      hlt
                    .org 0x9000
                    far:
                      .byte 1
                    """);
            assertNoErrors(st);
            assertEquals(0x43, st.getOutput().get(0) & 0xFF);
            assertEquals(0x00, st.getOutput().get(1) & 0xFF);
            assertEquals(0x90, st.getOutput().get(2) & 0xFF);
            assertEquals(0x42, st.getOutput().get(3) & 0xFF);
        }
    }

    @Nested
    class ErrorPaths {
        @Test
        void instruction_without_arch_is_rejected() {
            AssemblerState st = assembleRaw("b 1");
            assertHasErrorContaining(st, "Need to load .arch first");
        }

        @Test
        void bad_arch_file_is_reported() throws Exception {
            Path tmp = Files.createTempFile("bad-arch", ".json");
            Files.writeString(tmp, "{" );
            Assembler a = new Assembler();
            String err = a.loadArch(tmp.toString());
            assertNotNull(err);
        }

        @Test
        void invalid_arch_schema_is_reported() throws Exception {
            Path tmp = Files.createTempFile("bad-schema-arch", ".json");
            Files.writeString(tmp, "{\"name\":\"bad\",\"width\":8,\"vars\":{},\"rules\":[{\"fmt\":\"nop\",\"bits\":[false]}]}");
            Assembler a = new Assembler();
            String err = a.loadArch(tmp.toString());
            assertNotNull(err);
            assertTrue(err.contains("Invalid arch spec"));
        }

        @Test
        void data_section_selector_usage_error_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .data 1
                    """);
            assertHasErrorContaining(st, ".data is a section selector only");
        }

        @Test
        void instruction_emission_in_bss_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .bss
                    hlt
                    """);
            assertHasErrorContaining(st, "Cannot emit instructions in .bss");
        }

        @Test
        void section_requires_name() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .section
                    """);
            assertHasErrorContaining(st, ".section requires a name");
        }

        @Test
        void align_zero_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .align 0
                    """);
            assertHasErrorContaining(st, "Invalid alignment value");
        }

        @Test
        void unsupported_width_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .width 12
                    """);
            assertHasErrorContaining(st, "Unsupported .width 12");
        }

        @Test
        void missing_incbin_file_is_reported() {
            AssemblerState st = assembleRaw("""
                    .arch r816
                    .incbin "missing.bin"
                    """);
            assertHasErrorContaining(st, "Cannot find/read binary");
        }

        @Test
        void orphan_macro_and_conditional_closers_are_reported() {
            assertHasErrorContaining(assembleRaw("""
                    .arch r816
                    .endm
                    """), ".endm without active .macro");
            assertHasErrorContaining(assembleRaw("""
                    .arch r816
                    .else
                    """), ".else without matching .if");
            assertHasErrorContaining(assembleRaw("""
                    .arch r816
                    .endif
                    """), ".endif without matching .if");
        }

        @Test
        void include_not_found_and_missing_file_path_errors_are_reported() throws Exception {
            AssemblerState missingInclude = assembleRaw("""
                    .arch r816
                    .include "nope.asm"
                    """);
            assertHasErrorContaining(missingInclude, "Cannot find");

            AssemblerState missingPath = new Assembler().assemblePath(Path.of("definitely-does-not-exist-123456.asm"));
            assertHasErrorContaining(missingPath, "Cannot read file");
        }

        @Test
        void incbin_offset_out_of_range_is_reported() throws Exception {
            Path dir = Files.createTempDirectory("r8as-incbin-err");
            writeBinaryFile(dir, "data.bin", (byte) 1, (byte) 2);
            Path main = writeTextFile(dir, "main.asm", """
                    .arch r816
                    .incbin "data.bin", 9
                    """);
            AssemblerState st = new Assembler().assemblePath(main);
            assertHasErrorContaining(st, "offset beyond end of file");
        }

        @Test
        void relax_on_near_bra_does_not_widen() {
            AssemblerState st = new Assembler().setRelaxBranches(true).assembleFile("""
            .arch r816
            start:
              bra near
            .byte 0
            near:
              .byte 1
            """);
            assertNoErrors(st);

            // Still a short BRA, not widened to J.
            assertEquals(0x30, st.getOutput().get(0) & 0xFF); // keep/change if your BRA opcode differs
            assertEquals(4, st.getOutput().size());
        }

        @Test
        void relax_on_exact_boundary_beq_stays_short() {
            AssemblerState st = new Assembler().setRelaxBranches(true).assembleFile("""
            .arch r816
            start:
              beq far
            """ + manyBytes(125, 0) + """
            far:
              .byte 1
            """);
            assertNoErrors(st);

            // Should remain a 2-byte short conditional branch, not inverse+J.
            assertNotEquals(0x73, st.getOutput().get(0) & 0xFF); // not a J at byte 0
            assertEquals(128, st.getOutput().size());
        }

        @Test
        void relax_on_backward_far_bra_widens() {
            AssemblerState st = new Assembler().setRelaxBranches(true).assembleFile("""
            .arch r816
            top:
            """ + manyBytes(200, 0) + """
              bra top
            """);
            assertNoErrors(st);

            int n = st.getOutput().size();
            assertEquals(0x73, st.getOutput().get(n - 3) & 0xFF); // widened tail BRA -> J
        }

        @Test
        void relax_on_very_far_backward_bra_upgrades_to_absolute_i_jr() {
            AssemblerState st = new Assembler().setRelaxBranches(true).setRelaxMaxPasses(20).assembleFile("""
            .arch r816
            top:
              hlt
            .org 0x9000
              bra top
            """);
            assertNoErrors(st);

            int n = st.getOutput().size();
            assertEquals(0x43, st.getOutput().get(n - 4) & 0xFF); // i top
            assertEquals(0x00, st.getOutput().get(n - 3) & 0xFF);
            assertEquals(0x00, st.getOutput().get(n - 2) & 0xFF);
            assertEquals(0x42, st.getOutput().get(n - 1) & 0xFF); // jr
        }

        @Test
        void relax_on_far_bne_widens_to_inverted_skip_plus_j() {
            AssemblerState st = new Assembler().setRelaxBranches(true).assembleFile("""
            .arch r816
            start:
              bne far
            """ + manyBytes(200, 0) + """
            far:
              .byte 1
            """);
            assertNoErrors(st);

            // Structure check only: widened conditional = short inverse branch + J.
            assertEquals(0x03, st.getOutput().get(1) & 0xFF); // short skip over J
            assertEquals(0x73, st.getOutput().get(2) & 0xFF); // J opcode
            assertEquals(206, st.getOutput().size());
        }
    }
}
