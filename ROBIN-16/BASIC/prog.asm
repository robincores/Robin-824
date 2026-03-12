; BIOS/R816/BASIC/prog.asm
; Program store (append-only; deleted lines are marked)
; + RUN core (stable) + GOTO/END flags.

; ------------------------------------------------------------
; prog_new(): SYS_PROG_TOP = PROG_BASE
; ------------------------------------------------------------
prog_new:
  stl w14
  i SYS_PROG_TOP
  u (PROG_BASE & 0xFF)
  sb
  i (SYS_PROG_TOP + 1)
  u ((PROG_BASE >> 8) & 0xFF)
  sb
  ldl w14
  jr

; ------------------------------------------------------------
; prog_get_top() -> w0 = top (16-bit)
; ------------------------------------------------------------
prog_get_top:
  stl w14
  i SYS_PROG_TOP
  lu
  stl w1
  i (SYS_PROG_TOP + 1)
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; prog_set_top(w0 = top)
; ------------------------------------------------------------
prog_set_top:
  stl w14
  ldl w0
  u 0xFF
  and
  stl w1
  ldl w0
  srl 4
  srl 4
  stl w2
  i SYS_PROG_TOP
  ldl w1
  sb
  i (SYS_PROG_TOP + 1)
  ldl w2
  sb
  ldl w14
  jr

; ------------------------------------------------------------
; prog_find_line_ptr(w0=lineNo) -> w0=ptr or 0 if not found
; ------------------------------------------------------------
prog_find_line_ptr:
  stl w14
  ldl w0
  stl w7

  CALL prog_get_top
  ldl w0
  stl w11

  i PROG_BASE
  stl w10

.Lfl_loop:
  ldl w10
  ldl w11
  bltu .Lfl_body
  bra .Lfl_not

.Lfl_body:
  ldl w10
  u 2
  add
  lu
  stl w12

  ldl w10
  lu
  stl w1

  ldl w10
  u 1
  add
  lu
  stl w2

  ldl w2
  u 0x80
  and
  i0
  bne .Lfl_next

  ldl w2
  u 0x7F
  and
  stl w3

  ldl w3
  sll 4
  sll 4
  ldl w1
  add
  stl w4

  ldl w4
  ldl w7
  beq .Lfl_found

.Lfl_next:
  ldl w10
  u 3
  add
  ldl w12
  add
  stl w10
  bra .Lfl_loop

.Lfl_found:
  ldl w10
  stl w0
  ldl w14
  jr

.Lfl_not:
  i0
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; prog_delete_line(w0=lineNo)
; ------------------------------------------------------------
prog_delete_line:
  stl w14
  ldl w0
  stl w7

  CALL prog_get_top
  ldl w0
  stl w11

  i PROG_BASE
  stl w10

.Lpd_loop:
  ldl w10
  ldl w11
  bltu .Lpd_body
  bra .Lpd_exit

.Lpd_body:
  ldl w10
  u 2
  add
  lu
  stl w12

  ldl w10
  lu
  stl w1

  ldl w10
  u 1
  add
  lu
  stl w2

  ldl w2
  u 0x80
  and
  i0
  bne .Lpd_next

  ldl w2
  u 0x7F
  and
  stl w3

  ldl w3
  sll 4
  sll 4
  ldl w1
  add
  stl w4

  ldl w4
  ldl w7
  beq .Lpd_mark
  bra .Lpd_next

.Lpd_mark:
  ldl w10
  u 1
  add
  stl w13

  ldl w13
  lu
  u 0x80
  or
  stl w5

  ldl w13
  ldl w5
  sb

.Lpd_next:
  ldl w10
  u 3
  add
  ldl w12
  add
  stl w10
  bra .Lpd_loop

.Lpd_exit:
  ldl w14
  jr

; ------------------------------------------------------------
; prog_store_line(w0=lineNo, w1=ptrText NUL-terminated)
; ------------------------------------------------------------
prog_store_line:
  stl w14
  ldl w0
  stl w7
  ldl w1
  stl w8

  ldl w8
  lu
  i0
  beqfar .Lps_done

  ldl w7
  stl w0
  CALL prog_delete_line

  CALL prog_get_top
  ldl w0
  stl w9

  ldl w8
  stl w10
  i0
  stl w12

.Lps_len_loop:
  ldl w10
  lu
  stl w1

  ldl w12
  inc
  stl w12

  ldl w1
  i0
  beq .Lps_len_done

  ldl w10
  inc
  stl w10
  bra .Lps_len_loop

.Lps_len_done:
  ldl w9
  u 3
  add
  ldl w12
  add
  stl w6

  ldl w6
  i PROG_LIMIT
  bltu .Lps_mem_ok
  LIB_PUTS_Z_IMM oom_msg
  LIB_CRLF
  bra .Lps_done

.Lps_mem_ok:
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

  ldl w9
  ldl w1
  sb
  ldl w9
  u 1
  add
  ldl w2
  sb
  ldl w9
  u 2
  add
  ldl w12
  u 0xFF
  and
  sb

  ldl w8
  stl w10
  ldl w9
  u 3
  add
  stl w11

.Lps_copy_loop:
  ldl w10
  lu
  stl w1

  ldl w11
  ldl w1
  sb

  ldl w1
  i0
  beq .Lps_copy_done

  ldl w10
  inc
  stl w10
  ldl w11
  inc
  stl w11
  bra .Lps_copy_loop

.Lps_copy_done:
  ldl w6
  stl w0
  CALL prog_set_top

.Lps_done:
  ldl w14
  jr

oom_msg:
  .ascii "?OUT OF MEMORY"
  .byte 0

; ------------------------------------------------------------
; prog_list()
; Safe version: uses stored record length, not NUL scan.
; Record format:
;   +0 line low
;   +1 line high (bit7=deleted)
;   +2 text length INCLUDING trailing NUL
;   +3 text bytes...
; ------------------------------------------------------------
prog_list:
  stl w14

  CALL prog_get_top
  ldl w0
  stl w11

  i PROG_BASE
  stl w10

.Lpl_loop:
  ldl w10
  ldl w11
  bltu .Lpl_body
  bra .Lpl_exit

.Lpl_body:
  ; w12 = record text length INCLUDING trailing NUL
  ldl w10
  u 2
  add
  lu
  stl w12

  ; Corrupt/empty record guard
  ldl w12
  i0
  beq .Lpl_exit

  ; Precompute next record pointer: w9 = w10 + 3 + w12
  ldl w10
  u 3
  add
  ldl w12
  add
  stl w9

  ; Read line number header
  ldl w10
  lu
  stl w1

  ldl w10
  i1
  add
  lu
  stl w2

  ; Skip deleted line if bit7 set
  ldl w2
  u 0x80
  and
  i0
  bne .Lpl_next

  ; Reconstruct line number into w0
  ldl w2
  u 0x7F
  and
  stl w3

  ldl w3
  sll 4
  sll 4
  ldl w1
  add
  stl w0

  LIB_PRINT_U16_W0
  LIB_PUTC_IMM 32

  ; Printable text length = stored length - 1 (exclude trailing NUL)
  ldl w12
  i1
  sub
  stl w6

  ; w13 = start of text
  ldl w10
  u 3
  add
  stl w13

.Lpl_txt:
  ldl w6
  i0
  beq .Lpl_eol

  ldl w13
  lu
  stl w0
  LIB_PUTC_W0

  ldl w13
  inc
  stl w13

  ldl w6
  dec
  stl w6
  bra .Lpl_txt

.Lpl_eol:
  LIB_CRLF

.Lpl_next:
  ldl w9
  stl w10
  bra .Lpl_loop

.Lpl_exit:
  ldl w14
  jr

; ------------------------------------------------------------
; prog_run()
; ------------------------------------------------------------
prog_run:
  stl w14

  i SYS_RUNNING
  u 1
  sb
  i SYS_END_PEND
  u 0
  sb
  i SYS_GOTO_PEND
  u 0
  sb

  i SYS_RET_PEND
  u 0
  sb
  i SYS_FOR_SP
  u 0
  sb
  i SYS_GOSUB_SP
  u 0
  sb

  CALL prog_get_top
  ldl w0
  stl w11

  i PROG_BASE
  stl w10

  CALL prog_run_loop

  i SYS_RUNNING
  u 0
  sb

  ldl w14
  jr

; ------------------------------------------------------------
; prog_run_from_line(w0=lineNo)
; ------------------------------------------------------------
prog_run_from_line:
  stl w14

  CALL prog_find_line_ptr
  ldl w0
  i0
  beq .Lrfl_nf

  i SYS_RUNNING
  u 1
  sb
  i SYS_END_PEND
  u 0
  sb
  i SYS_GOTO_PEND
  u 0
  sb

  ldl w0
  stl w10

  CALL prog_get_top
  ldl w0
  stl w11

  CALL prog_run_loop

  i SYS_RUNNING
  u 0
  sb

  ldl w14
  jr

.Lrfl_nf:
  LIB_PUTS_Z_IMM undef_msg
  LIB_CRLF
  ldl w14
  jr

undef_msg:
  .ascii "?UNDEFINED LINE"
  .byte 0

; ------------------------------------------------------------
; prog_run_loop: w10=ptr, w11=top
; ------------------------------------------------------------
prog_run_loop:
  stl w14

.Lrl_loop:
  ldl w10
  ldl w11
  bltu .Lrl_body
  brafar .Lrl_exit

.Lrl_body:
  ; Clear per-line control flags (defensive against stale state)
  i SYS_GOTO_PEND
  u 0
  sb
  i SYS_END_PEND
  u 0
  sb

  ; len
  ldl w10
  u 2
  add
  lu
  stl w12

  ; nextPtr
  ldl w10
  u 3
  add
  ldl w12
  add
  stl w13

  ; Safety: if nextPtr <= ptr, abort RUN to avoid infinite loop
  ldl w10
  ldl w13
  bltu .Lrl_np_ok
  LIB_PUTS_Z_IMM bad_prog_msg
  LIB_CRLF
  j .Lrl_exit
.Lrl_np_ok:

  ; hi
  ldl w10
  i1
  add
  lu
  stl w2

  ; deleted?
  ldl w2
  u 0x80
  and
  i0
  bne .Lrl_after_exec

  ; exec at ptr+3
  ; preserve top and nextPtr across basic_exec_line (it clobbers w11/w13)
  ldl w11
  push
  ldl w13
  push

  ldl w10
  u 3
  add
  stl w0
  CALL basic_exec_line

  pop
  stl w13
  pop
  stl w11

.Lrl_after_exec:
  ; RETURN pending? (jump to stored record pointer)
  i SYS_RET_PEND
  lu
  i0
  beq .Lrl_check_end

  i SYS_RET_PEND
  u 0
  sb

  i SYS_RET_PTR_LO
  lu
  stl w1
  i SYS_RET_PTR_HI
  lu
  stl w2

  ; retPtr = (hi<<8) | lo
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w10
  bra .Lrl_loop

.Lrl_check_end:
  ; END?
  i SYS_END_PEND
  lu
  i0
  beq .Lrl_check_goto

  i SYS_END_PEND
  u 0
  sb
  bra .Lrl_exit

.Lrl_check_goto:
  i SYS_GOTO_PEND
  lu
  i0
  beq .Lrl_adv

  i SYS_GOTO_PEND
  u 0
  sb

  ; build lineNo
  i SYS_GOTO_LO
  lu
  stl w1
  i SYS_GOTO_HI
  lu
  stl w2

  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w0

  ; if lineNo == 0, ignore (spurious)
  ldl w0
  i0
  bne .Lrl_goto_do
  j .Lrl_adv

.Lrl_goto_do:
  ; preserve top
  ldl w11
  stl w8

  CALL prog_find_line_ptr

  ldl w8
  stl w11

  ldl w0
  i0
  beq .Lrl_goto_nf

  ldl w0
  stl w10
  j .Lrl_loop

.Lrl_goto_nf:
  LIB_PUTS_Z_IMM undef_msg
  LIB_CRLF
  bra .Lrl_exit

.Lrl_adv:
  ldl w13
  stl w10
  j .Lrl_loop

.Lrl_exit:
  i SYS_END_PEND
  u 0
  sb
  i SYS_GOTO_PEND
  u 0
  sb

  ldl w14
  jr

bad_prog_msg:
  .ascii "?BAD PROGRAM"
  .byte 0
