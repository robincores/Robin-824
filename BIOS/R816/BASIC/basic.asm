; BIOS/R816/BASIC/basic.asm
; R816 Tiny BASIC v0.4 (NEW/LIST/RUN/GOTO/END + LET + integer expressions)
;
; Notes:
; - Variables: QuickBASIC-ish (no type suffix), INT16 only in v0.4
; - Var names: case-insensitive, stored uppercased, max VAR_NAME_MAX chars

.arch r816
.width 8

.include "BIOS/R816/ports.inc"
.include "BIOS/R816/macros.inc"

; ---------------- BASIC memory ----------------
.equ LINE_BUF     0x9100
.equ LINE_MAX     159

; ---------------- Program store + UI/Run state ----------------
.equ SYS_PROG_TOP   0x9000      ; 2 bytes: low, high
.equ SYS_PROMPT     0x9002      ; 1 byte: 1=print READY, 0=silent (program entry)

; RUN control flags (simple)
.equ SYS_RUNNING    0x9003      ; 1 byte: 1=RUN active
.equ SYS_GOTO_LO    0x9004      ; 1 byte
.equ SYS_GOTO_HI    0x9005      ; 1 byte
.equ SYS_GOTO_PEND  0x9006      ; 1 byte
.equ SYS_END_PEND   0x9007      ; 1 byte

.equ PROG_BASE      0xA000
.equ PROG_LIMIT     0xBE00      ; first forbidden address

; ---------------- Variables (INT16) ----------------
.equ VAR_NAME_BUF   0x91A0      ; 32 bytes buffer (max 31 + NUL)
.equ VAR_NAME_MAX   31
.equ VAR_SLOTS      64
.equ VAR_ENTRY_SIZE 34          ; 1 len + 31 name + 2 value
.equ VAR_BASE       0x9200      ; 64*34 = 2176 bytes

.text
.org 0x0000

start:
  i 0xBEFE
  stl sp

  CALL bios_term_init
  CALL prog_new
  CALL vars_init

  i SYS_PROMPT
  u 1
  sb

  i SYS_RUNNING
  u 0
  sb
  i SYS_GOTO_PEND
  u 0
  sb
  i SYS_END_PEND
  u 0
  sb

  i banner
  stl w0
  CALL bios_puts_z

.Lbasic_repl:
  i SYS_PROMPT
  lu
  i0
  beq .Lno_prompt

  i ready
  stl w0
  CALL bios_puts_z

.Lno_prompt:
  i LINE_BUF
  stl w0
  i LINE_MAX
  stl w1
  CALL bios_kbd_readline

  i LINE_BUF
  stl w0
  CALL basic_exec_line

  bra .Lbasic_repl

; ------------------------------------------------------------
; basic_parse_lineno()
; in:  w10 = ptr at first digit
; out: w0  = line number
;      w10 advanced past digits
; ------------------------------------------------------------
basic_parse_lineno:
  stl w14

  i0
  stl w2

.Lln_loop:
  ldl w10
  lu
  stl w1

  ldl w1
  u 48
  blt .Lln_done

  ldl w1
  u 58
  bge .Lln_done

  ldl w1
  u 48
  sub
  stl w3

  ldl w2
  u 10
  mul
  ldl w3
  add
  stl w2

  ldl w10
  inc
  stl w10
  bra .Lln_loop

.Lln_done:
  ldl w2
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; basic_exec_line(w0 = ptr)
; ------------------------------------------------------------
basic_exec_line:
  stl w14
  ldl w0
  stl w10

  CALL tok_skip_spaces

  ; empty -> prompt on, return
  ldl w10
  lu
  i0
  beq .Lbasic_empty

  ; If we are RUNNING, NEVER treat leading digits as "program entry".
  i SYS_RUNNING
  lu
  i0
  bne .Lbasic_immediate

  ; program line? (first char is digit)
  ldl w10
  lu
  stl w1

  ldl w1
  u 48          ; '0'
  blt .Lbasic_immediate
  ldl w1
  u 58          ; ':' (one past '9')
  bge .Lbasic_immediate

  ; ---------------- program line entry ----------------
  CALL basic_parse_lineno      ; out: w0=lineNo, w10 advanced past digits
  ldl w0
  stl w2                       ; lineNo

  ; program entry: suppress READY between lines
  i SYS_PROMPT
  i0
  sb

  CALL tok_skip_spaces

  ; delete if empty after line number
  ldl w10
  lu
  i0
  beq .Lbasic_delete_line

  ; store/replace
  ldl w2
  stl w0
  ldl w10
  stl w1
  CALL prog_store_line
  bra .Lbasic_done

.Lbasic_delete_line:
  ldl w2
  stl w0
  CALL prog_delete_line
  bra .Lbasic_done

  ; ---------------- immediate/direct mode ----------------
.Lbasic_immediate:
  i SYS_PROMPT
  i1
  sb

  CALL stmt_try_end
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_goto
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_new
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_list
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_run
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_cls
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_help
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_if
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_let
  ldl w0
  i1
  beq .Lbasic_done

  CALL stmt_try_print
  ldl w0
  i1
  beq .Lbasic_done

  ; unknown
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  bra .Lbasic_done

.Lbasic_empty:
  i SYS_PROMPT
  i1
  sb

.Lbasic_done:
  ldl w14
  jr

banner:
  .ascii "R816 TINY BASIC v0.4.2 (IF/THEN)\r\n"
  .byte 0

ready:
  .ascii "READY.\r\n"
  .byte 0

err_syntax:
  .ascii "?SYNTAX ERROR"
  .byte 0

; includes at end
.include "BIOS/R816/con.asm"
.include "BIOS/R816/kbd.asm"
.include "BIOS/R816/BASIC/tok.asm"
.include "BIOS/R816/BASIC/vars.asm"
.include "BIOS/R816/BASIC/expr.asm"
.include "BIOS/R816/BASIC/stmt.asm"
.include "BIOS/R816/BASIC/prog.asm"
