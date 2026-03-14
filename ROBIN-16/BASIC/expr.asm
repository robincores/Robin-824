; BIOS/R816/BASIC/expr.asm
; Integer expression evaluator (final hardening pass)
; - signed 16-bit integers
; - grammar: expr   := term (('+'|'-') term)*
;            term   := factor (('*'|'/') factor)*
;            factor := number | ident | '(' expr ')' | '-' factor
;
; Contract:
;   w0 = value (only valid when w1=1)
;   w1 = success flag (1=success, 0=failure)
;   w10 = parse pointer (advanced on successful consumption)
;
; Internal register use:
;   w2 = running left accumulator at current precedence level
;   w3 = current operator / current char scratch
;   w4 = scratch / saved inner value / discarded preserved-left sink
;
; R8 style here:
; - workspace registers hold live parser state
; - operand stack is used for ALU flow only
; - PUSH/POP is used only at true recursive boundaries to preserve the
;   current left accumulator across a deeper parse call
;
; Hardening goals of this pass:
; - missing RHS after '+' '-' '*' '/' fails hard
; - missing ')' fails hard
; - division by zero fails hard
; - success is explicit in w1; value 0 is never used as an implicit error
;
; ------------------------------------------------------------
; expr_eval()
; out: w0=value, w1=success(1/0)
; ------------------------------------------------------------
expr_eval:
  stl w14

  CALL expr_parse_expr

  ldl w14
  jr

; ------------------------------------------------------------
; expr_parse_expr() -> w0, w1=success
; ------------------------------------------------------------
expr_parse_expr:
  stl w14

  CALL expr_parse_term
  ldl w1
  i0
  beq .Lex_expr_fail

  ldl w0
  stl w2          ; left accumulator

.Lex_e_loop:
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  stl w3          ; op

  ; '+'?
  ldl w3
  u 43
  beq .Lex_e_plus

  ; '-'?
  ldl w3
  u 45
  beq .Lex_e_minus

  ; done
  ldl w2
  stl w0
  u 1
  stl w1
  ldl w14
  jr

.Lex_e_plus:
  ; consume '+'
  ldl w10
  inc
  stl w10

  ; preserve current left across recursive call
  ldl w2
  push

  CALL expr_parse_term
  ldl w1
  i0
  beq .Lex_e_rhs_fail

  ; left = POP, rhs = w0
  pop
  ldl w0
  add
  stl w2
  bra .Lex_e_loop

.Lex_e_minus:
  ; consume '-'
  ldl w10
  inc
  stl w10

  ; preserve current left across recursive call
  ldl w2
  push

  CALL expr_parse_term
  ldl w1
  i0
  beq .Lex_e_rhs_fail

  ; left = POP, rhs = w0
  pop
  ldl w0
  sub
  stl w2
  bra .Lex_e_loop

.Lex_e_rhs_fail:
  ; discard preserved left and fail hard
  pop
  stl w4
.Lex_expr_fail:
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; expr_parse_term() -> w0, w1=success
; ------------------------------------------------------------
expr_parse_term:
  stl w14

  CALL expr_parse_factor
  ldl w1
  i0
  beq .Lex_term_fail

  ldl w0
  stl w2          ; left accumulator

.Lex_t_loop:
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  stl w3          ; op

  ; '*'
  ldl w3
  u 42
  beq .Lex_t_mul

  ; '/'
  ldl w3
  u 47
  beq .Lex_t_div

  ldl w2
  stl w0
  u 1
  stl w1
  ldl w14
  jr

.Lex_t_mul:
  ; consume '*'
  ldl w10
  inc
  stl w10

  ; preserve current left across recursive call
  ldl w2
  push

  CALL expr_parse_factor
  ldl w1
  i0
  beq .Lex_t_rhs_fail

  ; left = POP, rhs = w0
  pop
  ldl w0
  mul
  stl w2
  bra .Lex_t_loop

.Lex_t_div:
  ; consume '/'
  ldl w10
  inc
  stl w10

  ; preserve current left across recursive call
  ldl w2
  push

  CALL expr_parse_factor
  ldl w1
  i0
  beq .Lex_t_rhs_fail

  ; division by zero -> fail hard and discard preserved left
  ldl w0
  i0
  beq .Lex_t_div_zero

  ; left = POP, rhs = w0
  pop
  ldl w0
  div
  stl w2
  bra .Lex_t_loop

.Lex_t_div_zero:
  pop
  stl w4
  bra .Lex_term_fail

.Lex_t_rhs_fail:
  ; discard preserved left and fail hard
  pop
  stl w4
.Lex_term_fail:
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; expr_parse_factor() -> w0, w1=success
; ------------------------------------------------------------
expr_parse_factor:
  stl w14

  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  stl w3          ; current char / token

  ; '(' ?
  ldl w3
  u 40
  beq .Lfac_paren

  ; unary '-' ?
  ldl w3
  u 45
  beq .Lfac_neg

  ; tokenized number?
  ldl w3
  u TOKB_NUM16
  beq .Lfac_number

  ; digit? '0'..'9'
  ldl w3
  u 48
  blt .Lfac_ident
  ldl w3
  u 58
  bge .Lfac_ident

.Lfac_number:
  CALL tok_read_u16
  ; tok_read_u16 returns w0=value, w1=consumed(1/0)
  ldl w14
  jr

.Lfac_ident:
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lfac_fail

  ; vars_get(namePtr=w0, len=w1) -> w0=value
  CALL vars_get
  u 1
  stl w1
  ldl w14
  jr

.Lfac_paren:
  ; consume '('
  ldl w10
  inc
  stl w10

  CALL expr_parse_expr
  ldl w1
  i0
  beq .Lfac_fail

  ldl w0
  stl w4          ; preserve inner value across tok helpers

  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 41
  bne .Lfac_fail  ; require ')'

  ; consume ')'
  ldl w10
  inc
  stl w10

  ldl w4
  stl w0
  u 1
  stl w1
  ldl w14
  jr

.Lfac_neg:
  ; consume '-'
  ldl w10
  inc
  stl w10

  CALL expr_parse_factor
  ldl w1
  i0
  beq .Lfac_fail

  ldl w0
  neg
  stl w0
  u 1
  stl w1
  ldl w14
  jr

.Lfac_fail:
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr
