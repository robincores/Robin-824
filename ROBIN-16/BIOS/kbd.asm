; BIOS/R816/kbd.asm
; Keyboard routines (expects ports.inc + macros.inc + con.asm already included)

bios_kbd_getc_block:
  stl w14

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

  ldl w14
  jr

bios_kbd_readline:
  stl w14

  ldl w0
  stl w10
  i0
  stl w11
  ldl w1
  stl w12

.Lkbd_rl_loop:
  CALL bios_kbd_getc_block

  ldl w0
  u 13
  beq .Lkbd_rl_done

  ldl w0
  u 8
  beq .Lkbd_rl_bs

  ldl w0
  u 32
  blt .Lkbd_rl_loop

  ldl w11
  ldl w12
  bge .Lkbd_rl_loop

  ldl w10
  ldl w0
  sb

  CALL bios_putc

  ldl w10
  inc
  stl w10
  ldl w11
  inc
  stl w11

  bra .Lkbd_rl_loop

.Lkbd_rl_bs:
  ldl w11
  i0
  beq .Lkbd_rl_loop

  ldl w11
  dec
  stl w11
  ldl w10
  dec
  stl w10

  u 8
  stl w0
  CALL bios_putc
  u 32
  stl w0
  CALL bios_putc
  u 8
  stl w0
  CALL bios_putc

  bra .Lkbd_rl_loop

.Lkbd_rl_done:
  ldl w10
  i0
  sb

  CALL bios_crlf

  ldl w11
  stl w0

  ldl w14
  jr