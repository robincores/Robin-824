; BIOS/R816/BASIC/expr.asm
; Integer expression evaluator (v0.4)
; - signed 16-bit integers
; - grammar: expr := term (('+'|'-') term)*
;           term := factor (('*'|'/') factor)*
;         factor := number | ident | '(' expr ')' | '-' factor
;
; Conventions:
;   w10 = parse pointer (updated)
;   expr_eval() -> w0=value, w1=1 if consumed tokens else 0

; ------------------------------------------------------------
; expr_eval()
; out: w0=value, w1=success(1/0)
; ------------------------------------------------------------
expr_eval:
  stl w14

  ldl w10
  stl w9          ; start

  CALL expr_parse_expr

  ldl w10
  ldl w9
  beq .Lex_fail

  u 1
  stl w1
  ldl w14
  jr

.Lex_fail:
  i0
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; expr_parse_expr() -> w0
; ------------------------------------------------------------
expr_parse_expr:
  stl w14

  CALL expr_parse_term
  ldl w0
  stl w2          ; left

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
  ldl w14
  jr

.Lex_e_plus:
  ldl w10
  inc
  stl w10
  CALL expr_parse_term

  ldl w2
  ldl w0
  add
  stl w2
  bra .Lex_e_loop

.Lex_e_minus:
  ldl w10
  inc
  stl w10
  CALL expr_parse_term

  ldl w2
  ldl w0
  sub
  stl w2
  bra .Lex_e_loop

; ------------------------------------------------------------
; expr_parse_term() -> w0
; ------------------------------------------------------------
expr_parse_term:
  stl w14

  CALL expr_parse_factor
  ldl w0
  stl w2          ; left

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
  ldl w14
  jr

.Lex_t_mul:
  ldl w10
  inc
  stl w10
  CALL expr_parse_factor

  ldl w2
  ldl w0
  mul
  stl w2
  bra .Lex_t_loop

.Lex_t_div:
  ldl w10
  inc
  stl w10
  CALL expr_parse_factor

  ldl w2
  ldl w0
  div
  stl w2
  bra .Lex_t_loop

; ------------------------------------------------------------
; expr_parse_factor() -> w0
; ------------------------------------------------------------
expr_parse_factor:
  stl w14

  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  stl w3          ; ch

  ; '(' ?
  ldl w3
  u 40
  beq .Lfac_paren

  ; unary '-' ?
  ldl w3
  u 45
  beq .Lfac_neg

  ; digit? '0'..'9'
  ldl w3
  u 48
  blt .Lfac_ident
  ldl w3
  u 58
  bge .Lfac_ident

  CALL tok_read_u16
  ; tok_read_u16 sets w0=value, w1=consumed
  ldl w14
  jr

.Lfac_ident:
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lfac_fail

  ; vars_get(namePtr=w0, len=w1)
  CALL vars_get
  ldl w14
  jr

.Lfac_paren:
  ; consume '('
  ldl w10
  inc
  stl w10

  CALL expr_parse_expr

  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 41
  beq .Lfac_cons_rparen

  ; missing ')': still return value
  ldl w14
  jr

.Lfac_cons_rparen:
  ldl w10
  inc
  stl w10
  ldl w14
  jr

.Lfac_neg:
  ; consume '-'
  ldl w10
  inc
  stl w10
  CALL expr_parse_factor

  ldl w0
  neg
  stl w0

  ldl w14
  jr

.Lfac_fail:
  i0
  stl w0
  ldl w14
  jr
