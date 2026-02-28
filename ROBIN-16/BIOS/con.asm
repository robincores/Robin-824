; BIOS/R816/con.asm
; Console routines (expects ports.inc + macros.inc already included)

bios_term_init:
  stl w14

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

  ldl w14
  jr

bios_putc:
  stl w14
  i VPU_TX_PORT
  ldl w0
  sb
  ldl w14
  jr

bios_puts_z:
  stl w14
  ldl w0
  stl w10

.Lcon_pz_loop:
  ldl w10
  lu
  stl w1

  ldl w1
  i0
  beq .Lcon_pz_done

  ldl w1
  stl w0
  CALL bios_putc

  ldl w10
  inc
  stl w10
  bra .Lcon_pz_loop

.Lcon_pz_done:
  ldl w14
  jr

bios_crlf:
  stl w14
  u 13
  stl w0
  CALL bios_putc
  u 10
  stl w0
  CALL bios_putc
  ldl w14
  jr

bios_cls:
  stl w14
  WB VPU_TX_CMD, TXCMD_HOME_CLS
  ldl w14
  jr

; ------------------------------------------------------------
; bios_print_u16(w0 = unsigned 16-bit) prints decimal
; uses stack to reverse digits
; ------------------------------------------------------------
bios_print_u16:
  stl w14

  ldl w0
  i0
  beq .Lcon_pu_zero

  ldl w0
  stl w2        ; n
  i0
  stl w3        ; count

.Lcon_pu_push:
  ldl w2
  u 10
  mod
  stl w1

  ldl w2
  u 10
  div
  stl w2

  ldl w1
  u 48
  add
  push

  ldl w3
  inc
  stl w3

  ldl w2
  i0
  beq .Lcon_pu_pop
  bra .Lcon_pu_push

.Lcon_pu_pop:
  ldl w3
  i0
  beq .Lcon_pu_done

  pop
  stl w0
  CALL bios_putc

  ldl w3
  dec
  stl w3
  bra .Lcon_pu_pop

.Lcon_pu_zero:
  u 48
  stl w0
  CALL bios_putc

.Lcon_pu_done:
  ldl w14
  jr