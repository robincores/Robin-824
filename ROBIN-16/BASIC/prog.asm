; BIOS/R816/BASIC/prog.asm
; Sorted BASIC program store with tombstone deletes + on-demand compaction.
; Record format:
;   [0] lineLo
;   [1] lineHi (bit7 = deleted flag)
;   [2] payloadLenIncludingNul
;   [3..] tokenized text bytes including trailing NUL
;
; Stored program lines are tokenized to reduce RAM usage; LIST/RUN detokenize
; back to plain text when presenting or executing a line.
; The live program is kept in ascending line-number order.

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
; prog_reset_run_state()
; clears RUN control state/stacks defensively
; ------------------------------------------------------------
prog_reset_run_state:
  stl w14
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
  i SYS_RET_PTR_LO
  u 0
  sb
  i SYS_RET_PTR_HI
  u 0
  sb
  i SYS_NEXT_PTR_LO
  u 0
  sb
  i SYS_NEXT_PTR_HI
  u 0
  sb
  ldl w14
  jr

; ------------------------------------------------------------
; prog_free_bytes() -> w0 = PROG_LIMIT - PROG_TOP
; ------------------------------------------------------------
prog_free_bytes:
  stl w14
  i PROG_LIMIT
  stl w1
  CALL prog_get_top
  ldl w1
  ldl w0
  sub
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; prog_decode_record()
; in:  w10 = ptr, w11 = top
; out: w0 = 0 at end/top, 1 ok, 2 bad image
;      w9 = nextPtr
;      w12 = textLenIncludingNul
;      w4 = lineNo
;      w5 = 1 deleted, 0 live
; ------------------------------------------------------------
prog_decode_record:
  stl w14

  ; ptr >= top => end
  ldl w10
  ldl w11
  beq .Lpdr_eof
  ldl w10
  ldl w11
  bltu .Lpdr_check_hdr
  bra .Lpdr_eof

.Lpdr_check_hdr:
  ; require ptr + 3 <= top
  ldl w10
  u 3
  add
  stl w6
  ldl w6
  ldl w11
  beq .Lpdr_hdr_ok
  ldl w6
  ldl w11
  bltu .Lpdr_hdr_ok
  bra .Lpdr_bad

.Lpdr_hdr_ok:
  ldl w10
  u 2
  add
  lu
  stl w12
  ldl w12
  i0
  beq .Lpdr_bad

  ldl w10
  u 3
  add
  ldl w12
  add
  stl w9

  ; require next <= top and next > ptr
  ldl w10
  ldl w9
  bltu .Lpdr_next_gt_ptr
  bra .Lpdr_bad
.Lpdr_next_gt_ptr:
  ldl w9
  ldl w11
  beq .Lpdr_next_ok
  ldl w9
  ldl w11
  bltu .Lpdr_next_ok
  bra .Lpdr_bad
.Lpdr_next_ok:

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
  beq .Lpdr_live
  u 1
  stl w5
  bra .Lpdr_line
.Lpdr_live:
  i0
  stl w5

.Lpdr_line:
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

  u 1
  stl w0
  ldl w14
  jr

.Lpdr_eof:
  i0
  stl w0
  ldl w14
  jr

.Lpdr_bad:
  u 2
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; prog_report_bad_program()
; ------------------------------------------------------------
prog_report_bad_program:
  stl w14
  BIOS_PUTS_Z bad_prog_msg
  BIOS_CRLF
  ldl w14
  jr

; ------------------------------------------------------------
; prog_compact()
; compacts live records to PROG_BASE, skipping deleted lines.
; first validates the whole image to avoid half-legalizing corruption.
; out: w0 = 1 success, 0 bad program image
; ------------------------------------------------------------
prog_compact:
  stl w14
  CALL prog_get_top
  ldl w0
  stl w11

  ; validate whole image first
  i PROG_BASE
  stl w10
.Lppc_v_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lppc_copy_start
  ldl w0
  u 2
  bne .Lppc_v_next
  i0
  stl w0
  ldl w14
  jr
.Lppc_v_next:
  ldl w9
  stl w10
  bra .Lppc_v_loop

.Lppc_copy_start:
  i PROG_BASE
  stl w10
  i PROG_BASE
  stl w13
.Lppc_copy_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lppc_done
  ldl w0
  u 2
  bne .Lppc_copy_live_check
  i0
  stl w0
  ldl w14
  jr
.Lppc_copy_live_check:
  ldl w5
  i0
  beq .Lppc_copy_live
  ldl w9
  stl w10
  bra .Lppc_copy_loop
.Lppc_copy_live:
  ldl w10
  stl w6
  ldl w13
  stl w7
  ldl w12
  u 3
  add
  stl w8
.Lppc_copy_bytes:
  ldl w8
  i0
  beq .Lppc_copy_done
  ldl w6
  lu
  stl w1
  ldl w7
  ldl w1
  sb
  ldl w6
  inc
  stl w6
  ldl w7
  inc
  stl w7
  ldl w8
  dec
  stl w8
  bra .Lppc_copy_bytes
.Lppc_copy_done:
  ldl w7
  stl w13
  ldl w9
  stl w10
  bra .Lppc_copy_loop

.Lppc_done:
  ldl w13
  stl w0
  CALL prog_set_top
  u 1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; prog_find_line_ptr_status(w0=lineNo)
; out: w0 = 0 not found, 1 found, 2 bad image
;      w1 = ptr when found else 0
; ------------------------------------------------------------
prog_find_line_ptr_status:
  stl w14
  ldl w0
  stl w7

  CALL prog_get_top
  ldl w0
  stl w11
  i PROG_BASE
  stl w10

.Lpfls_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lpfls_not
  ldl w0
  u 2
  bne .Lpfls_have_rec
  u 2
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lpfls_have_rec:
  ldl w4
  ldl w7
  beq .Lpfls_maybe_found
  ldl w7
  ldl w4
  bltu .Lpfls_not
  ldl w9
  stl w10
  bra .Lpfls_loop

.Lpfls_maybe_found:
  ldl w5
  i0
  beq .Lpfls_found
  ; deleted same-line record: continue, newer live same-line insert would be before it
  ldl w9
  stl w10
  bra .Lpfls_loop

.Lpfls_found:
  u 1
  stl w0
  ldl w10
  stl w1
  ldl w14
  jr

.Lpfls_not:
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; prog_find_line_ptr(w0=lineNo) -> w0=ptr or 0 if not found/bad
; legacy wrapper
; ------------------------------------------------------------
prog_find_line_ptr:
  stl w14
  CALL prog_find_line_ptr_status
  ldl w0
  i1
  beq .Lpfl_wrap_found
  i0
  stl w0
  ldl w14
  jr
.Lpfl_wrap_found:
  ldl w1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; prog_find_insert_ptr(w0=lineNo)
; out: w0 = 0 ok, 2 bad image
;      w1 = insertion ptr (before first record with line >= target)
; ------------------------------------------------------------
prog_find_insert_ptr:
  stl w14
  ldl w0
  stl w7
  CALL prog_get_top
  ldl w0
  stl w11
  i PROG_BASE
  stl w10
.Lpfi_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lpfi_at_top
  ldl w0
  u 2
  bne .Lpfi_have
  u 2
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Lpfi_have:
  ldl w4
  ldl w7
  blt .Lpfi_advance
  i0
  stl w0
  ldl w10
  stl w1
  ldl w14
  jr
.Lpfi_advance:
  ldl w9
  stl w10
  bra .Lpfi_loop
.Lpfi_at_top:
  i0
  stl w0
  ldl w11
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; prog_delete_line(w0=lineNo)
; tombstones matching live records
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
.Lpdl_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lpdl_exit
  ldl w0
  u 2
  bne .Lpdl_have
  CALL prog_report_bad_program
  ldl w14
  jr
.Lpdl_have:
  ldl w4
  ldl w7
  beq .Lpdl_same
  ldl w7
  ldl w4
  bltu .Lpdl_exit
  ldl w9
  stl w10
  bra .Lpdl_loop
.Lpdl_same:
  ldl w5
  i0
  bne .Lpdl_next
  ldl w10
  u 1
  add
  stl w13
  ldl w13
  lu
  u 0x80
  or
  stl w6
  ldl w13
  ldl w6
  sb
.Lpdl_next:
  ldl w9
  stl w10
  bra .Lpdl_loop
.Lpdl_exit:
  ldl w14
  jr

; ------------------------------------------------------------
; prog_store_line(w0=lineNo, w1=ptrText NUL-terminated)
; stores line in sorted order, compacting only if free space is insufficient.
; ------------------------------------------------------------
prog_store_line:
  stl w14
  ldl w0
  stl w7               ; lineNo
  ldl w1
  stl w8               ; textPtr

  ldl w8
  lu
  i0
  beqfar .Lps_done

  ; reject line numbers >= 32768 (bit15 collides with deleted flag)
  ldl w7
  srl 4
  srl 4
  u 0x80
  and
  i0
  beq .Lps_line_ok
  BIOS_PUTS_Z err_syntax
  BIOS_CRLF
  bra .Lps_done

.Lps_line_ok:
  ; tokenize text into TOK_LINE_BUF (keyword compression)
  ldl w8
  stl w0
  i TOK_LINE_BUF
  stl w1
  CALL tok_tokenize_line
  ldl w0
  stl w12              ; tokenized length including trailing NUL
  i TOK_LINE_BUF
  stl w8               ; tokenized payload ptr

  ; tombstone previous live line if present
  ldl w7
  stl w0
  CALL prog_delete_line

  ; find insertion point in current physical order
  ldl w7
  stl w0
  CALL prog_find_insert_ptr
  ldl w0
  u 2
  bne .Lps_insert_ok
  CALL prog_report_bad_program
  bra .Lps_done
.Lps_insert_ok:
  ldl w1
  stl w13              ; insertPtr

  ; recSize = 3 + textLen
  ldl w12
  u 3
  add
  stl w6

  CALL prog_get_top
  ldl w0
  stl w9               ; top

  ; ensure capacity; compact only if needed
  ldl w9
  ldl w6
  add
  stl w5
  ldl w5
  i PROG_LIMIT
  beq .Lps_have_room
  ldl w5
  i PROG_LIMIT
  bltu .Lps_have_room

  ; spill caller-owned insert state: prog_compact() clobbers w6/w7/w8/w12
  i SYS_STORE_LINE_LO
  ldl w7
  u 0xFF
  and
  sb
  i SYS_STORE_LINE_HI
  ldl w7
  srl 4
  srl 4
  u 0x7F
  and
  sb
  i SYS_STORE_TXT_LO
  ldl w8
  u 0xFF
  and
  sb
  i SYS_STORE_TXT_HI
  ldl w8
  srl 4
  srl 4
  sb
  i SYS_STORE_LEN
  ldl w12
  u 0xFF
  and
  sb
  i SYS_STORE_RECSZ
  ldl w6
  u 0xFF
  and
  sb

  CALL prog_compact
  ldl w0
  i1
  beq .Lps_compact_ok
  CALL prog_report_bad_program
  bra .Lps_done
.Lps_compact_ok:
  ; restore caller-owned insert state after compaction
  i SYS_STORE_LINE_LO
  lu
  stl w1
  i SYS_STORE_LINE_HI
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w7

  i SYS_STORE_TXT_LO
  lu
  stl w1
  i SYS_STORE_TXT_HI
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w8

  i SYS_STORE_LEN
  lu
  stl w12
  i SYS_STORE_RECSZ
  lu
  stl w6

  ; re-find insertion point after compaction
  ldl w7
  stl w0
  CALL prog_find_insert_ptr
  ldl w0
  u 2
  bne .Lps_after_compact_find_ok
  CALL prog_report_bad_program
  bra .Lps_done
.Lps_after_compact_find_ok:
  ldl w1
  stl w13
  CALL prog_get_top
  ldl w0
  stl w9
  ldl w9
  ldl w6
  add
  stl w5
  ldl w5
  i PROG_LIMIT
  beq .Lps_have_room
  ldl w5
  i PROG_LIMIT
  bltu .Lps_have_room
  BIOS_PUTS_Z oom_msg
  BIOS_CRLF
  bra .Lps_done

.Lps_have_room:
  ; shift [insertPtr, top) upward by recSize bytes, backwards
  ldl w13
  ldl w9
  beq .Lps_write
  ldl w9
  dec
  stl w10              ; src = top - 1
  ldl w9
  ldl w6
  add
  dec
  stl w11              ; dst = top + recSize - 1
.Lps_shift_loop:
  ldl w10
  ldl w13
  blt .Lps_write
  ldl w10
  lu
  stl w1
  ldl w11
  ldl w1
  sb
  ldl w10
  dec
  stl w10
  ldl w11
  dec
  stl w11
  bra .Lps_shift_loop

.Lps_write:
  ; header
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

  ldl w13
  ldl w1
  sb
  ldl w13
  u 1
  add
  ldl w2
  sb
  ldl w13
  u 2
  add
  ldl w12
  u 0xFF
  and
  sb

  ; text bytes
  ldl w8
  stl w10
  ldl w13
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

  ldl w9
  ldl w6
  add
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
; ------------------------------------------------------------
prog_list:
  stl w14
  CALL prog_get_top
  ldl w0
  stl w11
  i PROG_BASE
  stl w10
.Lpl_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lpl_exit
  ldl w0
  u 2
  bne .Lpl_have
  CALL prog_report_bad_program
  ldl w14
  jr
.Lpl_have:
  ldl w5
  i0
  bne .Lpl_next

  ldl w4
  stl w0
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PRINT_U16
  BIOS_PUTC 32

  ; detokenize payload into LINE_BUF for human-readable LIST
  ldl w10
  u 3
  add
  stl w0
  i LINE_BUF
  stl w1
  CALL tok_detokenize_line

  i LINE_BUF
  stl w13
.Lpl_txt:
  ldl w13
  lu
  stl w0
  ldl w0
  i0
  beq .Lpl_eol
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PUTC
  ldl w13
  inc
  stl w13
  bra .Lpl_txt
.Lpl_eol:
  BIOS_CRLF

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
  CALL prog_reset_run_state
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
  CALL prog_find_line_ptr_status
  ldl w0
  i1
  beq .Lprfl_found
  ldl w0
  u 2
  beq .Lprfl_bad
  BIOS_PUTS_Z undef_msg
  BIOS_CRLF
  ldl w14
  jr
.Lprfl_bad:
  CALL prog_report_bad_program
  ldl w14
  jr
.Lprfl_found:
  i SYS_RUNNING
  u 1
  sb
  CALL prog_reset_run_state
  ldl w1
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

undef_msg:
  .ascii "?UNDEFINED LINE"
  .byte 0

; ------------------------------------------------------------
; prog_run_loop: w10=ptr, w11=top
; ------------------------------------------------------------
prog_run_loop:
  stl w14
.Lprl_loop:
  CALL prog_decode_record
  ldl w0
  i0
  beq .Lprl_exit
  ldl w0
  u 2
  bne .Lprl_have
  CALL prog_report_bad_program
  j .Lprl_exit
.Lprl_have:
  ; clear per-line control flags
  i SYS_GOTO_PEND
  u 0
  sb
  i SYS_END_PEND
  u 0
  sb

  ; nextPtr for nested control flow
  ldl w9
  u 0xFF
  and
  stl w1
  ldl w9
  srl 4
  srl 4
  stl w2
  i SYS_NEXT_PTR_LO
  ldl w1
  sb
  i SYS_NEXT_PTR_HI
  ldl w2
  sb

  ldl w5
  i0
  bne .Lprl_after_exec

  ; execute tokenized payload directly; preserve top across statement execution
  ldl w11
  push

  ldl w10
  u 3
  add
  stl w10
  CALL stmt_dispatch
  ldl w0
  i1
  beq .Lprl_exec_ok
  CALL stmt_fail_syntax
.Lprl_exec_ok:

  pop
  stl w11
  i SYS_NEXT_PTR_LO
  lu
  stl w1
  i SYS_NEXT_PTR_HI
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w13
  bra .Lprl_after_tail_setup

.Lprl_after_exec:
  ldl w9
  stl w13

.Lprl_after_tail_setup:
  ; RETURN pending?
  i SYS_RET_PEND
  lu
  i0
  beq .Lprl_check_end
  i SYS_RET_PEND
  u 0
  sb
  i SYS_RET_PTR_LO
  lu
  stl w1
  i SYS_RET_PTR_HI
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w10
  bra .Lprl_loop

.Lprl_check_end:
  i SYS_END_PEND
  lu
  i0
  beq .Lprl_check_goto
  i SYS_END_PEND
  u 0
  sb
  bra .Lprl_exit

.Lprl_check_goto:
  i SYS_GOTO_PEND
  lu
  i0
  beq .Lprl_adv
  i SYS_GOTO_PEND
  u 0
  sb

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
  ldl w0
  i0
  bne .Lprl_do_goto
  bra .Lprl_adv

.Lprl_do_goto:
  ldl w11
  stl w8
  CALL prog_find_line_ptr_status
  ldl w8
  stl w11
  ldl w0
  i1
  beq .Lprl_goto_found
  ldl w0
  u 2
  beq .Lprl_goto_bad
  BIOS_PUTS_Z undef_msg
  BIOS_CRLF
  bra .Lprl_exit
.Lprl_goto_bad:
  CALL prog_report_bad_program
  bra .Lprl_exit
.Lprl_goto_found:
  ldl w1
  stl w10
  bra .Lprl_loop

.Lprl_adv:
  ldl w13
  stl w10
  bra .Lprl_loop

.Lprl_exit:
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
