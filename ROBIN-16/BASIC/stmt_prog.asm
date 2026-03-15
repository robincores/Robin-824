; Program-management statements.


stmt_exec_dim:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsed_have
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsed_have:
  ldl w0
  stl w6
  ldl w1
  stl w7
  ; strings not valid for DIM in this pass
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  bne .Lsed_err
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 40
  beq .Lsed_lparen
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsed_lparen:
  ldl w10
  inc
  stl w10
  CALL expr_eval
  ldl w1
  i1
  beq .Lsed_idx_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsed_idx_ok:
  ldl w0
  stl w8
  ; reject negative dimension
  ldl w8
  i 0x8000
  and
  i0
  beq .Lsed_nonneg
.Lsed_err:
  CALL stmt_fail_dim
  ldl w14
  jr
.Lsed_nonneg:
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 41
  beq .Lsed_rparen
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsed_rparen:
  ldl w10
  inc
  stl w10
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsed_eol_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsed_eol_ok:
  ldl w6
  stl w0
  ldl w7
  stl w1
  ldl w8
  stl w2
  CALL arr_dim
  ldl w0
  i1
  beq .Lsed_ok
  CALL stmt_fail_dim
  ldl w14
  jr
.Lsed_ok:
  u 1
  stl w0
  ldl w14
  jr

stmt_exec_new:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsen_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsen_ok:
  CALL prog_new
  CALL vars_init
  u 1
  stl w0
  ldl w14
  jr

stmt_exec_list:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsel_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsel_ok:
  CALL prog_list
  u 1
  stl w0
  ldl w14
  jr

stmt_exec_run:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lser_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lser_ok:
  CALL vars_init
  CALL prog_run
  u 1
  stl w0
  ldl w14
  jr

stmt_exec_cls:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsec_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsec_ok:
  BIOS_CLS
  u 1
  stl w0
  ldl w14
  jr

stmt_exec_help:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lseh_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lseh_ok:
  BIOS_PUTS_Z help_text
  BIOS_CRLF
  u 1
  stl w0
  ldl w14
  jr

help_text:
  .ascii "COMMANDS: NEW, LIST, RUN, GOTO, GOSUB, RETURN, END, IF, FOR, NEXT, INPUT, LET, PRINT, CLS, HELP, DIM (STRINGS: A$)"
  .byte 0
