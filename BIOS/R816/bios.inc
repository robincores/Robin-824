; BIOS public include

; CSRs (from core)
.equ CSR_MTVEC  0x03
.equ CSR_MEPC   0x04

; Syscalls
.equ SYS_TERM_INIT   0x01
.equ SYS_PUTC        0x02
.equ SYS_PUTS_Z      0x03
.equ SYS_GETC_BLOCK  0x04
.equ SYS_READLINE    0x05

; Preserve A/B/C across syscalls (important for BASIC)
.macro BIOS_ECALL
  push
  push
  push
  ecall
  pop
  pop
  pop
.endm

.macro BIOS_CALL0 nr
  push
  push
  push
  u \nr
  stl w0
  ecall
  pop
  pop
  pop
.endm

.macro BIOS_CALL1 nr a1
  push
  push
  push
  u \nr
  stl w0
  i \a1
  stl w1
  ecall
  pop
  pop
  pop
.endm