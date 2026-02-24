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
