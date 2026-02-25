; BIOS/R816/BASIC/prog.asm
; Program store (sorted by line number; deleted lines are compacted)
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
; prog_compact()
; Physically removes deleted lines and packs program store.
; After this, all records in [PROG_BASE .. SYS_PROG_TOP) are live.
; ------------------------------------------------------------
prog_compact:
  stl w14

  CALL prog_get_top
  ldl w0
  stl w11                ; top

  i PROG_BASE
  stl w10                ; src
  i PROG_BASE
  stl w9                 ; dst

.Lpc_loop:
  ldl w10
  ldl w11
  bltu .Lpc_body
  bra .Lpc_done

.Lpc_body:
  ; len byte
  ldl w10
  u 2
  add
  lu
  stl w12                ; len

  ; recSize = 3 + len
  ldl w12
  u 3
  add
  stl w6                 ; recSize

  ; next = src + recSize
  ldl w10
  ldl w6
  add
  stl w13                ; next

  ; hi byte (deleted flag in bit7)
  ldl w10
  u 1
  add
  lu
  stl w2

  ; deleted?
  ldl w2
  u 0x80
  and
  i0
  bne .Lpc_skip

  ; if dst == src, no copy needed
  ldl w9
  ldl w10
  beq .Lpc_nocopy

  ; copy recSize bytes (forward copy is safe: dst <= src)
  ldl w10
  stl w3                 ; srcPtr
  ldl w9
  stl w4                 ; dstPtr
  ldl w6
  stl w5                 ; count

.Lpc_copy_loop:
  ldl w5
  i0
  beq .Lpc_copy_done

  ldl w3
  lu
  stl w1

  ldl w4
  ldl w1
  sb

  ldl w3
  inc
  stl w3
  ldl w4
  inc
  stl w4

  ldl w5
  dec
  stl w5
  bra .Lpc_copy_loop

.Lpc_copy_done:
  ; dst += recSize
  ldl w9
  ldl w6
  add
  stl w9
  bra .Lpc_adv

.Lpc_nocopy:
  ; dst = next
  ldl w13
  stl w9
  bra .Lpc_adv

.Lpc_skip:
  ; keep dst unchanged

.Lpc_adv:
  ldl w13
  stl w10
  bra .Lpc_loop

.Lpc_done:
  ; top = dst
  ldl w9
  stl w0
  CALL prog_set_top

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
; Marks line deleted. Also compacts store if a line was deleted,
; so deletes immediately reclaim memory.
; ------------------------------------------------------------
prog_delete_line:
  stl w14
  ldl w0
  stl w7

  i0
  stl w6                 ; didDelete=0

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
  ; len
  ldl w10
  u 2
  add
  lu
  stl w12

  ; lo/hi
  ldl w10
  lu
  stl w1
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
  bne .Lpd_next

  ; lineNo = (hi&0x7F)<<8 | lo
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
  ; set deleted flag in hi byte
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

  i1
  stl w6                 ; didDelete=1

.Lpd_next:
  ldl w10
  u 3
  add
  ldl w12
  add
  stl w10
  bra .Lpd_loop

.Lpd_exit:
  ; if deleted something, compact immediately
  ldl w6
  i0
  beq .Lpd_done

  CALL prog_compact

.Lpd_done:
  ldl w14
  jr

; ------------------------------------------------------------
; prog_store_line(w0=lineNo, w1=ptrText NUL-terminated)
; ------------------------------------------------------------
; Stores program lines in **sorted line-number order**.
; - Replaces any existing line with same number.
; - Compacts store to reclaim deleted space.
; ------------------------------------------------------------
prog_store_line:
  stl w14
  ldl w0
  stl w7                 ; lineNo
  ldl w1
  stl w8                 ; ptrText

  ; ignore if empty text (defensive): immediate return (avoid far branch)
  ldl w8
  lu
  i0
  bne .Lps_nonempty
  ldl w14
  jr
.Lps_nonempty:

  ; replace: delete old line (also compacts if found)
  ldl w7
  stl w0
  CALL prog_delete_line

  ; pack any remaining deleted lines (keeps store healthy)
  CALL prog_compact

  ; top
  CALL prog_get_top
  ldl w0
  stl w9                 ; top

  ; measure text length including NUL
  ldl w8
  stl w10
  i0
  stl w12                ; len=0

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
  ; recSize = 3 + len
  ldl w12
  u 3
  add
  stl w6                 ; recSize

  ; newTop = top + recSize
  ldl w9
  ldl w6
  add
  stl w5                 ; newTop

  ; OOM? (immediate return after printing, avoid far branch)
  ldl w5
  i PROG_LIMIT
  bltu .Lps_mem_ok

  i oom_msg
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  ldl w14
  jr

.Lps_mem_ok:
  ; ----------------------------------------------------------
  ; Find insertPtr = first line with lineNo >= newLineNo
  ; (store is compacted, so no deleted records here)
  ; ----------------------------------------------------------
  i PROG_BASE
  stl w10                ; scan ptr

.Lps_find_loop:
  ldl w10
  ldl w9
  bltu .Lps_find_body
  bra .Lps_found_end     ; insert at top

.Lps_find_body:
  ; len
  ldl w10
  u 2
  add
  lu
  stl w1

  ; next = ptr + 3 + len
  ldl w1
  u 3
  add
  stl w2
  ldl w10
  ldl w2
  add
  stl w13                ; next

  ; current lineNo
  ldl w10
  lu
  stl w3                 ; lo
  ldl w10
  u 1
  add
  lu
  u 0x7F
  and
  stl w4                 ; hi7

  ldl w4
  sll 4
  sll 4
  ldl w3
  add
  stl w4                 ; curLine

  ; if curLine < newLine, keep scanning
  ldl w4
  ldl w7
  bltu .Lps_find_next

  ; else: insert before current
  bra .Lps_found

.Lps_find_next:
  ldl w13
  stl w10
  bra .Lps_find_loop

.Lps_found_end:
  ldl w9
  stl w10

.Lps_found:
  ; w10 = insertPtr
  ; ----------------------------------------------------------
  ; Shift tail upward if insertPtr < top (backward copy)
  ; ----------------------------------------------------------
  ldl w10
  ldl w9
  beq .Lps_no_shift

  ; src = top - 1
  ldl w9
  dec
  stl w11

  ; dst = newTop - 1
  ldl w5
  dec
  stl w13

.Lps_shift_loop:
  ldl w11
  ldl w10
  bltu .Lps_shift_done   ; stop when src < insertPtr

  ldl w11
  lu
  stl w1

  ldl w13
  ldl w1
  sb

  ldl w11
  dec
  stl w11
  ldl w13
  dec
  stl w13
  bra .Lps_shift_loop

.Lps_shift_done:
.Lps_no_shift:

  ; ----------------------------------------------------------
  ; Write header at insertPtr
  ; ----------------------------------------------------------
  ldl w7
  u 0xFF
  and
  stl w1                 ; lo

  ldl w7
  srl 4
  srl 4
  u 0x7F
  and
  stl w2                 ; hi7 (bit7 clear)

  ldl w10
  ldl w1
  sb
  ldl w10
  u 1
  add
  ldl w2
  sb
  ldl w10
  u 2
  add
  ldl w12
  u 0xFF
  and
  sb

  ; ----------------------------------------------------------
  ; Copy text (including NUL) to insertPtr+3
  ; ----------------------------------------------------------
  ldl w8
  stl w11                ; srcText
  ldl w10
  u 3
  add
  stl w13                ; dstText

.Lps_copy_loop:
  ldl w11
  lu
  stl w1

  ldl w13
  ldl w1
  sb

  ldl w1
  i0
  beq .Lps_copy_done

  ldl w11
  inc
  stl w11
  ldl w13
  inc
  stl w13
  bra .Lps_copy_loop

.Lps_copy_done:
  ; update top
  ldl w5
  stl w0
  CALL prog_set_top

  ldl w14
  jr

oom_msg:
  .ascii "?OUT OF MEMORY"
  .byte 0

; ------------------------------------------------------------
; prog_list()
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
  ldl w10
  u 2
  add
  lu
  stl w12

  ldl w10
  lu
  stl w1
  ldl w10
  i1
  add
  lu
  stl w2

  ldl w2
  u 0x80
  and
  i0
  bne .Lpl_next

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
  CALL bios_print_u16

  u 32
  stl w0
  CALL bios_putc

  ldl w10
  u 3
  add
  stl w13

.Lpl_txt:
  ldl w13
  lu
  stl w0
  ldl w0
  i0
  beq .Lpl_eol
  CALL bios_putc
  ldl w13
  inc
  stl w13
  bra .Lpl_txt

.Lpl_eol:
  CALL bios_crlf

.Lpl_next:
  ldl w10
  u 3
  add
  ldl w12
  add
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
  i undef_msg
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
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
  i bad_prog_msg
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
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
  ; END?
  i SYS_END_PEND
  lu
  i0
  beq .Lrl_check_goto

  i SYS_END_PEND
  u 0
  sb
  brafar .Lrl_exit

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
  i undef_msg
  stl w0
  CALL bios_puts_z
  CALL bios_crlf
  brafar .Lrl_exit

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
