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

; ------------------------------------------------------------
; Tokenized stored-line helpers
; Stored program lines use keyword tokens (>= 0x80) to reduce RAM use.
; Direct-mode input remains plain ASCII. LIST/RUN detokenize back to text.
; ------------------------------------------------------------
.equ TOKB_END    0x80
.equ TOKB_GOTO   0x81
.equ TOKB_GOSUB  0x82
.equ TOKB_RETURN 0x83
.equ TOKB_NEW    0x84
.equ TOKB_LIST   0x85
.equ TOKB_RUN    0x86
.equ TOKB_CLS    0x87
.equ TOKB_HELP   0x88
.equ TOKB_IF     0x89
.equ TOKB_FOR    0x8A
.equ TOKB_NEXT   0x8B
.equ TOKB_INPUT  0x8C
.equ TOKB_LET    0x8D
.equ TOKB_PRINT  0x8E
.equ TOKB_REM    0x8F
.equ TOKB_THEN   0x90
.equ TOKB_TO     0x91
.equ TOKB_STEP   0x92

kwtok_end:    .ascii "END"
             .byte 0
kwtok_goto:   .ascii "GOTO"
.byte 0
kwtok_gosub:  .ascii "GOSUB"
.byte 0
kwtok_return: .ascii "RETURN"
.byte 0
kwtok_new:    .ascii "NEW"
             .byte 0
kwtok_list:   .ascii "LIST"
.byte 0
kwtok_run:    .ascii "RUN"
             .byte 0
kwtok_cls:    .ascii "CLS"
             .byte 0
kwtok_help:   .ascii "HELP"
.byte 0
kwtok_if:     .ascii "IF"
.byte 0
kwtok_for:    .ascii "FOR"
             .byte 0
kwtok_next:   .ascii "NEXT"
.byte 0
kwtok_input:  .ascii "INPUT"
.byte 0
kwtok_let:    .ascii "LET"
             .byte 0
kwtok_print:  .ascii "PRINT"
.byte 0
kwtok_rem:    .ascii "REM"
             .byte 0
kwtok_then:   .ascii "THEN"
.byte 0
kwtok_to:     .ascii "TO"
.byte 0
kwtok_step:   .ascii "STEP"
.byte 0

; tok_kw_buf_eq(w0=kwPtr, w1=kwLen, w7=currentLen) -> w0=1/0
; compares against VAR_NAME_BUF (uppercased by tok_read_ident)
tok_kw_buf_eq:
  stl w14
  ldl w7
  ldl w1
  beq .Ltkbe_len_ok
  i0
  stl w0
  ldl w14
  jr
.Ltkbe_len_ok:
  i VAR_NAME_BUF
  stl w11
  ldl w0
  stl w12
  ldl w7
  stl w13
.Ltkbe_loop:
  ldl w13
  i0
  beq .Ltkbe_yes
  ldl w11
  lu
  stl w2
  ldl w12
  lu
  stl w3
  ldl w2
  ldl w3
  beq .Ltkbe_next
  i0
  stl w0
  ldl w14
  jr
.Ltkbe_next:
  ldl w11
  inc
  stl w11
  ldl w12
  inc
  stl w12
  ldl w13
  dec
  stl w13
  bra .Ltkbe_loop
.Ltkbe_yes:
  i1
  stl w0
  ldl w14
  jr

; tok_keyword_token()
; in:  VAR_NAME_BUF contains uppercased identifier, w7=len
; out: w0 = TOKB_* or 0 if not a tokenized keyword
tok_keyword_token:
  stl w14

  i kwtok_end
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_end

  i kwtok_goto
  stl w0
  u 4
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_goto

  i kwtok_gosub
  stl w0
  u 5
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_gosub

  i kwtok_return
  stl w0
  u 6
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_return

  i kwtok_new
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_new

  i kwtok_list
  stl w0
  u 4
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_list

  i kwtok_run
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_run

  i kwtok_cls
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_cls

  i kwtok_help
  stl w0
  u 4
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_help

  i kwtok_if
  stl w0
  u 2
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_if

  i kwtok_for
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_for

  i kwtok_next
  stl w0
  u 4
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_next

  i kwtok_input
  stl w0
  u 5
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_input

  i kwtok_let
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_let

  i kwtok_print
  stl w0
  u 5
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_print

  i kwtok_rem
  stl w0
  u 3
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_rem

  i kwtok_then
  stl w0
  u 4
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_then

  i kwtok_to
  stl w0
  u 2
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_to

  i kwtok_step
  stl w0
  u 4
  stl w1
  CALL tok_kw_buf_eq
  ldl w0
  i1
  beq .Ltkkt_step

  i0
  stl w0
  ldl w14
  jr
.Ltkkt_end:    u TOKB_END
               stl w0
               bra .Ltkkt_ret
.Ltkkt_goto:   u TOKB_GOTO
               stl w0
               bra .Ltkkt_ret
.Ltkkt_gosub:  u TOKB_GOSUB
               stl w0
               bra .Ltkkt_ret
.Ltkkt_return: u TOKB_RETURN
               stl w0
               bra .Ltkkt_ret
.Ltkkt_new:    u TOKB_NEW
               stl w0
               bra .Ltkkt_ret
.Ltkkt_list:   u TOKB_LIST
               stl w0
               bra .Ltkkt_ret
.Ltkkt_run:    u TOKB_RUN
               stl w0
               bra .Ltkkt_ret
.Ltkkt_cls:    u TOKB_CLS
               stl w0
               bra .Ltkkt_ret
.Ltkkt_help:   u TOKB_HELP
               stl w0
               bra .Ltkkt_ret
.Ltkkt_if:     u TOKB_IF
               stl w0
               bra .Ltkkt_ret
.Ltkkt_for:    u TOKB_FOR
               stl w0
               bra .Ltkkt_ret
.Ltkkt_next:   u TOKB_NEXT
               stl w0
               bra .Ltkkt_ret
.Ltkkt_input:  u TOKB_INPUT
               stl w0
               bra .Ltkkt_ret
.Ltkkt_let:    u TOKB_LET
               stl w0
               bra .Ltkkt_ret
.Ltkkt_print:  u TOKB_PRINT
               stl w0
               bra .Ltkkt_ret
.Ltkkt_rem:    u TOKB_REM
               stl w0
               bra .Ltkkt_ret
.Ltkkt_then:   u TOKB_THEN
               stl w0
               bra .Ltkkt_ret
.Ltkkt_to:     u TOKB_TO
               stl w0
               bra .Ltkkt_ret
.Ltkkt_step:   u TOKB_STEP
               stl w0
.Ltkkt_ret:
  ldl w14
  jr

; tok_copy_zstr(w0=srcZ, w11=dst, w12=lenSoFar) -> updates w11,w12
; copies a NUL-terminated string to dst (without the terminating NUL)
tok_copy_zstr:
  stl w14
  ldl w0
  stl w9
.Ltkcz_loop:
  ldl w9
  lu
  stl w1
  ldl w1
  i0
  beq .Ltkcz_done
  ldl w11
  ldl w1
  sb
  ldl w11
  inc
  stl w11
  ldl w12
  inc
  stl w12
  ldl w9
  inc
  stl w9
  bra .Ltkcz_loop
.Ltkcz_done:
  ldl w14
  jr

; tok_tokenize_line(w0=srcPtr, w1=dstPtr)
; tokenizes keyword lexemes outside quoted strings, preserving all other text.
; out: w0 = tokenizedLenIncludingNul, w1 = dstPtr
tok_tokenize_line:
  stl w14
  ldl w0
  stl w10              ; src
  ldl w1
  stl w5               ; dst
  i0
  stl w6               ; len
.Lttl_loop:
  ldl w10
  lu
  stl w0
  ldl w0
  i0
  beq .Lttl_nul
  ldl w0
  u 34
  beq .Lttl_quote

  ; identifier start? ('_' or alpha)
  ldl w0
  stl w3
  ldl w3
  u 97
  blt .Lttl_up_done
  ldl w3
  u 123
  bge .Lttl_up_done
  ldl w3
  u 32
  sub
  stl w3
.Lttl_up_done:
  ldl w3
  u 95
  beq .Lttl_ident
  ldl w3
  u 65
  blt .Lttl_raw
  ldl w3
  u 91
  bge .Lttl_raw

.Lttl_ident:
  ldl w10
  stl w9               ; start ptr
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lttl_raw        ; defensive only
  ldl w1
  stl w7
  CALL tok_keyword_token
  ldl w0
  i0
  beq .Lttl_copy_ident_raw

  ; emit token byte
  ldl w5
  ldl w0
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6

  ; REM token: preserve remainder of line raw
  ldl w0
  u TOKB_REM
  bne .Lttl_loop
.Lttl_rem_copy:
  ldl w10
  lu
  stl w1
  ldl w5
  ldl w1
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6
  ldl w1
  i0
  beq .Lttl_done_ret
  ldl w10
  inc
  stl w10
  bra .Lttl_rem_copy

.Lttl_copy_ident_raw:
  ldl w9
  stl w3
.Lttl_copy_ident_loop:
  ldl w3
  ldl w10
  beq .Lttl_loop
  ldl w3
  lu
  stl w1
  ldl w5
  ldl w1
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6
  ldl w3
  inc
  stl w3
  bra .Lttl_copy_ident_loop

.Lttl_quote:
  ; copy opening quote
  ldl w5
  u 34
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6
  ldl w10
  inc
  stl w10
.Lttl_qloop:
  ldl w10
  lu
  stl w1
  ldl w5
  ldl w1
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6
  ldl w1
  i0
  beq .Lttl_done_ret
  ldl w10
  inc
  stl w10
  ldl w1
  u 34
  beq .Lttl_loop
  bra .Lttl_qloop

.Lttl_raw:
  ldl w5
  ldl w0
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6
  ldl w10
  inc
  stl w10
  bra .Lttl_loop

.Lttl_nul:
  ldl w5
  u 0
  sb
  ldl w5
  inc
  stl w5
  ldl w6
  inc
  stl w6
.Lttl_done_ret:
  ldl w6
  stl w0
  ldl w14
  jr

; tok_detokenize_line(w0=srcTokPtr, w1=dstPtr)
; expands stored keyword tokens back to plain text for LIST/RUN.
; out: w0 = detokenizedLenIncludingNul, w1 = dstPtr
tok_detokenize_line:
  stl w14
  ldl w0
  stl w10              ; src
  ldl w1
  stl w11              ; dst
  i0
  stl w12              ; len
.Ltdl_loop:
  ldl w10
  lu
  stl w0
  ldl w0
  i0
  beq .Ltdl_nul
  ldl w0
  i 0x80
  and
  i0
  bne .Ltdl_token
  ; raw byte
  ldl w11
  ldl w0
  sb
  ldl w11
  inc
  stl w11
  ldl w12
  inc
  stl w12
  ldl w10
  inc
  stl w10
  bra .Ltdl_loop

.Ltdl_token:
  ldl w0
  u TOKB_END
  beq .Ltdl_emit_end
  ldl w0
  u TOKB_GOTO
  beq .Ltdl_emit_goto
  ldl w0
  u TOKB_GOSUB
  beq .Ltdl_emit_gosub
  ldl w0
  u TOKB_RETURN
  beq .Ltdl_emit_return
  ldl w0
  u TOKB_NEW
  beq .Ltdl_emit_new
  ldl w0
  u TOKB_LIST
  beq .Ltdl_emit_list
  ldl w0
  u TOKB_RUN
  beq .Ltdl_emit_run
  ldl w0
  u TOKB_CLS
  beq .Ltdl_emit_cls
  ldl w0
  u TOKB_HELP
  beq .Ltdl_emit_help
  ldl w0
  u TOKB_IF
  beq .Ltdl_emit_if
  ldl w0
  u TOKB_FOR
  beq .Ltdl_emit_for
  ldl w0
  u TOKB_NEXT
  beq .Ltdl_emit_next
  ldl w0
  u TOKB_INPUT
  beq .Ltdl_emit_input
  ldl w0
  u TOKB_LET
  beq .Ltdl_emit_let
  ldl w0
  u TOKB_PRINT
  beq .Ltdl_emit_print
  ldl w0
  u TOKB_REM
  beq .Ltdl_emit_rem
  ldl w0
  u TOKB_THEN
  beq .Ltdl_emit_then
  ldl w0
  u TOKB_TO
  beq .Ltdl_emit_to
  ldl w0
  u TOKB_STEP
  beq .Ltdl_emit_step
  ; unknown high byte: emit raw as-is
  ldl w11
  ldl w0
  sb
  ldl w11
  inc
  stl w11
  ldl w12
  inc
  stl w12
  bra .Ltdl_adv

.Ltdl_emit_end:    i kwtok_end    ; token payloads detokenize in canonical uppercase
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_goto:   i kwtok_goto
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_gosub:  i kwtok_gosub
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_return: i kwtok_return
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_new:    i kwtok_new
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_list:   i kwtok_list
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_run:    i kwtok_run
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_cls:    i kwtok_cls
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_help:   i kwtok_help
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_if:     i kwtok_if
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_for:    i kwtok_for
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_next:   i kwtok_next
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_input:  i kwtok_input
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_let:    i kwtok_let
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_print:  i kwtok_print
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_rem:    i kwtok_rem
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_then:   i kwtok_then
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_to:     i kwtok_to
                   stl w0
                   bra .Ltdl_copy
.Ltdl_emit_step:   i kwtok_step
                   stl w0
.Ltdl_copy:
  CALL tok_copy_zstr
.Ltdl_adv:
  ldl w10
  inc
  stl w10
  bra .Ltdl_loop

.Ltdl_nul:
  ldl w11
  u 0
  sb
  ldl w11
  inc
  stl w11
  ldl w12
  inc
  stl w12
  ldl w12
  stl w0
  ldl w14
  jr
