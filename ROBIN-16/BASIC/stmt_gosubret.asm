; GOSUB / RETURN.

; GOSUB <lineno>
stmt_exec_gosub:
  stl w14
  i SYS_RUNNING
  lu
  i0
  bne .Lsegs_running
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsegs_running:
  CALL tok_skip_spaces
  CALL stmt_parse_line_target
  ldl w1
  i0
  bne .Lsegs_line_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsegs_line_ok:
  ldl w0
  stl w7
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsegs_eol_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsegs_eol_ok:

  i SYS_GOSUB_SP
  lu
  stl w6
  ldl w6
  i GOSUB_STACK_MAX
  bge .Lsegs_ovf

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

.Lsegs_ovf:
  BIOS_PUTS_Z gosub_ovf
  BIOS_CRLF
  CALL stmt_abort_if_running
  u 1
  stl w0
  ldl w14
  jr

gosub_ovf:
  .ascii "?GOSUB STACK"
  .byte 0

; RETURN
stmt_exec_return:
  stl w14
  CALL stmt_require_eol
  ldl w0
  i1
  beq .Lsert_eol_ok
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsert_eol_ok:

  i SYS_RUNNING
  lu
  i0
  bne .Lsert_running
  CALL stmt_fail_syntax
  ldl w14
  jr
.Lsert_running:

  i SYS_GOSUB_SP
  lu
  stl w6
  ldl w6
  i0
  beq .Lsert_uf

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

.Lsert_uf:
  BIOS_PUTS_Z ret_uf
  BIOS_CRLF
  CALL stmt_abort_if_running
  u 1
  stl w0
  ldl w14
  jr

ret_uf:
  .ascii "?RETURN WITHOUT GOSUB"
  .byte 0
