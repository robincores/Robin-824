; Assignment statements.

; ------------------------------------------------------------
; stmt_assign_common()
; in:
;   w6 = namePtr
;   w7 = nameLen
;   w10 = parse pointer at '=' or following spaces
; out: w0 = 1 handled
; numeric variables only
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

  ; preserve numeric value across stmt_require_eol()
  ldl w0
  stl w8

  CALL stmt_require_eol
  ldl w0
  i1
  bne .Lsac_syntax

.Lsac_store:
  ldl w6
  stl w0
  ldl w7
  stl w1
  ldl w8
  stl w2
  CALL vars_set
  u 1
  stl w0
  ldl w14
  jr

.Lsac_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr


; ------------------------------------------------------------
; stmt_assign_string_common()
; in:
;   w6 = target namePtr
;   w7 = target nameLen
;   w10 at '=' or following spaces
; supports:
;   A$ = "literal"
;   A$ = B$
; ------------------------------------------------------------
stmt_assign_string_common:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61
  beq .Lsasc_eq
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsasc_eq:
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 34
  beq .Lsasc_lit
  ldl w0
  u TOKB_STR
  beq .Lsasc_lit

  ; otherwise require a string identifier source
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lsasc_syntax

  ldl w0
  stl w8               ; src name ptr
  ldl w1
  stl w9               ; src name len

  ldl w8
  stl w0
  ldl w9
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  bne .Lsasc_syntax

  CALL stmt_require_eol
  ldl w0
  i1
  bne .Lsasc_syntax

  ldl w8
  stl w0
  ldl w9
  stl w1
  CALL strvars_get_ptr
  ldl w0
  stl w2               ; src ptr
  ldl w1
  stl w3               ; src len
  ldl w2
  ldl w3
  add
  stl w3               ; end = ptr + len

  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL strvars_set_range
  u 1
  stl w0
  ldl w14
  jr

.Lsasc_lit:
  CALL stmt_scan_quoted_string
  ldl w0
  i1
  bne .Lsasc_syntax

  ; preserve literal range across stmt_require_eol()
  ldl w2
  stl w8               ; start ptr
  ldl w3
  stl w9               ; end ptr

  CALL stmt_require_eol
  ldl w0
  i1
  bne .Lsasc_syntax

  ldl w6
  stl w0
  ldl w7
  stl w1
  ldl w8
  stl w2
  ldl w9
  stl w3
  CALL strvars_set_range
  u 1
  stl w0
  ldl w14
  jr

.Lsasc_syntax:
  CALL stmt_fail_syntax
  ldl w14
  jr


; LET <ident> = <expr-or-string>
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
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  beq .Lslet_string
  CALL stmt_assign_common
  ldl w14
  jr

.Lslet_string:
  CALL stmt_assign_string_common
  ldl w14
  jr


; <ident> = <expr-or-string>
; entry ident already tokenized into VAR_NAME_BUF by stmt_parse_kind.
stmt_exec_assign_ident:
  stl w14
  i VAR_NAME_BUF
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
  beq .Lsai_string
  CALL stmt_assign_common
  ldl w14
  jr

.Lsai_string:
  CALL stmt_assign_string_common
  ldl w14
  jr