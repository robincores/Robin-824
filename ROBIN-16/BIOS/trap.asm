.once

; BIOS ECALL trap handler
; Contract (syscall ABI):
;   w0 = syscall number
;   w1..w3 = args
;   return in w0
;
; Included by the master translation unit after bios.inc/macros.inc.

bios_trap:
  ; --- return to instruction AFTER ECALL ---
  csrr CSR_MEPC      ; A = mepc
  inc                ; A = mepc + 1  (ECALL is 1 byte)
  csrw CSR_MEPC      ; mepc = A

  ; --- dispatch on w0 (syscall number) ---
  ldl w0
  u SYS_TERM_INIT
  beq .Ltrap_term_init

  ldl w0
  u SYS_PUTC
  beq .Ltrap_putc

  ldl w0
  u SYS_PUTS_Z
  beq .Ltrap_puts_z

  ldl w0
  u SYS_GETC_BLOCK
  beq .Ltrap_getc_block

  ldl w0
  u SYS_READLINE
  beq .Ltrap_readline

  ldl w0
  u SYS_CRLF
  beq .Ltrap_crlf

  ldl w0
  u SYS_CLS
  beq .Ltrap_cls

  ldl w0
  u SYS_PRINT_U16
  beq .Ltrap_print_u16

  ; unknown syscall => return 0x00FF
  u 0xFF
  stl w0
  iret

.Ltrap_term_init:
  CALL bios_term_init
  iret

.Ltrap_putc:
  ldl w1
  stl w0
  CALL bios_putc
  iret

.Ltrap_puts_z:
  ldl w1
  stl w0
  CALL bios_puts_z
  iret

.Ltrap_getc_block:
  CALL bios_kbd_getc_block
  iret

.Ltrap_readline:
  ldl w1
  stl w0
  ldl w2
  stl w1
  CALL bios_kbd_readline
  iret

.Ltrap_crlf:
  CALL bios_crlf
  iret

.Ltrap_cls:
  CALL bios_cls
  iret

.Ltrap_print_u16:
  ldl w1
  stl w0
  CALL bios_print_u16
  iret
