.once

; ============================================================================
; BIOS public include (R816)
; ============================================================================
;
; Syscall ABI (from user code):
;   - w0 = syscall number (input) / return value (output)
;   - w1..w3 = arguments (u16 pointers/values unless noted)
;   - return value is placed in w0 (0 if not used)
;
; IMPORTANT:
;   The R8 core uses A/B/C as a 3-deep stack register set. ECALL/BIOS code will
;   clobber A/B/C unless you save/restore them.
;
; If you need A/B/C preserved (e.g. BASIC expression stack), use the BIOS_ECALL
; / BIOS_CALL* macros below.
;
; ----------------------------------------------------------------------------
; CSRs (from core)
; ----------------------------------------------------------------------------
.equ CSR_MTVEC  0x03
.equ CSR_MEPC   0x04

; ----------------------------------------------------------------------------
; Syscalls
; ----------------------------------------------------------------------------
.equ SYS_TERM_INIT   0x01
.equ SYS_PUTC        0x02
.equ SYS_PUTS_Z      0x03
.equ SYS_GETC_BLOCK  0x04
.equ SYS_READLINE    0x05
.equ SYS_CRLF        0x06
.equ SYS_CLS         0x07
.equ SYS_PRINT_U16   0x08

; ----------------------------------------------------------------------------
; Save/restore A/B/C (top-of-stack registers)
; ----------------------------------------------------------------------------
.macro BIOS_SAVE_ABC
  push
  push
  push
.endm

.macro BIOS_RESTORE_ABC
  pop
  pop
  pop
.endm

; ----------------------------------------------------------------------------
; Raw ECALL (no preservation)
; ----------------------------------------------------------------------------
.macro BIOS_ECALL_NS
  ecall
.endm

; ----------------------------------------------------------------------------
; ECALL with A/B/C preserved
; ----------------------------------------------------------------------------
.macro BIOS_ECALL
  BIOS_SAVE_ABC
  ecall
  BIOS_RESTORE_ABC
.endm

; ----------------------------------------------------------------------------
; Generic BIOS calls (preserve A/B/C)
; ----------------------------------------------------------------------------
.macro BIOS_CALL0 nr
  BIOS_SAVE_ABC
  u \nr
  stl w0
  ecall
  BIOS_RESTORE_ABC
.endm

.macro BIOS_CALL1 nr a1
  BIOS_SAVE_ABC
  u \nr
  stl w0
  i \a1
  stl w1
  ecall
  BIOS_RESTORE_ABC
.endm

.macro BIOS_CALL2 nr a1 a2
  BIOS_SAVE_ABC
  u \nr
  stl w0
  i \a1
  stl w1
  i \a2
  stl w2
  ecall
  BIOS_RESTORE_ABC
.endm

.macro BIOS_CALL3 nr a1 a2 a3
  BIOS_SAVE_ABC
  u \nr
  stl w0
  i \a1
  stl w1
  i \a2
  stl w2
  i \a3
  stl w3
  ecall
  BIOS_RESTORE_ABC
.endm

; ----------------------------------------------------------------------------
; Generic BIOS calls (NO preservation) - faster, but clobbers A/B/C
; ----------------------------------------------------------------------------
.macro BIOS_CALL0_NS nr
  u \nr
  stl w0
  ecall
.endm

.macro BIOS_CALL1_NS nr a1
  u \nr
  stl w0
  i \a1
  stl w1
  ecall
.endm

.macro BIOS_CALL2_NS nr a1 a2
  u \nr
  stl w0
  i \a1
  stl w1
  i \a2
  stl w2
  ecall
.endm

.macro BIOS_CALL3_NS nr a1 a2 a3
  u \nr
  stl w0
  i \a1
  stl w1
  i \a2
  stl w2
  i \a3
  stl w3
  ecall
.endm

; ----------------------------------------------------------------------------
; Friendly wrappers (preserve A/B/C)
; ----------------------------------------------------------------------------
.macro BIOS_TERM_INIT
  BIOS_CALL0 SYS_TERM_INIT
.endm

.macro BIOS_PUTC ch
  BIOS_CALL1 SYS_PUTC, \ch
.endm

.macro BIOS_PUTS_Z addr
  BIOS_CALL1 SYS_PUTS_Z, \addr
.endm

; returns: w0 = char (0..255)
.macro BIOS_GETC_BLOCK
  BIOS_CALL0 SYS_GETC_BLOCK
.endm

; args: buffer ptr, maxlen
; returns: w0 = length (0..maxlen)
.macro BIOS_READLINE buf maxlen
  BIOS_CALL2 SYS_READLINE, \buf, \maxlen
.endm

.macro BIOS_CRLF
  BIOS_CALL0 SYS_CRLF
.endm

.macro BIOS_CLS
  BIOS_CALL0 SYS_CLS
.endm

.macro BIOS_PRINT_U16 v
  BIOS_CALL1 SYS_PRINT_U16, \v
.endm
