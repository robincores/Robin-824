; BIOS/R816/BASIC/stmt.asm
; Statement classification + shared statement helpers.
; The dispatcher classifies the first identifier once and then jumps to a
; statement executor that parses only the statement tail.
;
; Conventions:
; - w10 is the parse pointer
; - stmt_dispatch() returns w0=1 handled, else 0
; - stmt_parse_kind() advances w10 past the first identifier and returns:
;     w0 = STMTK_*
;     w1 = identifier length (useful for shorthand assignment)

; ------------------------------------------------------------
; helper: stmt_print_i16(w0=value)
; prints signed decimal
; ------------------------------------------------------------
stmt_print_i16:
  stl w14
  ldl w0
  stl w2

  ; if (value & 0x8000)==0 => positive
  ldl w2
  i 0x8000
  and
  i0
  beq .Lpi_pos

  ; print '-'
  BIOS_PUTC 45

  ; value = -value
  ldl w2
  neg
  stl w2

.Lpi_pos:
  ldl w2
  stl w0
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PRINT_U16

  ldl w14
  jr

; ------------------------------------------------------------
; stmt_abort_if_running()
; ------------------------------------------------------------
stmt_abort_if_running:
  stl w14
  i SYS_RUNNING
  lu
  i0
  beq .Lsair_done
  i SYS_END_PEND
  u 1
  sb
.Lsair_done:
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_fail_syntax()
; prints ?SYNTAX ERROR, aborts RUN if active, returns handled=1
; ------------------------------------------------------------
stmt_fail_syntax:
  stl w14
  BIOS_PUTS_Z err_syntax
  BIOS_CRLF
  CALL stmt_abort_if_running
  u 1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_require_eol()
; skips trailing spaces; returns w0=1 iff at end of line
; ------------------------------------------------------------
stmt_require_eol:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  i0
  beq .Lsre_yes
  i0
  stl w0
  ldl w14
  jr
.Lsre_yes:
  u 1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_parse_line_target()
; parses a BASIC line target (<32768)
; out: w0=lineNo, w1=1 iff valid line target consumed
; ------------------------------------------------------------
stmt_parse_line_target:
  stl w14
  CALL tok_skip_spaces
  CALL tok_read_lineno
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_parse_then_keyword()
; parses THEN as either a tokenized keyword byte or exact identifier
; out: w0=1 iff THEN consumed, else 0
; ------------------------------------------------------------
stmt_parse_then_keyword:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u TOKB_THEN
  beq .Lspth_tok
  CALL tok_read_ident
  ldl w1
  i4
  beq .Lspth_len_ok
  i0
  stl w0
  ldl w14
  jr
.Lspth_tok:
  ldl w10
  inc
  stl w10
  u 1
  stl w0
  ldl w14
  jr
.Lspth_len_ok:
  ldl w1
  stl w7
  i kw_then
  stl w0
  u 4
  stl w1
  CALL stmt_ident_eq
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_parse_to_keyword()
; parses TO as either a tokenized keyword byte or exact identifier
; out: w0=1 iff TO consumed, else 0
; ------------------------------------------------------------
stmt_parse_to_keyword:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u TOKB_TO
  beq .Lspto_tok
  CALL tok_read_ident
  ldl w1
  i2
  beq .Lspto_len_ok
  i0
  stl w0
  ldl w14
  jr
.Lspto_tok:
  ldl w10
  inc
  stl w10
  u 1
  stl w0
  ldl w14
  jr
.Lspto_len_ok:
  ldl w1
  stl w7
  i kw_to_local
  stl w0
  u 2
  stl w1
  CALL stmt_ident_eq
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_parse_step_keyword()
; parses STEP as either a tokenized keyword byte or exact identifier
; out: w0=1 iff STEP consumed, else 0
; ------------------------------------------------------------
stmt_parse_step_keyword:
  stl w14
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u TOKB_STEP
  beq .Lspsk_tok
  CALL tok_read_ident
  ldl w1
  i4
  beq .Lspsk_len_ok
  i0
  stl w0
  ldl w14
  jr
.Lspsk_tok:
  ldl w10
  inc
  stl w10
  u 1
  stl w0
  ldl w14
  jr
.Lspsk_len_ok:
  ldl w1
  stl w7
  i kw_step_local
  stl w0
  u 4
  stl w1
  CALL stmt_ident_eq
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_scan_quoted_string()
; in:  w10 at opening quote
; out: w0=1 on success, 0 on unterminated/non-string
;      w2 = start ptr (first char after opening quote)
;      w3 = end ptr (closing quote position, exclusive)
;      w10 advanced past closing quote on success
; ------------------------------------------------------------
stmt_scan_quoted_string:
  stl w14
  CALL tok_peek
  ldl w0
  u TOKB_STR
  beq .Lsqs_tok
  ldl w0
  u 34
  beq .Lsqs_have_open
  i0
  stl w0
  ldl w14
  jr
.Lsqs_tok:
  ldl w10
  inc
  stl w10
  ldl w10
  lu
  stl w7
  ldl w10
  inc
  stl w10
  ldl w10
  stl w2
  ldl w10
  ldl w7
  add
  stl w3
  ldl w3
  stl w10
  u 1
  stl w0
  ldl w14
  jr
.Lsqs_have_open:
  ldl w10
  inc
  stl w10
  ldl w10
  stl w2
.Lsqs_loop:
  CALL tok_peek
  ldl w0
  i0
  beq .Lsqs_fail
  ldl w0
  u 34
  beq .Lsqs_close
  ldl w10
  inc
  stl w10
  bra .Lsqs_loop
.Lsqs_close:
  ldl w10
  stl w3
  ldl w10
  inc
  stl w10
  u 1
  stl w0
  ldl w14
  jr
.Lsqs_fail:
  i0
  stl w0
  ldl w14
  jr

; Statement kind enums
.equ STMTK_NONE   0
.equ STMTK_END    1
.equ STMTK_GOTO   2
.equ STMTK_GOSUB  3
.equ STMTK_RETURN 4
.equ STMTK_NEW    5
.equ STMTK_LIST   6
.equ STMTK_RUN    7
.equ STMTK_CLS    8
.equ STMTK_HELP   9
.equ STMTK_IF     10
.equ STMTK_FOR    11
.equ STMTK_NEXT   12
.equ STMTK_INPUT  13
.equ STMTK_LET    14
.equ STMTK_PRINT  15
.equ STMTK_REM    16
.equ STMTK_ASSIGN 17

; ------------------------------------------------------------
; stmt_ident_eq(w0=kwPtr, w1=kwLen, w7=currentLen) -> w0=1/0
; compares against VAR_NAME_BUF (uppercased by tok_read_ident)
; ------------------------------------------------------------
stmt_ident_eq:
  stl w14
  ldl w7
  ldl w1
  beq .Lsie_len_ok
  i0
  stl w0
  ldl w14
  jr
.Lsie_len_ok:
  i VAR_NAME_BUF
  stl w11
  ldl w0
  stl w12
  ldl w7
  stl w13
.Lsie_loop:
  ldl w13
  i0
  beq .Lsie_yes
  ldl w11
  lu
  stl w2
  ldl w12
  lu
  stl w3
  ldl w2
  ldl w3
  beq .Lsie_next
  i0
  stl w0
  ldl w14
  jr
.Lsie_next:
  ldl w11
  inc
  stl w11
  ldl w12
  inc
  stl w12
  ldl w13
  dec
  stl w13
  bra .Lsie_loop
.Lsie_yes:
  i1
  stl w0
  ldl w14
  jr

kw_end:    .ascii "END"
kw_goto:   .ascii "GOTO"
kw_gosub:  .ascii "GOSUB"
kw_return: .ascii "RETURN"
kw_new:    .ascii "NEW"
kw_list:   .ascii "LIST"
kw_run:    .ascii "RUN"
kw_cls:    .ascii "CLS"
kw_help:   .ascii "HELP"
kw_if:     .ascii "IF"
kw_for:    .ascii "FOR"
kw_next:   .ascii "NEXT"
kw_input:  .ascii "INPUT"
kw_let:    .ascii "LET"
kw_print:  .ascii "PRINT"
kw_rem:    .ascii "REM"
kw_then:   .ascii "THEN"

; ------------------------------------------------------------
; stmt_parse_kind()
; in:  w10 = parse pointer
; out: w0 = STMTK_*
;      w1 = first identifier length (0 if none)
;      w10 advanced past the first identifier when nonzero
;      returns STMTK_ASSIGN for a non-keyword identifier (A=5 style)
; ------------------------------------------------------------
stmt_parse_kind:
  stl w14
  CALL tok_skip_spaces

  ; tokenized stored-line fast path
  CALL tok_peek
  stl w3
  ldl w3
  i 0x80
  and
  i0
  beq .Lspk_text

  ldl w3
  u TOKB_END
  beq .Lspk_tok_end
  ldl w3
  u TOKB_GOTO
  beq .Lspk_tok_goto
  ldl w3
  u TOKB_GOSUB
  beq .Lspk_tok_gosub
  ldl w3
  u TOKB_RETURN
  beq .Lspk_tok_return
  ldl w3
  u TOKB_NEW
  beq .Lspk_tok_new
  ldl w3
  u TOKB_LIST
  beq .Lspk_tok_list
  ldl w3
  u TOKB_RUN
  beq .Lspk_tok_run
  ldl w3
  u TOKB_CLS
  beq .Lspk_tok_cls
  ldl w3
  u TOKB_HELP
  beq .Lspk_tok_help
  ldl w3
  u TOKB_IF
  beq .Lspk_tok_if
  ldl w3
  u TOKB_FOR
  beq .Lspk_tok_for
  ldl w3
  u TOKB_NEXT
  beq .Lspk_tok_next
  ldl w3
  u TOKB_INPUT
  beq .Lspk_tok_input
  ldl w3
  u TOKB_LET
  beq .Lspk_tok_let
  ldl w3
  u TOKB_PRINT
  beq .Lspk_tok_print
  ldl w3
  u TOKB_REM
  beq .Lspk_tok_rem
  ldl w3
  u TOKB_IDENT
  beq .Lspk_tok_ident
  ; non-statement token at line start
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr

.Lspk_tok_end:
  ldl w10
  inc
  stl w10
  u STMTK_END
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_goto:
  ldl w10
  inc
  stl w10
  u STMTK_GOTO
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_gosub:
  ldl w10
  inc
  stl w10
  u STMTK_GOSUB
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_return:
  ldl w10
  inc
  stl w10
  u STMTK_RETURN
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_new:
  ldl w10
  inc
  stl w10
  u STMTK_NEW
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_list:
  ldl w10
  inc
  stl w10
  u STMTK_LIST
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_run:
  ldl w10
  inc
  stl w10
  u STMTK_RUN
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_cls:
  ldl w10
  inc
  stl w10
  u STMTK_CLS
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_help:
  ldl w10
  inc
  stl w10
  u STMTK_HELP
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_if:
  ldl w10
  inc
  stl w10
  u STMTK_IF
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_for:
  ldl w10
  inc
  stl w10
  u STMTK_FOR
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_next:
  ldl w10
  inc
  stl w10
  u STMTK_NEXT
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_input:
  ldl w10
  inc
  stl w10
  u STMTK_INPUT
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_let:
  ldl w10
  inc
  stl w10
  u STMTK_LET
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_print:
  ldl w10
  inc
  stl w10
  u STMTK_PRINT
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_rem:
  ldl w10
  inc
  stl w10
  u STMTK_REM
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_tok_ident:
  CALL tok_read_ident
  u STMTK_ASSIGN
  stl w0
  ldl w14
  jr

.Lspk_text:
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lspk_have_ident
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lspk_have_ident:
  ldl w1
  stl w7

  i kw_end
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_end

  i kw_goto
  stl w0
  u 4
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_goto

  i kw_gosub
  stl w0
  u 5
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_gosub

  i kw_return
  stl w0
  u 6
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_return

  i kw_new
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_new

  i kw_list
  stl w0
  u 4
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_list

  i kw_run
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_run

  i kw_cls
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_cls

  i kw_help
  stl w0
  u 4
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_help

  i kw_if
  stl w0
  u 2
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_if

  i kw_for
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_for

  i kw_next
  stl w0
  u 4
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_next

  i kw_input
  stl w0
  u 5
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_input

  i kw_let
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_let

  i kw_print
  stl w0
  u 5
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_print

  i kw_rem
  stl w0
  u 3
  stl w1
  CALL stmt_ident_eq
  ldl w0
  i1
  beq .Lspk_rem

  u STMTK_ASSIGN
  stl w0
  ldl w7
  stl w1
  ldl w14
  jr

.Lspk_end:    u STMTK_END
              stl w0
              bra .Lspk_ret
.Lspk_goto:   u STMTK_GOTO
              stl w0
              bra .Lspk_ret
.Lspk_gosub:  u STMTK_GOSUB
              stl w0
              bra .Lspk_ret
.Lspk_return: u STMTK_RETURN
              stl w0
              bra .Lspk_ret
.Lspk_new:    u STMTK_NEW
              stl w0
              bra .Lspk_ret
.Lspk_list:   u STMTK_LIST
              stl w0
              bra .Lspk_ret
.Lspk_run:    u STMTK_RUN
              stl w0
              bra .Lspk_ret
.Lspk_cls:    u STMTK_CLS
              stl w0
              bra .Lspk_ret
.Lspk_help:   u STMTK_HELP
              stl w0
              bra .Lspk_ret
.Lspk_if:     u STMTK_IF
              stl w0
              bra .Lspk_ret
.Lspk_for:    u STMTK_FOR
              stl w0
              bra .Lspk_ret
.Lspk_next:   u STMTK_NEXT
              stl w0
              bra .Lspk_ret
.Lspk_input:  u STMTK_INPUT
              stl w0
              bra .Lspk_ret
.Lspk_let:    u STMTK_LET
              stl w0
              bra .Lspk_ret
.Lspk_print:  u STMTK_PRINT
              stl w0
              bra .Lspk_ret
.Lspk_rem:    u STMTK_REM
              stl w0
              bra .Lspk_ret
.Lspk_ret:
  ldl w7
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; stmt_dispatch()
; Classifies the first identifier once, then executes only the tail.
; in:  w10 parse pointer
; out: w0 = 1 handled, 0 no match
; ------------------------------------------------------------
stmt_dispatch:
  stl w14
  CALL stmt_parse_kind
  ldl w0
  stl w8                ; kind
  ldl w1
  stl w7                ; first ident len (for shorthand assignment)

  ldl w8
  i STMTK_NONE
  beq .Lsd_no

  ldl w8
  i STMTK_END
  beq .Lsd_end
  ldl w8
  u STMTK_GOTO
  beq .Lsd_goto
  ldl w8
  u STMTK_GOSUB
  beq .Lsd_gosub
  ldl w8
  u STMTK_RETURN
  beq .Lsd_return
  ldl w8
  u STMTK_NEW
  beq .Lsd_new
  ldl w8
  u STMTK_LIST
  beq .Lsd_list
  ldl w8
  u STMTK_RUN
  beq .Lsd_run
  ldl w8
  u STMTK_CLS
  beq .Lsd_cls
  ldl w8
  u STMTK_HELP
  beq .Lsd_help
  ldl w8
  u STMTK_IF
  beq .Lsd_if
  ldl w8
  u STMTK_FOR
  beq .Lsd_for
  ldl w8
  u STMTK_NEXT
  beq .Lsd_next
  ldl w8
  u STMTK_INPUT
  beq .Lsd_input
  ldl w8
  u STMTK_LET
  beq .Lsd_let
  ldl w8
  u STMTK_PRINT
  beq .Lsd_print
  ldl w8
  u STMTK_REM
  beq .Lsd_rem

  ; shorthand assignment: first identifier already consumed into VAR_NAME_BUF
  ldl w7
  stl w1
  CALL stmt_exec_assign_ident
  ldl w14
  jr
.Lsd_end:    CALL stmt_exec_end
             ldl w14
             jr
.Lsd_goto:   CALL stmt_exec_goto
             ldl w14
             jr
.Lsd_gosub:  CALL stmt_exec_gosub
             ldl w14
             jr
.Lsd_return: CALL stmt_exec_return
             ldl w14
             jr
.Lsd_new:    CALL stmt_exec_new
             ldl w14
             jr
.Lsd_list:   CALL stmt_exec_list
             ldl w14
             jr
.Lsd_run:    CALL stmt_exec_run
             ldl w14
             jr
.Lsd_cls:    CALL stmt_exec_cls
             ldl w14
             jr
.Lsd_help:   CALL stmt_exec_help
             ldl w14
             jr
.Lsd_if:     CALL stmt_exec_if
             ldl w14
             jr
.Lsd_for:    CALL stmt_exec_for
             ldl w14
             jr
.Lsd_next:   CALL stmt_exec_next
             ldl w14
             jr
.Lsd_input:  CALL stmt_exec_input
             ldl w14
             jr
.Lsd_let:    CALL stmt_exec_let
             ldl w14
             jr
.Lsd_print:  CALL stmt_exec_print
             ldl w14
             jr
.Lsd_rem:    CALL stmt_exec_rem
             ldl w14
             jr
.Lsd_no:
  i0
  stl w0
  ldl w14
  jr

.include "ROBIN-16/BASIC/stmt_ctrl.asm"
.include "ROBIN-16/BASIC/stmt_prog.asm"
.include "ROBIN-16/BASIC/stmt_assign.asm"
.include "ROBIN-16/BASIC/stmt_io.asm"
.include "ROBIN-16/BASIC/stmt_gosubret.asm"
.include "ROBIN-16/BASIC/stmt_loop.asm"
.include "ROBIN-16/BASIC/stmt_validate.asm"
