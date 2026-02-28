; BIOS/R816/BASIC/stmt.asm
; Statements. Uses w10=parse ptr. Returns w0=1 handled else 0.
;
; v0.4 adds:
; - LET / assignment (INT16)
; - PRINT <expr>
; - IF <expr> <op> <expr> THEN <action>
;
; Conventions:
; - No numeric local labels (1f/1b). Use .Lxxx only.
; - Avoid u 0 / u 1 (use i0 / i1).

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
  u 45
  stl w0
  CALL bios_putc

  ; value = -value
  ldl w2
  neg
  stl w2

.Lpi_pos:
  ldl w2
  stl w0
  CALL bios_print_u16

  ldl w14
  jr

; ---------- IF ----------
; IF <expr> <op> <expr> THEN <action>
; <op> supports: =  <>  <  <=  >  >=   (signed 16-bit)
; <action> supports:
;   - <lineno>          (implied GOTO)
;   - GOTO <lineno>
;   - END
;   - PRINT <expr|string>
;   - LET / assignment
stmt_try_if:
  stl w14
  CALL tok_skip_spaces

  ldl w10
  stl w9                ; start ptr (for restore)

  ; --- match "IF" keyword (case-insensitive) ---
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 73                  ; 'I'
  beq .Lif_got_I
  i0
  stl w0
  ldl w14
  jr

.Lif_got_I:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 70                  ; 'F'
  beq .Lif_got_F

  ; not IF -> restore and return 0
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

.Lif_got_F:
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  ; left expr
  CALL expr_eval
  ldl w1
  i0
  bne .Lif_left_ok
  j .Lif_syntax
.Lif_left_ok:
  ldl w0
  stl w2                ; left

  CALL tok_skip_spaces

  ; parse operator into w5:
  ; 0 EQ, 1 NE, 2 LT, 3 LE, 4 GT, 5 GE
  CALL tok_peek
  stl w3                ; ch

  ; '='
  ldl w3
  u 61
  beq .Lif_op_eq

  ; '<'
  ldl w3
  u 60
  beq .Lif_op_lt

  ; '>'
  ldl w3
  u 62
  beq .Lif_op_gt

  j .Lif_syntax

.Lif_op_eq:
  i0
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lif_rhs

.Lif_op_lt:
  ; consume '<'
  ldl w10
  inc
  stl w10
  CALL tok_peek
  stl w3

  ; '<=' ?
  ldl w3
  u 61
  beq .Lif_op_le

  ; '<>' ?
  ldl w3
  u 62
  beq .Lif_op_ne

  ; plain '<'
  u 2
  stl w5
  bra .Lif_rhs

.Lif_op_le:
  u 3
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lif_rhs

.Lif_op_ne:
  i1
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lif_rhs

.Lif_op_gt:
  ; consume '>'
  ldl w10
  inc
  stl w10
  CALL tok_peek
  stl w3

  ; '>=' ?
  ldl w3
  u 61
  beq .Lif_op_ge

  ; plain '>'
  u 4
  stl w5
  bra .Lif_rhs

.Lif_op_ge:
  u 5
  stl w5
  ldl w10
  inc
  stl w10
  bra .Lif_rhs

.Lif_rhs:
  CALL tok_skip_spaces

  ; right expr
  CALL expr_eval
  ldl w1
  i0
  bne .Lif_right_ok
  j .Lif_syntax
.Lif_right_ok:
  ldl w0
  stl w4                ; right

  ; THEN keyword
  CALL tok_skip_spaces

  ; 'T'
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  beq .Lif_then_h
  j .Lif_syntax
.Lif_then_h:
  ldl w10
  inc
  stl w10

  ; 'H'
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 72
  beq .Lif_then_e
  j .Lif_syntax
.Lif_then_e:
  ldl w10
  inc
  stl w10

  ; 'E'
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  beq .Lif_then_n
  j .Lif_syntax
.Lif_then_n:
  ldl w10
  inc
  stl w10

  ; 'N'
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  beq .Lif_then_ok
  j .Lif_syntax
.Lif_then_ok:
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  ; Evaluate condition -> branch to action or skip
  ; Compare uses B=left, A=right
  ldl w5
  i0
  beq .Lif_cond_eq
  ldl w5
  i1
  beq .Lif_cond_ne
  ldl w5
  u 2
  beq .Lif_cond_lt
  ldl w5
  u 3
  beq .Lif_cond_le
  ldl w5
  u 4
  beq .Lif_cond_gt
  bra .Lif_cond_ge

.Lif_cond_eq:
  ldl w2
  ldl w4
  beq .Lif_do_action
  bra .Lif_skip

.Lif_cond_ne:
  ldl w2
  ldl w4
  bne .Lif_do_action
  bra .Lif_skip

.Lif_cond_lt:
  ldl w2
  ldl w4
  blt .Lif_do_action
  bra .Lif_skip

.Lif_cond_le:
  ldl w2
  ldl w4
  blt .Lif_do_action
  ldl w2
  ldl w4
  beq .Lif_do_action
  bra .Lif_skip

.Lif_cond_gt:
  ldl w2
  ldl w4
  blt .Lif_skip
  ldl w2
  ldl w4
  beq .Lif_skip
  bra .Lif_do_action

.Lif_cond_ge:
  ldl w2
  ldl w4
  bge .Lif_do_action
  bra .Lif_skip

.Lif_skip:
  i1
  stl w0
  ldl w14
  jr

.Lif_do_action:
  ; action starts at w10
  ldl w10
  stl w8

  ; digit? => implied GOTO <lineno>
  CALL tok_peek
  stl w6                ; ch
  ldl w6
  u 48
  blt .Lif_try_kw
  ldl w6
  u 58
  bge .Lif_try_kw

  CALL tok_read_u16
  ldl w1
  i0
  bne .Lif_have_lineno

  ; fallthrough to keyword tries
  ldl w8
  stl w10
  bra .Lif_try_kw

.Lif_have_lineno:
  ldl w0
  stl w7                ; line

  i SYS_RUNNING
  lu
  i0
  beq .Lif_direct_goto

  ; running -> pending goto
  ldl w7
  u 0xFF
  and
  stl w1
  ldl w7
  srl 4
  srl 4
  stl w2

  i SYS_GOTO_LO
  ldl w1
  sb
  i SYS_GOTO_HI
  ldl w2
  sb
  i SYS_GOTO_PEND
  i1
  sb

  bra .Lif_done

.Lif_direct_goto:
  CALL vars_init
  ldl w7
  stl w0
  CALL prog_run_from_line
  bra .Lif_done

.Lif_try_kw:
  ; Try GOTO / END / PRINT / LET (in that order)
  ldl w8
  stl w10
  CALL stmt_try_goto
  ldl w0
  i1
  beq .Lif_done

  ldl w8
  stl w10
  CALL stmt_try_end
  ldl w0
  i1
  beq .Lif_done

  ldl w8
  stl w10
  CALL stmt_try_print
  ldl w0
  i1
  beq .Lif_done

  ldl w8
  stl w10
  CALL stmt_try_let
  ldl w0
  i1
  beq .Lif_done

  j .Lif_syntax

.Lif_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf

  ; If we are RUNning, abort RUN on syntax error
  i SYS_RUNNING
  lu
  i0
  beq .Lif_no_abort
  i SYS_END_PEND
  i1
  sb
.Lif_no_abort:
  bra .Lif_done

.Lif_done:
  i1
  stl w0
  ldl w14
  jr

; ---------- END ----------
stmt_try_end:
  stl w14
  CALL tok_skip_spaces

  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69                  ; E
  beq .Lend_e
  i0
  stl w0
  ldl w14
  jr

.Lend_e:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78                  ; N
  beq .Lend_n
  i0
  stl w0
  ldl w14
  jr

.Lend_n:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 68                  ; D
  beq .Lend_ok
  i0
  stl w0
  ldl w14
  jr

.Lend_ok:
  i SYS_RUNNING
  lu
  i0
  beq .Lend_done

  i SYS_END_PEND
  i1
  sb

.Lend_done:
  i1
  stl w0
  ldl w14
  jr

; ---------- GOTO ----------
stmt_try_goto:
  stl w14
  CALL tok_skip_spaces

  ; G
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 71
  beq .Lgto_g
  i0
  stl w0
  ldl w14
  jr

.Lgto_g:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 79
  beq .Lgto_o1
  i0
  stl w0
  ldl w14
  jr

.Lgto_o1:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  beq .Lgto_t
  i0
  stl w0
  ldl w14
  jr

.Lgto_t:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 79
  beq .Lgto_ok
  i0
  stl w0
  ldl w14
  jr

.Lgto_ok:
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  ; parse line number using tok_read_u16
  CALL tok_read_u16
  ldl w1
  i0
  beq .Lgto_no

  ldl w0
  stl w7              ; line

  i SYS_RUNNING
  lu
  i0
  beq .Lgto_direct

  ; running: set pending goto
  ldl w7
  u 0xFF
  and
  stl w1

  ldl w7
  srl 4
  srl 4
  stl w2

  i SYS_GOTO_LO
  ldl w1
  sb
  i SYS_GOTO_HI
  ldl w2
  sb

  i SYS_GOTO_PEND
  i1
  sb

  i1
  stl w0
  ldl w14
  jr

.Lgto_direct:
  ; like RUN: reset vars
  CALL vars_init

  ldl w7
  stl w0
  CALL prog_run_from_line
  i1
  stl w0
  ldl w14
  jr

.Lgto_no:
  i0
  stl w0
  ldl w14
  jr

; ---------- NEW ----------
stmt_try_new:
  stl w14
  CALL tok_skip_spaces

  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  beq .Lnew_n
  i0
  stl w0
  ldl w14
  jr
.Lnew_n:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  beq .Lnew_e
  i0
  stl w0
  ldl w14
  jr
.Lnew_e:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 87
  beq .Lnew_ok
  i0
  stl w0
  ldl w14
  jr
.Lnew_ok:
  CALL prog_new
  CALL vars_init
  i1
  stl w0
  ldl w14
  jr

; ---------- LIST ----------
stmt_try_list:
  stl w14
  CALL tok_skip_spaces

  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 76
  beq .Llist_l
  i0
  stl w0
  ldl w14
  jr
.Llist_l:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 73
  beq .Llist_i
  i0
  stl w0
  ldl w14
  jr
.Llist_i:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 83
  beq .Llist_s
  i0
  stl w0
  ldl w14
  jr
.Llist_s:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  beq .Llist_ok
  i0
  stl w0
  ldl w14
  jr
.Llist_ok:
  CALL prog_list
  i1
  stl w0
  ldl w14
  jr

; ---------- RUN ----------
stmt_try_run:
  stl w14
  CALL tok_skip_spaces

  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 82
  beq .Lrun_r
  i0
  stl w0
  ldl w14
  jr
.Lrun_r:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 85
  beq .Lrun_u
  i0
  stl w0
  ldl w14
  jr
.Lrun_u:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  beq .Lrun_ok
  i0
  stl w0
  ldl w14
  jr
.Lrun_ok:
  CALL vars_init
  CALL prog_run
  i1
  stl w0
  ldl w14
  jr

; ---------- CLS ----------
stmt_try_cls:
  stl w14
  CALL tok_skip_spaces

  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 67
  beq .Lcls_c
  i0
  stl w0
  ldl w14
  jr
.Lcls_c:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 76
  beq .Lcls_l
  i0
  stl w0
  ldl w14
  jr
.Lcls_l:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 83
  beq .Lcls_ok
  i0
  stl w0
  ldl w14
  jr
.Lcls_ok:
  CALL bios_cls
  i1
  stl w0
  ldl w14
  jr

; ---------- HELP ----------
stmt_try_help:
  stl w14
  CALL tok_skip_spaces

  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 72
  beq .Lhelp_h
  i0
  stl w0
  ldl w14
  jr
.Lhelp_h:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  beq .Lhelp_e
  i0
  stl w0
  ldl w14
  jr
.Lhelp_e:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 76
  beq .Lhelp_l
  i0
  stl w0
  ldl w14
  jr
.Lhelp_l:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 80
  beq .Lhelp_ok
  i0
  stl w0
  ldl w14
  jr
.Lhelp_ok:
  i help_text
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i1
  stl w0
  ldl w14
  jr

help_text:
  .ascii "COMMANDS: NEW, LIST, RUN, GOTO, END, IF, LET, PRINT, CLS, HELP"
  .byte 0

; ---------- LET / assignment ----------
; supports:
;   LET X = <expr>
;   X = <expr>
stmt_try_let:
  stl w14

  CALL tok_skip_spaces
  ldl w10
  stl w9                ; start

  ; check keyword LET
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 76                  ; L
  bne .Llet_try_assign

  ; 'E'
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  bne .Llet_try_assign_restore

  ; 'T'
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  bne .Llet_try_assign_restore

  ; consume 'T'
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces
  bra .Llet_parse

.Llet_try_assign_restore:
  ldl w9
  stl w10

.Llet_try_assign:
  ; shorthand assignment: fallthrough
  ldl w9
  stl w10
  CALL tok_skip_spaces

.Llet_parse:
  ; ident -> (w0=buf, w1=len)
  CALL tok_read_ident
  ldl w1
  i0
  beq .Llet_no

  ldl w0
  stl w6                ; namePtr
  ldl w1
  stl w7                ; len

  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61                  ; '='
  beq .Llet_have_eq

  ; not assignment
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

.Llet_have_eq:
  ; consume '='
  ldl w10
  inc
  stl w10

  CALL expr_eval         ; w0=value, w1=ok
  ldl w1
  i0
  beq .Llet_syntax

  ldl w0
  stl w2                 ; value

  ldl w6
  stl w0
  ldl w7
  stl w1
  ldl w2
  stl w2
  CALL vars_set

  i1
  stl w0
  ldl w14
  jr

.Llet_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf

  ; If we are RUNning, abort RUN on syntax error
  i SYS_RUNNING
  lu
  i0
  beq .Llet_no_abort
  i SYS_END_PEND
  i1
  sb
.Llet_no_abort:
  i1
  stl w0
  ldl w14
  jr

.Llet_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

; ---------- PRINT ----------
stmt_try_print:
  stl w14
  CALL tok_skip_spaces

  ; match PRINT
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 80
  beq .Lpr_p
  i0
  stl w0
  ldl w14
  jr

.Lpr_p:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 82
  beq .Lpr_r
  i0
  stl w0
  ldl w14
  jr

.Lpr_r:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 73
  beq .Lpr_i
  i0
  stl w0
  ldl w14
  jr

.Lpr_i:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  beq .Lpr_n
  i0
  stl w0
  ldl w14
  jr

.Lpr_n:
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  beq .Lpr_ok
  i0
  stl w0
  ldl w14
  jr

.Lpr_ok:
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  CALL tok_peek
  ldl w0
  u 34
  beq .Lpr_string

  ; numeric expression
  CALL expr_eval
  ldl w1
  i0
  beq .Lpr_syntax

  ; print signed
  CALL stmt_print_i16
  CALL bios_crlf

  i1
  stl w0
  ldl w14
  jr

.Lpr_string:
  ; skip opening quote
  ldl w10
  inc
  stl w10

.Lpr_str_loop:
  CALL tok_peek
  ldl w0
  i0
  beq .Lpr_done

  ldl w0
  u 34
  beq .Lpr_done

  CALL bios_putc

  ldl w10
  inc
  stl w10
  bra .Lpr_str_loop

.Lpr_done:
  CALL bios_crlf
  i1
  stl w0
  ldl w14
  jr

.Lpr_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf

  ; If we are RUNning, abort RUN on syntax error
  i SYS_RUNNING
  lu
  i0
  beq .Lpr_no_abort
  i SYS_END_PEND
  i1
  sb
.Lpr_no_abort:
  i1
  stl w0
  ldl w14
  jr


; ---------- REM ----------
; REM <anything>
stmt_try_rem:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w9

  ; R
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 82
  bne .Lrem_no
  ldl w10
  inc
  stl w10
  ; E
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  bne .Lrem_no
  ldl w10
  inc
  stl w10
  ; M
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 77
  bne .Lrem_no

  ; consume M
  ldl w10
  inc
  stl w10

  ; delimiter after REM
  CALL tok_peek
  ldl w0
  u 32
  beq .Lrem_ok
  ldl w0
  u 9
  beq .Lrem_ok
  ldl w0
  i0
  beq .Lrem_ok
  bra .Lrem_no

.Lrem_ok:
  u 1
  stl w0
  ldl w14
  jr

.Lrem_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr


; ---------- INPUT ----------
; INPUT <ident>
stmt_try_input:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w9

  ; I N P U T
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 73
  bnefar .Lin_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  bnefar .Lin_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 80
  bnefar .Lin_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 85
  bnefar .Lin_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  bnefar .Lin_no

  ; consume T
  ldl w10
  inc
  stl w10

  ; delimiter
  CALL tok_peek
  ldl w0
  u 32
  beq .Lin_kw_ok
  ldl w0
  u 9
  beq .Lin_kw_ok
  ldl w0
  i0
  beq .Lin_kw_ok
  brafar .Lin_no
.Lin_kw_ok:
  CALL tok_skip_spaces

  ; ident
  CALL tok_read_ident
  ldl w1
  i0
  beq .Lin_syntax

  ldl w0
  stl w6              ; namePtr
  ldl w1
  stl w7              ; len

  ; prompt "? "
  u 63
  stl w0
  CALL bios_putc
  u 32
  stl w0
  CALL bios_putc

  i LINE_BUF
  stl w0
  i LINE_MAX
  stl w1
  CALL bios_kbd_readline

  i LINE_BUF
  stl w10
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i0
  beq .Lin_syntax

  ldl w0
  stl w2              ; value

  ldl w6
  stl w0
  ldl w7
  stl w1
  ldl w2
  stl w2
  CALL vars_set

  u 1
  stl w0
  ldl w14
  jr

.Lin_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lin_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr


; ---------- GOSUB ----------
; GOSUB <lineno>
stmt_try_gosub:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w9

  ; G O S U B
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 71
  bnefar .Lgs_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 79
  bnefar .Lgs_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 83
  bnefar .Lgs_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 85
  bnefar .Lgs_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 66
  bnefar .Lgs_no

  ; consume B
  ldl w10
  inc
  stl w10

  ; delimiter
  CALL tok_peek
  ldl w0
  u 32
  beq .Lgs_kw_ok
  ldl w0
  u 9
  beq .Lgs_kw_ok
  brafar .Lgs_no
.Lgs_kw_ok:
  CALL tok_skip_spaces

  ; must be RUNning
  i SYS_RUNNING
  lu
  i0
  beq .Lgs_syntax

  ; target line number
  CALL tok_read_u16
  ldl w1
  i0
  beq .Lgs_syntax
  ldl w0
  stl w7              ; target

  ; sp
  i SYS_GOSUB_SP
  lu
  stl w6
  ldl w6
  i GOSUB_STACK_MAX
  bge .Lgs_ovf

  ; addr = base + sp*2
  ldl w6
  ldl w6
  add
  i GOSUB_STACK_BASE
  add
  stl w5

  ; push return pointer = SYS_NEXT_PTR
  i SYS_NEXT_PTR_LO
  lu
  stl w1
  i SYS_NEXT_PTR_HI
  lu
  stl w2
  ldl w5
  ldl w1
  sb
  ldl w5
  inc
  ldl w2
  sb

  ; sp++
  ldl w6
  inc
  stl w6
  i SYS_GOSUB_SP
  ldl w6
  sb

  ; set GOTO pending to target line
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

.Lgs_ovf:
  i gosub_ovf
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lgs_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lgs_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

gosub_ovf:
  .ascii "?GOSUB STACK"
  .byte 0


; ---------- RETURN ----------
; RETURN
stmt_try_return:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w9

  ; R E T U R N
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 82
  bnefar .Lrt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  bnefar .Lrt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  bnefar .Lrt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 85
  bnefar .Lrt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 82
  bnefar .Lrt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  bne .Lrt_no

  ; consume N
  ldl w10
  inc
  stl w10

  ; must be RUNning
  i SYS_RUNNING
  lu
  i0
  beq .Lrt_syntax

  ; sp?
  i SYS_GOSUB_SP
  lu
  stl w6
  ldl w6
  i0
  beq .Lrt_uf

  ; sp--
  ldl w6
  dec
  stl w6
  i SYS_GOSUB_SP
  ldl w6
  sb

  ; addr = base + sp*2
  ldl w6
  ldl w6
  add
  i GOSUB_STACK_BASE
  add
  stl w5

  ; pop ptr bytes
  ldl w5
  lu
  stl w1
  ldl w5
  inc
  lu
  stl w2

  i SYS_RET_PTR_LO
  ldl w1
  sb
  i SYS_RET_PTR_HI
  ldl w2
  sb
  i SYS_RET_PEND
  u 1
  sb

  u 1
  stl w0
  ldl w14
  jr

.Lrt_uf:
  i ret_uf
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lrt_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lrt_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

ret_uf:
  .ascii "?RETURN WITHOUT GOSUB"
  .byte 0


; ---------- FOR ----------
; FOR I=expr TO expr [STEP expr]
stmt_try_for:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w9

  ; match FOR
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 70
  bnefar .Lfor_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 79
  bnefar .Lfor_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 82
  bnefar .Lfor_no
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  ; must be RUNning
  i SYS_RUNNING
  lu
  i0
  beqfar .Lfor_syntax

  ; ident
  CALL tok_read_ident
  ldl w1
  i0
  beqfar .Lfor_syntax
  ldl w0
  stl w6
  ldl w1
  stl w7

  ; entryPtr
  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_find_entry
  ldl w0
  stl w12

  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  u 61
  bnefar .Lfor_syntax
  ldl w10
  inc
  stl w10

  CALL expr_eval
  ldl w1
  i0
  beqfar .Lfor_syntax
  ldl w0
  stl w2

  ; set var = start
  ldl w2
  u 0xFF
  and
  stl w3
  ldl w2
  srl 4
  srl 4
  stl w4
  ldl w12
  i (1 + VAR_NAME_MAX)
  add
  ldl w3
  sb
  ldl w12
  i (2 + VAR_NAME_MAX)
  add
  ldl w4
  sb

  CALL tok_skip_spaces

  ; match TO
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  bnefar .Lfor_syntax
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 79
  bnefar .Lfor_syntax
  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  CALL expr_eval
  ldl w1
  i0
  beqfar .Lfor_syntax
  ldl w0
  stl w8

  i1
  stl w9            ; step=1

  ; optional STEP
  CALL tok_skip_spaces
  ldl w10
  stl w13
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 83
  bne .Lfor_push

  ; match STEP
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  bne .Lfor_step_fail
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  bne .Lfor_step_fail
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 80
  bne .Lfor_step_fail

  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  CALL expr_eval
  ldl w1
  i0
  beqfar .Lfor_syntax
  ldl w0
  stl w9
  bra .Lfor_push

.Lfor_step_fail:
  ldl w13
  stl w10

.Lfor_push:
  ; push frame (entryPtr, limit, step, loopPtr)
  i SYS_FOR_SP
  lu
  stl w5
  ldl w5
  i FOR_STACK_MAX
  bge .Lfor_ovf

  ldl w5
  sll 2
  sll 1
  i FOR_STACK_BASE
  add
  stl w10

  ; entryPtr
  ldl w12
  u 0xFF
  and
  stl w3
  ldl w12
  srl 4
  srl 4
  stl w4
  ldl w10
  ldl w3
  sb
  ldl w10
  inc
  ldl w4
  sb

  ; limit
  ldl w8
  u 0xFF
  and
  stl w3
  ldl w8
  srl 4
  srl 4
  stl w4
  ldl w10
  u 2
  add
  ldl w3
  sb
  ldl w10
  u 3
  add
  ldl w4
  sb

  ; step
  ldl w9
  u 0xFF
  and
  stl w3
  ldl w9
  srl 4
  srl 4
  stl w4
  ldl w10
  u 4
  add
  ldl w3
  sb
  ldl w10
  u 5
  add
  ldl w4
  sb

  ; loopPtr = SYS_NEXT_PTR
  i SYS_NEXT_PTR_LO
  lu
  stl w3
  i SYS_NEXT_PTR_HI
  lu
  stl w4
  ldl w10
  u 6
  add
  ldl w3
  sb
  ldl w10
  u 7
  add
  ldl w4
  sb

  ; sp++
  ldl w5
  inc
  stl w5
  i SYS_FOR_SP
  ldl w5
  sb

  u 1
  stl w0
  ldl w14
  jr

.Lfor_ovf:
  i for_ovf
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lfor_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lfor_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

for_ovf:
  .ascii "?FOR STACK"
  .byte 0


; ---------- NEXT ----------
; NEXT [I]
stmt_try_next:
  stl w14
  CALL tok_skip_spaces
  ldl w10
  stl w9

  ; match NEXT
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 78
  bnefar .Lnxt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 69
  bnefar .Lnxt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 88
  bnefar .Lnxt_no
  ldl w10
  inc
  stl w10
  CALL tok_peek
  CALL tok_to_upper
  ldl w0
  u 84
  bnefar .Lnxt_no

  ldl w10
  inc
  stl w10
  CALL tok_skip_spaces

  ; must be RUNning
  i SYS_RUNNING
  lu
  i0
  beqfar .Lnxt_syntax

  i SYS_FOR_SP
  lu
  stl w5
  ldl w5
  i0
  beqfar .Lnxt_uf

  ldl w5
  dec
  stl w5

  ldl w5
  sll 2
  sll 1
  i FOR_STACK_BASE
  add
  stl w10

  ; entryPtr bytes
  ldl w10
  lu
  stl w12
  ldl w10
  inc
  lu
  stl w13

  ; load limit
  ldl w10
  u 2
  add
  lu
  stl w1
  ldl w10
  u 3
  add
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w8

  ; load step
  ldl w10
  u 4
  add
  lu
  stl w1
  ldl w10
  u 5
  add
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w9

  ; load loopPtr
  ldl w10
  u 6
  add
  lu
  stl w3
  ldl w10
  u 7
  add
  lu
  stl w4

  ; entryPtr = (hi<<8)|lo
  ldl w13
  sll 4
  sll 4
  ldl w12
  add
  stl w6

  ; cur
  ldl w6
  i (1 + VAR_NAME_MAX)
  add
  lu
  stl w1
  ldl w6
  i (2 + VAR_NAME_MAX)
  add
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w7

  ; new=cur+step
  ldl w7
  ldl w9
  add
  stl w7

  ; store new
  ldl w7
  u 0xFF
  and
  stl w1
  ldl w7
  srl 4
  srl 4
  stl w2
  ldl w6
  i (1 + VAR_NAME_MAX)
  add
  ldl w1
  sb
  ldl w6
  i (2 + VAR_NAME_MAX)
  add
  ldl w2
  sb

  ; step sign?
  ldl w9
  srl 4
  srl 4
  u 0x80
  and
  i0
  bne .Lnxt_step_neg

  ; step >=0 : exit if new>limit
  ldl w7
  ldl w8
  bgt .Lnxt_exit
  bra .Lnxt_continue

.Lnxt_step_neg:
  ; step <0 : exit if new<limit
  ldl w7
  ldl w8
  blt .Lnxt_exit

.Lnxt_continue:
  i SYS_RET_PTR_LO
  ldl w3
  sb
  i SYS_RET_PTR_HI
  ldl w4
  sb
  i SYS_RET_PEND
  u 1
  sb

  u 1
  stl w0
  ldl w14
  jr

.Lnxt_exit:
  i SYS_FOR_SP
  ldl w5
  sb

  u 1
  stl w0
  ldl w14
  jr

.Lnxt_uf:
  i nxt_uf
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lnxt_syntax:
  i err_syntax
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  i SYS_END_PEND
  u 1
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lnxt_no:
  ldl w9
  stl w10
  i0
  stl w0
  ldl w14
  jr

nxt_uf:
  .ascii "?NEXT WITHOUT FOR"
  .byte 0
nxt_mis:
  .ascii "?NEXT MISMATCH"
  .byte 0

