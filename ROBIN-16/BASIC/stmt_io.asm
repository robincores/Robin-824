; I/O statements.

; helper: emit string range [w2, w3)
stmt_emit_string_range:
  stl w14
  ldl w2
  stl w11
  ldl w3
  stl w12
.Lsesr_loop:
  ldl w11
  ldl w12
  beq .Lsesr_done
  ldl w11
  lu
  stl w1
  BIOS_CALL0 SYS_PUTC
  ldl w11
  inc
  stl w11
  bra .Lsesr_loop
.Lsesr_done:
  ldl w14
  jr

; PRINT <expr>
; PRINT <string-literal>
; PRINT <string-var>
; PRINT
stmt_exec_print:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  i0
  beq .Lsep_blank
  ldl w0
  u 34
  beq .Lsep_string
  ldl w0
  u TOKB_STR
  beq .Lsep_string

  ; string variable?
  ldl w10
  stl w13
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lsep_expr
  ldl w0
  stl w6
  ldl w1
  stl w7
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  beq .Lsep_strvar
  ldl w13
  stl w10
  bra .Lsep_expr
.Lsep_strvar:
  CALL stmt_require_eol
  ldl w0
  i1
  bne .Lsep_syntax
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL strvars_get_ptr
  ldl w0
  stl w2
  ldl w0
  ldl w1
  add
  stl w3
  CALL stmt_emit_string_range
  BIOS_CRLF
  u 1
  stl w0
  ldl w14
  jr

.Lsep_expr:
  ldl w13
  stl w10
  CALL expr_eval
  ldl w1
  i0
  beq .Lsep_syntax
  ldl w0
  stl w2
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsep_num_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsep_num_ok:
  ldl w2
  stl w0
  CALL stmt_print_i16
  BIOS_CRLF
  u 1
  stl w0
  ldl w14
  jr

.Lsep_blank:
  BIOS_CRLF
  u 1
  stl w0
  ldl w14
  jr

.Lsep_string:
  CALL stmt_scan_quoted_string
  ldl w0
  i1
  beq .Lsep_have_string
.Lsep_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsep_have_string:
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsep_str_done
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsep_str_done:
  CALL stmt_emit_string_range
  BIOS_CRLF
  u 1
  stl w0
  ldl w14
  jr

; REM <anything>
stmt_exec_rem:
  stl w14
  u 1
  stl w0
  ldl w14
  jr

; INPUT <ident>
stmt_exec_input:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsei_have_ident
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsei_have_ident:
  ldl w0
  stl w6
  ldl w1
  stl w7

  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsei_prompt
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsei_prompt:
  BIOS_PUTC 63
  BIOS_PUTC 32
  BIOS_READLINE LINE_BUF, LINE_MAX

  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  beq .Lsei_store_string

  i LINE_BUF
  stl w10
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i0
  beq .Lsei_syntax
  ldl w0
  stl w2

  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsei_store_num
.Lsei_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsei_store_num:
  ldl w6
  stl w0
  ldl w7
  stl w1
  ldl w2
  stl w2
  CALL vars_set
  u 1
  stl w0
  ldl w14
  jr

.Lsei_store_string:
  ldl w6
  stl w0
  ldl w7
  stl w1
  i LINE_BUF
  stl w2
  CALL strvars_set_z
  u 1
  stl w0
  ldl w14
  jr
