; BIOS/R816/BASIC/tok.asm
; Token helpers (v0.4.1) 
; Conventions:
; - w10 is the current parse pointer
; - functions update w10 as they consume input

; ------------------------------------------------------------
; tok_skip_spaces(): advances w10 past spaces/tabs
; ------------------------------------------------------------
tok_skip_spaces:
  stl w14
.Ltok_sp_loop:
  ldl w10
  lu
  stl w1

  ldl w1
  u 32
  beq .Ltok_sp_adv

  ldl w1
  u 9
  beq .Ltok_sp_adv

  bra .Ltok_sp_done

.Ltok_sp_adv:
  ldl w10
  inc
  stl w10
  bra .Ltok_sp_loop

.Ltok_sp_done:
  ldl w14
  jr

; ------------------------------------------------------------
; tok_peek(): returns w0 = *w10 (byte)
; ------------------------------------------------------------
tok_peek:
  stl w14
  ldl w10
  lu
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; tok_to_upper(w0=in ch) -> w0=upper ch
; only affects a..z
; ------------------------------------------------------------
tok_to_upper:
  stl w14

  ldl w0
  u 97
  blt .Ltok_up_ret

  ldl w0
  u 123
  bge .Ltok_up_ret

  ldl w0
  u 32
  sub
  stl w0

.Ltok_up_ret:
  ldl w14
  jr


; ------------------------------------------------------------
; tok_is_kw_delim()
; returns w0=1 iff current *w10 is a keyword delimiter:
;   NUL, space, tab, ':'
; ------------------------------------------------------------
tok_is_kw_delim:
  stl w14
  CALL tok_peek
  ldl w0
  i0
  beq .Ltok_kd_yes
  u 32
  beq .Ltok_kd_yes
  ldl w0
  u 9
  beq .Ltok_kd_yes
  ldl w0
  u 58
  beq .Ltok_kd_yes
  i0
  stl w0
  ldl w14
  jr
.Ltok_kd_yes:
  i1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; tok_read_u16()
; Parses decimal digits at w10 with overflow checking.
; out: w0=value, w1=1 if at least one digit consumed and <=65535 else 0
; note: on overflow, w10 is advanced past the full digit run and w1=0
; ------------------------------------------------------------
tok_read_u16:
  stl w14

  ldl w10
  stl w9            ; start

  i0
  stl w2            ; value

.Lnum_loop:
  ldl w10
  lu
  stl w1            ; ch

  ldl w1
  u 48
  blt .Lnum_done
  ldl w1
  u 58
  bge .Lnum_done

  ; digit = ch - '0'
  ldl w1
  u 48
  sub
  stl w3

  ; overflow check for max 65535:
  ; if value > 6553 => overflow
  ; if value == 6553 and digit > 5 => overflow
  ldl w2
  i 6553
  blt .Lnum_mul_add
  ldl w2
  i 6553
  beq .Lnum_eq_thresh
  bra .Lnum_overflow

.Lnum_eq_thresh:
  ldl w3
  u 6
  blt .Lnum_mul_add
  bra .Lnum_overflow

.Lnum_mul_add:
  ldl w2
  u 10
  mul
  ldl w3
  add
  stl w2

  ldl w10
  inc
  stl w10
  bra .Lnum_loop

.Lnum_overflow:
  ; consume remaining digits so callers do not see a partial token
.Lnum_ovf_consume:
  ldl w10
  lu
  stl w1
  ldl w1
  u 48
  blt .Lnum_fail
  ldl w1
  u 58
  bge .Lnum_fail
  ldl w10
  inc
  stl w10
  bra .Lnum_ovf_consume

.Lnum_fail:
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr

.Lnum_done:
  ldl w2
  stl w0

  ; consumed?
  ldl w10
  ldl w9
  beq .Lnum_no

  u 1
  stl w1
  ldl w14
  jr

.Lnum_no:
  i0
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; tok_read_lineno()
; Parses a BASIC line number at w10.
; out: w0=value, w1=1 if at least one digit consumed and <=32767 else 0
; note: on failure, w10 is advanced past the full digit run
; ------------------------------------------------------------
tok_read_lineno:
  stl w14
  CALL tok_read_u16
  ldl w1
  i0
  bne .Ltln_have_num
  ldl w14
  jr
.Ltln_have_num:
  ldl w0
  i 0x8000
  and
  i0
  beq .Ltln_ok
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Ltln_ok:
  ldl w14
  jr

; ------------------------------------------------------------
; tok_read_ident()
; Reads an identifier into VAR_NAME_BUF (uppercased).
; Name rules:
;   start: A-Z or '_'
;   rest:  A-Z 0-9 '_'
; Max stored length: VAR_NAME_MAX (extra chars are consumed but not stored)
; out: w0=VAR_NAME_BUF, w1=len (0 => no ident)
;
; NOTE: implemented with inline char fetch + uppercase to keep loops small
;       (avoids rel8 overflows and is faster than CALL-heavy version).
; ------------------------------------------------------------
tok_read_ident:
  stl w14

  CALL tok_skip_spaces

  ldl w10
  stl w9            ; start ptr

  i VAR_NAME_BUF
  stl w12           ; dst
  i0
  stl w11           ; len

  ; ---- read first char ----
  ldl w10
  lu
  stl w0            ; ch

  ; uppercase inline: if 'a'<=ch<'z'+1 then ch-=32
  ldl w0
  u 97
  blt .Lid_up1_done
  ldl w0
  u 123
  bge .Lid_up1_done
  ldl w0
  u 32
  sub
  stl w0
.Lid_up1_done:

  ; '_' allowed
  ldl w0
  u 95
  beq .Lid_first_ok

  ; 'A'..'Z'?
  ldl w0
  u 65
  blt .Lid_fail
  ldl w0
  u 91
  bge .Lid_fail

.Lid_first_ok:
  ; store if len < max
  ldl w11
  i VAR_NAME_MAX
  bge .Lid_first_nostore
  ldl w12
  ldl w0
  sb
  ldl w12
  inc
  stl w12
  ldl w11
  inc
  stl w11
.Lid_first_nostore:

  ; consume first
  ldl w10
  inc
  stl w10

  ; ---- loop remaining ----
.Lid_loop:
  ldl w10
  lu
  stl w0            ; ch

  ; uppercase inline
  ldl w0
  u 97
  blt .Lid_up_done
  ldl w0
  u 123
  bge .Lid_up_done
  ldl w0
  u 32
  sub
  stl w0
.Lid_up_done:

  ; '_' ?
  ldl w0
  u 95
  beq .Lid_store

  ; digit '0'..'9' ?
  ldl w0
  u 48
  blt .Lid_chk_alpha
  ldl w0
  u 58
  blt .Lid_store

.Lid_chk_alpha:
  ; alpha 'A'..'Z' ?
  ldl w0
  u 65
  blt .Lid_done
  ldl w0
  u 91
  bge .Lid_done

.Lid_store:
  ; store if len < max
  ldl w11
  i VAR_NAME_MAX
  bge .Lid_consume

  ldl w12
  ldl w0
  sb
  ldl w12
  inc
  stl w12
  ldl w11
  inc
  stl w11

.Lid_consume:
  ldl w10
  inc
  stl w10
  j .Lid_loop
.Lid_done:
  ; optional trailing '$' for string variables
  ldl w10
  lu
  stl w0
  ldl w0
  u 36
  bne .Lid_finish

  ; store '$' if len < max
  ldl w11
  i VAR_NAME_MAX
  bge .Lid_consume_dollar
  ldl w12
  ldl w0
  sb
  ldl w12
  inc
  stl w12
  ldl w11
  inc
  stl w11
.Lid_consume_dollar:
  ldl w10
  inc
  stl w10

.Lid_finish:
  ; NUL terminate
  ldl w12
  u 0
  sb

  i VAR_NAME_BUF
  stl w0
  ldl w11
  stl w1
  ldl w14
  jr

.Lid_fail:
  ; no ident: restore ptr, return len=0
  ldl w9
  stl w10
  i VAR_NAME_BUF
  stl w0
  i0
  stl w1
  ldl w14
  jr


; ------------------------------------------------------------
; tok_skip_to_eol()
; Advances w10 until NUL (0) encountered (consumes rest of line).
; ------------------------------------------------------------
tok_skip_to_eol:
  stl w14
.Lte_loop:
  ldl w10
  lu
  i0
  beq .Lte_done
  ldl w10
  inc
  stl w10
  bra .Lte_loop
.Lte_done:
  ldl w14
  jr
