; Control-flow statements.

; IF <expr> <op> <expr> THEN <stmt-or-line>
stmt_exec_if:
  stl w14

  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i0
  bne .Lsei_left_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsei_left_ok:
  ldl w0
  stl w2                ; left

  CALL tok_skip_spaces
  CALL tok_peek
  stl w3                ; ch

  ; operator -> w5: 0 EQ, 1 NE, 2 LT, 3 LE, 4 GT, 5 GE
  ldl w3
  u 61
  beq .Lsei_op_eq
  ldl w3
  u 60
  beq .Lsei_op_lt
  ldl w3
  u 62
  beq .Lsei_op_gt
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsei_op_eq:
  i0
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lsei_rhs

.Lsei_op_lt:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  stl w3
  ldl w3
  u 61
  beq .Lsei_op_le
  ldl w3
  u 62
  beq .Lsei_op_ne
  u 2
  stl w5
  bra .Lsei_rhs

.Lsei_op_le:
  u 3
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lsei_rhs

.Lsei_op_ne:
  i1
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lsei_rhs

.Lsei_op_gt:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  stl w3
  ldl w3
  u 61
  beq .Lsei_op_ge
  u 4
  stl w5
  bra .Lsei_rhs

.Lsei_op_ge:
  u 5
  stl w5
  ldl w10
  inc
  stl w10

.Lsei_rhs:
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i0
  bne .Lsei_right_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsei_right_ok:
  ldl w0
  stl w4                ; right

  CALL stmt_parse_then_keyword
  ldl w0
  i1
  beq .Lsei_then_ok
.Lsei_then_bad:
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsei_then_ok:
  CALL tok_skip_spaces

  ; Evaluate condition to w6 = 1/0
  i0
  stl w6
  ldl w5
  i0
  beq .Lsei_cond_eq
  ldl w5
  i1
  beq .Lsei_cond_ne
  ldl w5
  u 2
  beq .Lsei_cond_lt
  ldl w5
  u 3
  beq .Lsei_cond_le
  ldl w5
  u 4
  beq .Lsei_cond_gt
  bra .Lsei_cond_ge

.Lsei_cond_eq:
  ldl w2
  ldl w4
  beq .Lsei_true
  bra .Lsei_after_cond
.Lsei_cond_ne:
  ldl w2
  ldl w4
  bne .Lsei_true
  bra .Lsei_after_cond
.Lsei_cond_lt:
  ldl w2
  ldl w4
  blt .Lsei_true
  bra .Lsei_after_cond
.Lsei_cond_le:
  ldl w2
  ldl w4
  blt .Lsei_true
  ldl w2
  ldl w4
  beq .Lsei_true
  bra .Lsei_after_cond
.Lsei_cond_gt:
  ldl w2
  ldl w4
  blt .Lsei_after_cond
  ldl w2
  ldl w4
  beq .Lsei_after_cond
  bra .Lsei_true
.Lsei_cond_ge:
  ldl w2
  ldl w4
  bge .Lsei_true
  bra .Lsei_after_cond
.Lsei_true:
  i1
  stl w6
.Lsei_after_cond:

  ; numeric THEN tail => implied GOTO <lineno>
  CALL tok_peek
  stl w3
  ldl w3
  u TOKB_NUM16
  beq .Lsei_numeric_tail
  ldl w3
  u 48
  blt .Lsei_stmt_tail
  ldl w3
  u 58
  bge .Lsei_stmt_tail
.Lsei_numeric_tail:

  CALL stmt_parse_line_target
  ldl w1
  i0
  bne .Lsei_have_lineno
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsei_have_lineno:
  ldl w0
  stl w7
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsei_lineno_eol_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsei_lineno_eol_ok:
  ldl w6
  i0
  beq .Lsei_if_done

  i SYS_RUNNING
  lu
  i0
  beq .Lsei_direct_goto
  ldl w7
  u 0xFF
  and
  stl w1
  ldl w7
  srl 4
  srl 4
  u 0x7F
  and
  stl w2
  i SYS_GOTO_LO
  ldl w1
  sb
  i SYS_GOTO_HI
  ldl w2
  sb
  i SYS_GOTO_PEND
  u 1
  sb
  bra .Lsei_if_done

.Lsei_direct_goto:
  CALL vars_init
  ldl w7
  stl w0
  CALL prog_run_from_line
  bra .Lsei_if_done

.Lsei_stmt_tail:
  ; Always syntax-check the tail; only execute when the condition is true.
  ldl w10
  push
  CALL stmt_validate_dispatch
  ldl w0
  i1
  beq .Lsei_tail_valid
  pop
  stl w10
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsei_tail_valid:
  pop
  stl w10
  ldl w6
  i0
  beq .Lsei_if_done
  CALL stmt_dispatch
  ldl w0
  i1
  beq .Lsei_if_done
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsei_if_done:
  u 1
  stl w0
  ldl w14
  jr


; END
stmt_exec_end:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lseend_eol_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lseend_eol_ok:
  i SYS_RUNNING
  lu
  i0
  beq .Lseend_done
  i SYS_END_PEND
  u 1
  sb
.Lseend_done:
  u 1
  stl w0
  ldl w14
  jr

; GOTO <lineno>
stmt_exec_goto:
  stl w14
  CALL tok_skip_spaces
  CALL stmt_parse_line_target
  ldl w1
  i0
  bne .Lseg_line_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lseg_line_ok:
  ldl w0
  stl w7
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lseg_eol_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lseg_eol_ok:
  i SYS_RUNNING
  lu
  i0
  beq .Lseg_direct

  ldl w7
  u 0xFF
  and
  stl w1
  ldl w7
  srl 4
  srl 4
  u 0x7F
  and
  stl w2
  i SYS_GOTO_LO
  ldl w1
  sb
  i SYS_GOTO_HI
  ldl w2
  sb
  i SYS_GOTO_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lseg_direct:
  CALL vars_init
  ldl w7
  stl w0
  CALL prog_run_from_line
  u 1
  stl w0
  ldl w14
  jr
