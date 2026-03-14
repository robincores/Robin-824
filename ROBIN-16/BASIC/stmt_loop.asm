; FOR / NEXT.

; FOR I = expr TO expr [STEP expr]
stmt_exec_for:
  stl w14

  i SYS_RUNNING
  lu
  i0
  bne .Lsef_running
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_running:

  CALL tok_skip_spaces
  CALL tok_read_ident
  ldl w1
  i0
  bne .Lsef_ident_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_ident_ok:
  ldl w0
  stl w6                ; namePtr
  ldl w1
  stl w7                ; len

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
  beq .Lsef_have_eq
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_have_eq:
  ldl w10
  inc
  stl w10

  CALL expr_eval
  ldl w1
  i0
  bne .Lsef_start_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_start_ok:
  ldl w0
  stl w2                ; start value

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

  CALL stmt_parse_to_keyword
  ldl w0
  i1
  beq .Lsef_to_ok
.Lsef_to_bad:
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_to_ok:

  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i0
  bne .Lsef_limit_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_limit_ok:
  ldl w0
  stl w8                ; limit

  i1
  stl w9                ; default step = 1

  CALL tok_skip_spaces
  ldl w10
  stl w13               ; optional STEP start
  CALL tok_peek
  ldl w0
  i0
  beq .Lsef_step_done

  CALL stmt_parse_step_keyword
  ldl w0
  i1
  beq .Lsef_step_kw
.Lsef_step_done_restore:
  ldl w13
  stl w10
  bra .Lsef_step_done

.Lsef_step_kw:
  CALL tok_skip_spaces
  CALL expr_eval
  ldl w1
  i0
  bne .Lsef_step_val_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_step_val_ok:
  ldl w0
  stl w9

.Lsef_step_done:
  ; reject STEP 0
  ldl w9
  i0
  bne .Lsef_step_nonzero
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsef_step_nonzero:

  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsef_push
  CALL stmt_fail_syntax
  ldl w14
  jr

.Lsef_push:
  ; push frame (entryPtr, limit, step, loopPtr)
  i SYS_FOR_SP
  lu
  stl w5
  ldl w5
  i FOR_STACK_MAX
  bge .Lsef_ovf

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

.Lsef_ovf:
  BIOS_PUTS_Z for_ovf
  BIOS_CRLF
  CALL stmt_abort_if_running
  u 1
  stl w0
  ldl w14
  jr

for_ovf:
  .ascii "?FOR STACK"
  .byte 0
kw_to_local:
  .ascii "TO"
kw_step_local:
  .ascii "STEP"

; NEXT [ident]
stmt_exec_next:
  stl w14

  i SYS_RUNNING
  lu
  i0
  bne .Lsenx_running
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsenx_running:

  i SYS_FOR_SP
  lu
  stl w5
  ldl w5
  i0
  beq .Lsenx_uf

  ldl w5
  dec
  stl w5
  ldl w5
  sll 2
  sll 1
  i FOR_STACK_BASE
  add
  stl w10              ; framePtr

  ; entryPtr bytes
  ldl w10
  lu
  stl w12
  ldl w10
  inc
  lu
  stl w13

  ; optional identifier after NEXT
  CALL tok_skip_spaces
  CALL tok_peek
  ldl w0
  i0
  beq .Lsenx_after_ident

  CALL tok_read_ident
  ldl w1
  i0
  beq .Lsenx_after_ident

  ; compare parsed ident to variable in top frame
  ldl w13
  sll 4
  sll 4
  ldl w12
  add
  stl w6               ; entryPtr

  ldl w6
  lu
  stl w2               ; entry len
  ldl w2
  ldl w1
  beq .Lsenx_cmp_loop_prep
  bra .Lsenx_mis
.Lsenx_cmp_loop_prep:
  ldl w6
  u 1
  add
  stl w3               ; entry name ptr
  i VAR_NAME_BUF
  stl w4               ; parsed name ptr
  ldl w1
  stl w7               ; remaining
.Lsenx_cmp_loop:
  ldl w7
  i0
  beq .Lsenx_after_ident
  ldl w3
  lu
  stl w1
  ldl w4
  lu
  stl w2
  ldl w1
  ldl w2
  beq .Lsenx_cmp_next
  bra .Lsenx_mis
.Lsenx_cmp_next:
  ldl w3
  inc
  stl w3
  ldl w4
  inc
  stl w4
  ldl w7
  dec
  stl w7
  bra .Lsenx_cmp_loop

.Lsenx_after_ident:
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsenx_tail_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsenx_tail_ok:

  ; Reload frame pointer after stmt_require_eol clobbers w10
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
  bne .Lsenx_step_neg

  ; step >= 0 : exit if new > limit
  ldl w7
  ldl w8
  bgt .Lsenx_exit
  bra .Lsenx_continue

.Lsenx_step_neg:
  ; step < 0 : exit if new < limit
  ldl w7
  ldl w8
  blt .Lsenx_exit

.Lsenx_continue:
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

.Lsenx_exit:
  i SYS_FOR_SP
  ldl w5
  sb
  u 1
  stl w0
  ldl w14
  jr

.Lsenx_uf:
  BIOS_PUTS_Z nxt_uf
  BIOS_CRLF
  CALL stmt_abort_if_running
  u 1
  stl w0
  ldl w14
  jr

.Lsenx_mis:
  BIOS_PUTS_Z nxt_mis
  BIOS_CRLF
  CALL stmt_abort_if_running
  u 1
  stl w0
  ldl w14
  jr

nxt_uf:
  .ascii "?NEXT WITHOUT FOR"
  .byte 0
nxt_mis:
  .ascii "?NEXT MISMATCH"
  .byte 0
