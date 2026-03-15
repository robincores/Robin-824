; Syntax validators used by IF false-branch checking.
; These parse statement tails with no side effects.
;
; stmt_validate_dispatch():
;   in:  w10 at statement start
;   out: w0=1 handled+syntactically valid, else 0

stmt_validate_dispatch:
  stl w14
  CALL stmt_parse_kind
  ldl w0
  stl w8
  ldl w1
  stl w7

  ldl w8
  i STMTK_NONE
  beq .Lsvd_no

  ldl w8
  i STMTK_END
  beq .Lsvd_end
  ldl w8
  u STMTK_GOTO
  beq .Lsvd_goto
  ldl w8
  u STMTK_GOSUB
  beq .Lsvd_gosub
  ldl w8
  u STMTK_RETURN
  beq .Lsvd_return
  ldl w8
  u STMTK_NEW
  beq .Lsvd_new
  ldl w8
  u STMTK_LIST
  beq .Lsvd_list
  ldl w8
  u STMTK_RUN
  beq .Lsvd_run
  ldl w8
  u STMTK_CLS
  beq .Lsvd_cls
  ldl w8
  u STMTK_HELP
  beq .Lsvd_help
  ldl w8
  u STMTK_IF
  beq .Lsvd_if
  ldl w8
  u STMTK_FOR
  beq .Lsvd_for
  ldl w8
  u STMTK_NEXT
  beq .Lsvd_next
  ldl w8
  u STMTK_INPUT
  beq .Lsvd_input
  ldl w8
  u STMTK_LET
  beq .Lsvd_let
  ldl w8
  u STMTK_PRINT
  beq .Lsvd_print
  ldl w8
  u STMTK_REM
  beq .Lsvd_rem
  ldl w8
  u STMTK_DIM
  beq .Lsvd_dim

  ; shorthand assignment
  ldl w7
  stl w1
  CALL stmt_validate_assign_ident
  ldl w14
  jr
.Lsvd_end:    CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_goto:   CALL stmt_validate_goto
              ldl w14
              jr
.Lsvd_gosub:  CALL stmt_validate_gosub
              ldl w14
              jr
.Lsvd_return: CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_new:    CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_list:   CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_run:    CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_cls:    CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_help:   CALL stmt_validate_eol_stmt
              ldl w14
              jr
.Lsvd_if:     CALL stmt_validate_if
              ldl w14
              jr
.Lsvd_for:    CALL stmt_validate_for
              ldl w14
              jr
.Lsvd_next:   CALL stmt_validate_next
              ldl w14
              jr
.Lsvd_input:  CALL stmt_validate_input
              ldl w14
              jr
.Lsvd_let:    CALL stmt_validate_let
              ldl w14
              jr
.Lsvd_print:  CALL stmt_validate_print
              ldl w14
              jr
.Lsvd_dim:    CALL stmt_validate_dim
              ldl w14
              jr
.Lsvd_rem:    u 1
              stl w0
              ldl w14
              jr
.Lsvd_no:
  i0
  stl w0
  ldl w14
  jr

stmt_validate_eol_stmt:
  stl w14
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_goto:
  stl w14
  CALL stmt_parse_line_target
  ldl w1
  i1
  beq .Lsvg_have
  i0
  stl w0
  ldl w14
  jr
.Lsvg_have:
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_gosub:
  stl w14
  CALL stmt_validate_goto
  ldl w14
  jr

stmt_validate_assign_common:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61
  beq .Lsvac_eq
  i0
  stl w0
  ldl w14
  jr
.Lsvac_eq:
  ldl w10
  inc
  stl w10
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvac_expr_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvac_expr_ok:
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_assign_string_common:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61
  beq .Lsvasc_eq
  i0
  stl w0
  ldl w14
  jr
.Lsvasc_eq:
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 34
  beq .Lsvasc_lit
  ldl w0
  u TOKB_STR
  beq .Lsvasc_lit
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lsvasc_fail
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
  bne .Lsvasc_fail
  CALL stmt_require_eol
  ldl w14
  jr
.Lsvasc_lit:
  CALL stmt_scan_quoted_string
  ldl w0
  i1
  beq .Lsvasc_lit_ok
.Lsvasc_fail:
  i0
  stl w0
  ldl w14
  jr
.Lsvasc_lit_ok:
  CALL stmt_require_eol
  ldl w14
  jr


stmt_validate_assign_array_common:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 40
  beq .Lsvaa_lparen
  i0
  stl w0
  ldl w14
  jr
.Lsvaa_lparen:
  ldl w10
  inc
  stl w10
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvaa_idx_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvaa_idx_ok:
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 41
  beq .Lsvaa_rparen
  i0
  stl w0
  ldl w14
  jr
.Lsvaa_rparen:
  ldl w10
  inc
  stl w10
  CALL stmt_validate_assign_common
  ldl w14
  jr

stmt_validate_let:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsvl_have
  i0
  stl w0
  ldl w14
  jr
.Lsvl_have:
  ldl w0
  stl w6
  ldl w1
  stl w7
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 40
  beq .Lsvl_array
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  beq .Lsvl_string
  CALL stmt_validate_assign_common
  ldl w14
  jr
.Lsvl_array:
  CALL stmt_validate_assign_array_common
  ldl w14
  jr
.Lsvl_string:
  CALL stmt_validate_assign_string_common
  ldl w14
  jr

stmt_validate_assign_ident:
  stl w14
  i VAR_NAME_BUF
  stl w6
  ldl w1
  stl w7
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 40
  beq .Lsvai_array
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_name_is_string
  ldl w0
  i1
  beq .Lsvai_string
  CALL stmt_validate_assign_common
  ldl w14
  jr
.Lsvai_array:
  CALL stmt_validate_assign_array_common
  ldl w14
  jr
.Lsvai_string:
  CALL stmt_validate_assign_string_common
  ldl w14
  jr

stmt_validate_dim:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsvdim_have
  i0
  stl w0
  ldl w14
  jr
.Lsvdim_have:
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
  beq .Lsvdim_fail
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 40
  bne .Lsvdim_fail
  ldl w10
  inc
  stl w10
  CALL expr_eval
  ldl w1
  i1
  bne .Lsvdim_idx
.Lsvdim_fail:
  i0
  stl w0
  ldl w14
  jr
.Lsvdim_idx:
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 41
  bne .Lsvdim_fail
  ldl w10
  inc
  stl w10
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_input:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsvi_ident
  i0
  stl w0
  ldl w14
  jr
.Lsvi_ident:
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_print:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  i0
  beq .Lsvp_blank
  ldl w0
  u 34
  beq .Lsvp_string
  ldl w0
  u TOKB_STR
  beq .Lsvp_string

  ; string variable?
  ldl w10
  stl w13
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lsvp_expr
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
  beq .Lsvp_strvar
  ldl w13
  stl w10
  bra .Lsvp_expr
.Lsvp_strvar:
  CALL stmt_require_eol
  ldl w14
  jr
.Lsvp_expr:
  ldl w13
  stl w10
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvp_expr_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvp_expr_ok:
  CALL stmt_require_eol
  ldl w14
  jr
.Lsvp_blank:
  u 1
  stl w0
  ldl w14
  jr
.Lsvp_string:
  CALL stmt_scan_quoted_string
  ldl w0
  i1
  beq .Lsvp_have_string
.Lsvp_fail:
  i0
  stl w0
  ldl w14
  jr
.Lsvp_have_string:
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_next:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w13
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lsvn_no_ident
  CALL stmt_require_eol
  ldl w14
  jr
.Lsvn_no_ident:
  ldl w13
  stl w10
  CALL stmt_require_eol
  ldl w14
  jr

stmt_validate_for:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsvf_ident
  i0
  stl w0
  ldl w14
  jr
.Lsvf_ident:
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61
  beq .Lsvf_eq
  i0
  stl w0
  ldl w14
  jr
.Lsvf_eq:
  ldl w10
  inc
  stl w10
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvf_start_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvf_start_ok:
  CALL stmt_parse_to_keyword
  ldl w0
  i1
  beq .Lsvf_to_ok
.Lsvf_fail:
  i0
  stl w0
  ldl w14
  jr
.Lsvf_to_ok:
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvf_limit_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvf_limit_ok:
  CALL tok_skip_spaces
  ldl w10
  stl w13
  CALL tok_peek
  ldl w0
  i0
  beq .Lsvf_done
  CALL stmt_parse_step_keyword
  ldl w0
  i1
  beq .Lsvf_step_kw
.Lsvf_restore:
  ldl w13
  stl w10
  bra .Lsvf_done
.Lsvf_step_kw:
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvf_done
  i0
  stl w0
  ldl w14
  jr
.Lsvf_done:
  CALL stmt_require_eol
  ldl w14
  jr

; IF <expr> <op> <expr> THEN <stmt-or-line>
; validates the tail regardless of condition truth value.
stmt_validate_if:
  stl w14
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvi_left_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvi_left_ok:
  CALL tok_skip_spaces
  CALL tok_peek
  stl w3
  ldl w3
  u 61
  beq .Lsvi_op_eq
  ldl w3
  u 60
  beq .Lsvi_op_lt
  ldl w3
  u 62
  beq .Lsvi_op_gt
  i0
  stl w0
  ldl w14
  jr
.Lsvi_op_eq:
  ldl w10
  inc
  stl w10
  bra .Lsvi_rhs
.Lsvi_op_lt:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  stl w3
  ldl w3
  u 61
  beq .Lsvi_consume2
  ldl w3
  u 62
  beq .Lsvi_consume2
  bra .Lsvi_rhs
.Lsvi_op_gt:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  stl w3
  ldl w3
  u 61
  beq .Lsvi_consume2
  bra .Lsvi_rhs
.Lsvi_consume2:
  ldl w10
  inc
  stl w10
.Lsvi_rhs:
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i1
  beq .Lsvi_right_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvi_right_ok:
  CALL stmt_parse_then_keyword
  ldl w0
  i1
  beq .Lsvi_then_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvi_then_ok:
  CALL tok_skip_spaces
  CALL tok_peek
  stl w3
  ldl w3
  u TOKB_NUM16
  beq .Lsvi_numeric_tail
  ldl w3
  u 48
  blt .Lsvi_stmt_tail
  ldl w3
  u 58
  bge .Lsvi_stmt_tail
.Lsvi_numeric_tail:
  CALL stmt_parse_line_target
  ldl w1
  i1
  beq .Lsvi_line_ok
  i0
  stl w0
  ldl w14
  jr
.Lsvi_line_ok:
  CALL stmt_require_eol
  ldl w14
  jr
.Lsvi_stmt_tail:
  CALL stmt_validate_dispatch
  ldl w14
  jr
