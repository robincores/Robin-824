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
; tok_read_u16()
; Parses decimal digits at w10.
; out: w0=value, w1=1 if at least one digit consumed else 0
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

  ; value = value*10 + digit
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
