; BIOS/R816/BASIC/basic.asm
; R816 Tiny BASIC v0.4.3 (sorted store + strict statement tails)
;
; Notes:
; - Variables: QuickBASIC-ish (no type suffix), INT16 only in v0.4
; - Var names: case-insensitive, stored uppercased, max VAR_NAME_MAX chars

.arch r816
.width 8

.include "ROBIN-16/BIOS/ports.inc"
.include "ROBIN-16/BIOS/macros.inc"
.include "ROBIN-16/BIOS/bios.inc"

.include "ROBIN-16/BASIC/basic_mem.inc"

.text
.org 0x0000

start:
  i 0xBEFE
  stl sp

  ; --- install BIOS trap vector (required before any ECALL) ---
  i bios_trap
  csrw CSR_MTVEC


  BIOS_CALL0 SYS_TERM_INIT
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

  i SYS_RET_PEND
  u 0
  sb
  i SYS_GOSUB_SP
  u 0
  sb
  i SYS_FOR_SP
  u 0
  sb
  BIOS_PUTS_Z banner

.Lbasic_repl:
  i SYS_PROMPT
  lu
  i0
  beq .Lno_prompt
  BIOS_PUTS_Z ready

.Lno_prompt:
  BIOS_READLINE LINE_BUF, LINE_MAX

  i LINE_BUF
  stl w0
  CALL basic_exec_line

  bra .Lbasic_repl

; ------------------------------------------------------------
; basic_parse_lineno()
; in:  w10 = ptr at first digit
; out: w0 = line number, w1 = 1 iff valid BASIC line number consumed
;      w10 advanced past digits
; ------------------------------------------------------------
basic_parse_lineno:
  stl w14
  CALL tok_read_lineno
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
  beqfar .Lbasic_empty

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
  CALL basic_parse_lineno      ; out: w0=lineNo, w1=1 iff valid, w10 advanced
  ldl w1
  i1
  beq .Lbasic_lineno_ok
  BIOS_PUTS_Z err_syntax
  BIOS_CRLF
  brafar .Lbasic_done
.Lbasic_lineno_ok:
  ldl w0
  stl w2                       ; lineNo

  ; program entry: suppress READY between lines
  i SYS_PROMPT
  u 0
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
  brafar .Lbasic_done

.Lbasic_delete_line:
  ldl w2
  stl w0
  CALL prog_delete_line
  brafar .Lbasic_done

  ; ---------------- immediate/direct mode ----------------
.Lbasic_immediate:
  i SYS_PROMPT
  u 1
  sb

  CALL stmt_dispatch
  ldl w0
  i1
  beqfar .Lbasic_done

  ; unknown
  BIOS_PUTS_Z err_syntax
  BIOS_CRLF

  ; If we are RUNning, abort RUN on syntax error
  i SYS_RUNNING
  lu
  i0
  beq .Lbasic_done
  i SYS_END_PEND
  u 1
  sb
  bra .Lbasic_done

.Lbasic_empty:
  i SYS_PROMPT
  u 1
  sb

.Lbasic_done:
  ldl w14
  jr

banner:
  .ascii "R816 TINY BASIC v0.4.3 (SORTED/STRICT)\r\n"
  .byte 0

ready:
  .ascii "READY.\r\n"
  .byte 0

err_syntax:
  .ascii "?SYNTAX ERROR"
  .byte 0

; includes at end
.include "ROBIN-16/BIOS/con.asm"
.include "ROBIN-16/BIOS/kbd.asm"
.include "ROBIN-16/BIOS/trap.asm"
.include "ROBIN-16/BASIC/tok.asm"
.include "ROBIN-16/BASIC/vars.asm"
.include "ROBIN-16/BASIC/expr.asm"
.include "ROBIN-16/BASIC/stmt.asm"
.include "ROBIN-16/BASIC/prog.asm"
