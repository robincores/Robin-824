.once

; ROBIN-16/LIB/io.inc
; Shared console/syscall marshaling helpers.
; These macros hide the w0->w1 / w1->w2 argument shuffles for runtime values.

.include "ROBIN-16/LIB/sys.inc"

.macro LIB_PUTC_IMM ch
  BIOS_PUTC \ch
.endm

.macro LIB_PUTC_W0
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PUTC
.endm

.macro LIB_PUTS_Z_IMM addr
  BIOS_PUTS_Z \addr
.endm

.macro LIB_PUTS_Z_W0
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PUTS_Z
.endm

.macro LIB_PRINT_U16_IMM value
  BIOS_PRINT_U16 \value
.endm

.macro LIB_PRINT_U16_W0
  ldl w0
  stl w1
  BIOS_CALL0 SYS_PRINT_U16
.endm

.macro LIB_READLINE_IMM buf maxlen
  BIOS_READLINE \buf, \maxlen
.endm

.macro LIB_READLINE_W0W1
  ldl w1
  stl w2
  ldl w0
  stl w1
  BIOS_CALL0 SYS_READLINE
.endm

.macro LIB_CRLF
  BIOS_CRLF
.endm

.macro LIB_CLS
  BIOS_CLS
.endm
