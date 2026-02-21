.arch r816
.width 8

; ---------------- Ports (easy to change) ----------------
.equ SYS_MMIO_BASE     0xBFF0
.equ SYS_VIDWIN        SYS_MMIO_BASE + 0x0000
.equ VIDWIN_MMIO_BIT   0x04

.equ VPU_BASE          0xC000
.equ VPU_CTRL          VPU_BASE + 0x0000
.equ VPU_MODE          VPU_BASE + 0x0002
.equ VPU_TX_CTRL       VPU_BASE + 0x0007
.equ VPU_TEXT_BASE     VPU_BASE + 0x0300

.equ TEXT_COLS         80
.equ TEXT_ROWS         25
.equ TOTAL_CELLS       2000

start:
  ; stack
  i 0xBEFE
  stl sp

  ; select VPU MMIO window
  i SYS_VIDWIN
  u VIDWIN_MMIO_BIT
  sb

  ; enable VPU
  i VPU_CTRL
  u 0x01
  sb

  ; text mode (MODE=4)
  i VPU_MODE
  u 0x04
  sb

  ; cursor enable + blink (optional)
  i VPU_TX_CTRL
  u 0x0A
  sb

  ; ---------------- clear screen ----------------
  u 0x0F
  stl w6              ; attr

  i VPU_TEXT_BASE
  stl w10             ; ptr

  i TOTAL_CELLS
  stl w11             ; count (cells)

_clear_loop:
  ldl w11
  i0
  beq _clear_done

  ; char
  ldl w10
  u 0x20              ; ' '
  sb
  ldl w10
  inc
  stl w10

  ; attr
  ldl w10
  ldl w6
  sb
  ldl w10
  inc
  stl w10

  ldl w11
  dec
  stl w11
  j _clear_loop

_clear_done:

  ; ---------------- write "Hello, World!" at (0,0) ----------------
  i hello
  stl w10             ; str ptr

  i0
  stl w11             ; cell index

_write_loop:
  ldl w10
  lu
  stl w5              ; ch

  ldl w5
  i0
  beq _done           ; if ch == 0

  ; addr = VPU_TEXT_BASE + (cell*2)
  ldl w11
  sll                 ; *2
  stl w12

  i VPU_TEXT_BASE
  ldl w12
  add
  stl w12             ; addr

  ; write ch
  ldl w12
  ldl w5
  sb

  ; write attr
  ldl w12
  inc
  stl w12
  ldl w12
  ldl w6
  sb

  ; ptr++, cell++
  ldl w10
  inc
  stl w10
  ldl w11
  inc
  stl w11

  j _write_loop

_done:
  hlt

hello:
  .ascii "Hello, World!"
  .byte 0