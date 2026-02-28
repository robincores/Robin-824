; BIOS ECALL trap handler
; Requires: csrr/csrw in r816.json
; Contract: uses w0..w3 args, returns in w0

;.include "ROBIN-16/BIOS/ports.inc"
;.include "ROBIN-16/BIOS/bios.inc"

bios_trap:
  ; --- return to instruction AFTER ECALL ---
  csrr CSR_MEPC      ; A = mepc
  inc                ; A = mepc + 1  (ECALL is 1 byte)
  csrw CSR_MEPC      ; mepc = A

  ; --- dispatch: if (w0 == SYS_xxx) ---
  ldl w0
  u SYS_PUTC
  beq .Ltrap_putc

  ldl w0
  u SYS_PUTS_Z
  beq .Ltrap_puts_z

  ldl w0
  u SYS_GETC_BLOCK
  beq .Ltrap_getc

  ldl w0
  u SYS_TERM_INIT
  beq .Ltrap_term_init

  ; default: return 0xFF (error/unknown syscall)
  u 0xFF
  stl w0
  iret

.Ltrap_putc:
  ; write char (w1) to TX_PORT
  i VPU_TX_PORT
  ldl w1
  sb
  i0
  stl w0
  iret

.Ltrap_puts_z:
  ; w1 = ptr
  ldl w1
  stl w10          ; ptr in w10

.Ltrap_puts_loop:
  ldl w10
  lu
  stl w11          ; ch

  ldl w11
  i0
  beq .Ltrap_puts_done

  i VPU_TX_PORT
  ldl w11
  sb

  ldl w10
  inc
  stl w10
  bra .Ltrap_puts_loop

.Ltrap_puts_done:
  i0
  stl w0
  iret

.Ltrap_getc:
.Lkbd_wait:
  i KBD_STATUS
  lu
  u 1
  and
  i0
  beq .Lkbd_wait

  i KBD_DATA
  lu
  stl w0
  iret

.Ltrap_term_init:
  ; minimal: same as your term_init body (inline or copy)
  ; (kept short here—move full body from your proven code)
  WB SYS_VIDWIN, VIDWIN_MMIO_BIT
  WB VPU_OVL_MODE, OVL_TEXT
  WB VPU_CTRL, CTRL_DEFAULT
  WB VPU_MODE, GFX_MODE
  WB VPU_TX_CTRL, TXCTRL_DEFAULT
  WB VPU_TX_CUR_START, 0x00
  WB VPU_TX_CUR_END,   0x0F
  WB VPU_TX_ORG_L, 0x00
  WB VPU_TX_ORG_H, 0x00
  WB VPU_TX_FINE_Y, 0x00
  WB VPU_TX_FINE_X, 0x00
  WB VPU_TX_ATTR, 0x0F
  WB VPU_TX_CMD, TXCMD_HOME_CLS

  i0
  stl w0
  iret