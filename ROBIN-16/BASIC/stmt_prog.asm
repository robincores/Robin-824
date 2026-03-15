; Program-management statements.

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
  .ascii "COMMANDS: NEW, LIST, RUN, GOTO, GOSUB, RETURN, END, IF, FOR, NEXT, INPUT, LET, PRINT, CLS, HELP (STRINGS: A$)"
  .byte 0
