; I/O statements.

; PRINT <expr>
; PRINT "literal"
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

  CALL expr_eval
  ldl w1
  i0
  beq .Lsep_syntax
  ldl w0
  push
  CALL stmt_require_eol
  ldl w0
  i1
  bne .Lsep_num_ok
  pop
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsep_num_ok:
  pop
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
  ; consume opening quote
  ldl w10
  inc
  stl w10
.Lsep_str_loop:
  CALL tok_peek
  ldl w0
  i0
  beq .Lsep_syntax
  ldl w0
  u 34
  beq .Lsep_str_close
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PUTC
  ldl w10
  inc
  stl w10
  bra .Lsep_str_loop

.Lsep_str_close:
  ldl w10
  inc
  stl w10
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsep_str_done
.Lsep_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsep_str_done:
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
  beq .Lsei_store
.Lsei_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsei_store:
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
