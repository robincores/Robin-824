; Assignment statements.

; ------------------------------------------------------------
; stmt_assign_common()
; in:
;   w6 = namePtr
;   w7 = nameLen
;   w10 = parse pointer at '=' or following spaces
; out: w0 = 1 handled
; ------------------------------------------------------------
stmt_assign_common:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61
  beq .Lsac_eq
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsac_eq:
  ldl w10
  inc
  stl w10

  CALL expr_eval
  ldl w1
  i0
  beq .Lsac_syntax
  ldl w0
  stl w2

  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsac_store
.Lsac_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsac_store:
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

; LET <ident> = <expr>
stmt_exec_let:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lslet_have
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lslet_have:
  ldl w0
  stl w6
  ldl w1
  stl w7
  CALL stmt_assign_common
  ldl w14
  jr

; <ident> = <expr>
; entry ident already tokenized into VAR_NAME_BUF by stmt_parse_kind.
stmt_exec_assign_ident:
  stl w14
  i VAR_NAME_BUF
  stl w6
  ldl w1
  stl w7
  CALL stmt_assign_common
  ldl w14
  jr
