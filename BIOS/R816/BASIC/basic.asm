; ============================================================
; R816 BASIC Terminal v0.0 (Text + Keyboard, HW scroll origin)
; - VPU regs (per VPU.java):
;     CTRL        = 0x0000
;     MODE        = 0x0002
;     TX_CTRL     = 0x0007
;     CUR_X/Y     = 0x0008/0x0009
;     CUR_START/END = 0x000A/0x000B
;     TEXT_BASE   = 0x0300 (cell*2: char, attr)
;     ORIGIN_L/H  = 0x0013/0x0014
;     TX_CMD      = 0x0019   (fast helpers)
;     TX_ATTR     = 0x001A
;     TX_PORT     = 0x001B
; - Uses ONLY .Lxxx local labels
; - Uses BRA for local unconditional flow (no J)
; ============================================================

.arch r816
.width 8

; -----------------------------
; Easy-to-change ports / regs
; -----------------------------

; MMIO region (system)
.equ SYS_MMIO_BASE     0xBFF0
.equ SYS_VIDWIN        (SYS_MMIO_BASE + 0x00)
.equ VIDWIN_WIN_MMIO   0x04          ; bit2

; Video window base
.equ VPU_WIN_BASE      0xC000

; VPU register offsets
.equ VPU_REG_CTRL         0x0000
.equ VPU_REG_MODE         0x0002
.equ VPU_REG_TX_CTRL      0x0007
.equ VPU_REG_TX_CUR_X     0x0008
.equ VPU_REG_TX_CUR_Y     0x0009
.equ VPU_REG_TX_CUR_START 0x000A
.equ VPU_REG_TX_CUR_END   0x000B
.equ VPU_REG_TX_ORIGIN_L  0x0013
.equ VPU_REG_TX_ORIGIN_H  0x0014

; Text helper regs (fast path)
.equ VPU_REG_TX_CMD        0x0019
.equ VPU_REG_TX_ATTR       0x001A
.equ VPU_REG_TX_PORT       0x001B

; Absolute addresses (VPU in MMIO mode)
.equ VPU_CTRL          (VPU_WIN_BASE + VPU_REG_CTRL)
.equ VPU_MODE          (VPU_WIN_BASE + VPU_REG_MODE)
.equ VPU_TX_CTRL       (VPU_WIN_BASE + VPU_REG_TX_CTRL)
.equ VPU_CUR_X         (VPU_WIN_BASE + VPU_REG_TX_CUR_X)
.equ VPU_CUR_Y         (VPU_WIN_BASE + VPU_REG_TX_CUR_Y)
.equ VPU_CUR_START     (VPU_WIN_BASE + VPU_REG_TX_CUR_START)
.equ VPU_CUR_END       (VPU_WIN_BASE + VPU_REG_TX_CUR_END)
.equ VPU_ORG_L         (VPU_WIN_BASE + VPU_REG_TX_ORIGIN_L)
.equ VPU_ORG_H         (VPU_WIN_BASE + VPU_REG_TX_ORIGIN_H)

.equ VPU_TX_CMD        (VPU_WIN_BASE + VPU_REG_TX_CMD)
.equ VPU_TX_ATTR       (VPU_WIN_BASE + VPU_REG_TX_ATTR)
.equ VPU_TX_PORT       (VPU_WIN_BASE + VPU_REG_TX_PORT)

; Text backing RAM inside VPU MMIO space
.equ VPU_TEXT_BASE     (VPU_WIN_BASE + 0x0300)

; Text geometry
.equ TEXT_COLS         80
.equ TEXT_ROWS         25
.equ TOTAL_CELLS       (TEXT_COLS * TEXT_ROWS)       ; 2000
.equ BOTTOM_ROW_OFS    ((TEXT_ROWS - 1) * TEXT_COLS) ; 1920

; TX_CTRL bits
.equ TX_CURSOR_EN      0x02
.equ TX_CURSOR_BLINK   0x08

; TX_CMD bits (write-only)
.equ TXCMD_CLR_EOL     0x01
.equ TXCMD_CLR_LINE    0x02
.equ TXCMD_CLR_SCREEN  0x04
.equ TXCMD_SCROLL_UP   0x08
.equ TXCMD_HOME        0x10

; Keyboard MMIO
.equ KBD_BASE          0xBF00
.equ KBD_DATA          (KBD_BASE + 0x00)
.equ KBD_STATUS        (KBD_BASE + 0x01)             ; bit0=hasData

; RAM layout for this demo
.equ LINE_BUF          0x9100
.equ LINE_MAX          159

; -----------------------------
; Helpers / macros
; -----------------------------

.macro WB addr, val
  i \addr
  u \val
  sb
.endm

.macro PROLOG
  stl w14
  ldl sp
  u 2
  sub
  stl sp
  ldl sp
  ldl w14
  st
.endm

.macro EPILOG
  ldl sp
  ld
  stl w14
  ldl sp
  u 2
  add
  stl sp
  ldl w14
  jr
.endm

; ---------------------------------------
; Terminal state (workspace regs)
;   w6 = attr (bg<<4 | fg)
;   w7 = origin (0..1999)
;   w8 = curX
;   w9 = curY
; Scratch: w10..w13
; ---------------------------------------

.text
.org 0x0000

start:
    ; SP just below MMIO (0xBF00..)
    i 0xBEFE
    stl sp

    jal term_init

    i banner
    stl w0
    jal term_puts_z

repl:
    i ready
    stl w0
    jal term_puts_z

    i LINE_BUF
    stl w0
    jal kbd_readline

    bra repl


; ============================================================
; Terminal
; ============================================================

term_init:
    PROLOG

    WB SYS_VIDWIN, VIDWIN_WIN_MMIO

    WB VPU_CTRL, 1
    WB VPU_MODE, 4

    ; Cursor on + blink (MODE 4 always renders text; TX_EN not required)
    WB VPU_TX_CTRL, (TX_CURSOR_EN | TX_CURSOR_BLINK)

    ; Cursor shape
    WB VPU_CUR_START, 0
    WB VPU_CUR_END, 15

    ; Default colors: fg=0x0F, bg=0x00 => attr 0x0F
    u 0x0F
    stl w6

    ; Tell the VPU helpers what attr to use for CLR_SCREEN/SCROLL_UP clears
    i VPU_TX_ATTR
    ldl w6
    sb

    i0
    stl w7
    i0
    stl w8
    i0
    stl w9

    jal term_write_origin
    jal term_cls
    jal term_sync_cursor

    EPILOG


term_sync_cursor:
    PROLOG

    i VPU_CUR_X
    ldl w8
    sb

    i VPU_CUR_Y
    ldl w9
    sb

    EPILOG


term_write_origin:
    PROLOG

    ldl w7
    i 0x00FF
    and
    stl w12

    ldl w7
    srl 4
    srl 4
    i 0x00FF
    and
    stl w13

    i VPU_ORG_L
    ldl w12
    sb

    i VPU_ORG_H
    ldl w13
    sb

    EPILOG


; FAST CLS: HOME + CLR_SCREEN (uses TX_ATTR)
term_cls:
    PROLOG

    ; Ensure helpers have current attr
    i VPU_TX_ATTR
    ldl w6
    sb

    ; Reset our software state
    i0
    stl w7
    i0
    stl w8
    i0
    stl w9

    ; Force HW origin = 0
    jal term_write_origin

    ; HOME + CLR_SCREEN
    WB VPU_TX_CMD, (TXCMD_HOME | TXCMD_CLR_SCREEN)

    ; Keep cursor regs consistent (optional; HOME already did it)
    jal term_sync_cursor

    EPILOG


; term_putc: w0 = ascii
term_putc:
    PROLOG

    ldl w0
    stl w5

    ldl w5
    u 13
    beq .Lcr

    ldl w5
    u 10
    beq .Llf

    ldl w5
    u 8
    beq .Lbs

    ldl w8
    i TEXT_COLS
    bge .Llf

    bra .Lnormal

.Lcr:
    i0
    stl w8
    jal term_sync_cursor
    bra .Lret

.Llf:
    i0
    stl w8
    ldl w9
    inc
    stl w9

    ldl w9
    i TEXT_ROWS
    blt .Llf_sync

    jal term_scroll_up
    i (TEXT_ROWS - 1)
    stl w9

.Llf_sync:
    jal term_sync_cursor
    bra .Lret

.Lbs:
    ldl w8
    i0
    beq .Lret

    ldl w8
    dec
    stl w8
    jal term_sync_cursor

    u 0x20
    stl w0
    jal term_putc

    ldl w8
    dec
    stl w8
    jal term_sync_cursor
    bra .Lret

.Lnormal:
    ; screenCell = origin + y*80 + x  (mod 2000)
    ; y*80 = (y<<6) + (y<<4)
    ldl w9
    sll 4
    stl w12

    ldl w9
    sll 4
    sll 2
    stl w13

    ldl w13
    ldl w12
    add
    stl w12

    ldl w12
    ldl w8
    add
    stl w12

    ldl w12
    ldl w7
    add
    stl w12

    ldl w12
    i TOTAL_CELLS
    blt .Lcell_ok
    ldl w12
    i TOTAL_CELLS
    sub
    stl w12
.Lcell_ok:

    ; addr = TEXT_BASE + (cell<<1)
    ldl w12
    sll
    stl w13
    i VPU_TEXT_BASE
    ldl w13
    add
    stl w10

    ; write char
    ldl w10
    ldl w5
    sb

    ; write attr
    ldl w10
    inc
    stl w10
    ldl w10
    ldl w6
    sb

    ; x++
    ldl w8
    inc
    stl w8

    jal term_sync_cursor

.Lret:
    EPILOG


; FAST SCROLL: TX_CMD_SCROLL_UP clears last row using TX_ATTR
term_scroll_up:
    PROLOG

    ; Ensure helpers have current attr
    i VPU_TX_ATTR
    ldl w6
    sb

    ; Ask VPU to scroll+clear bottom row
    WB VPU_TX_CMD, TXCMD_SCROLL_UP

    ; Keep our software origin in sync: origin = (origin + 80) mod 2000
    ldl w7
    i TEXT_COLS
    add
    stl w7

    ldl w7
    i TOTAL_CELLS
    blt .Lorg_ok
    ldl w7
    i TOTAL_CELLS
    sub
    stl w7
.Lorg_ok:

    ; Make sure HW origin matches our w7 (safe even if TX_CMD already updated it)
    jal term_write_origin

    EPILOG


term_puts_z:
    PROLOG

    ldl w0
    stl w5

.Lputs_loop:
    ldl w5
    lu
    stl w1

    ldl w1
    i0
    beq .Lputs_done

    ldl w1
    stl w0
    jal term_putc

    ldl w5
    inc
    stl w5
    bra .Lputs_loop

.Lputs_done:
    EPILOG


; ============================================================
; Keyboard
; ============================================================

kbd_getc_block:
    PROLOG

.Lkwait:
    i KBD_STATUS
    lu
    i1
    and
    i0
    beq .Lkwait

    i KBD_DATA
    lu
    stl w0

    EPILOG


kbd_readline:
    PROLOG

    ldl w0
    stl w5
    i0
    stl w11

.Lrl_loop:
    jal kbd_getc_block

    ldl w0
    u 13
    beq .Lrl_done
    ldl w0
    u 10
    beq .Lrl_done

    ldl w0
    u 8
    beq .Lrl_bs

    bra .Lrl_char

.Lrl_done:
    ldl w5
    i0
    sb

    u 13
    stl w0
    jal term_putc
    u 10
    stl w0
    jal term_putc

    EPILOG

.Lrl_bs:
    ldl w11
    i0
    beq .Lrl_loop

    ldl w11
    dec
    stl w11
    ldl w5
    dec
    stl w5

    u 8
    stl w0
    jal term_putc
    u 32
    stl w0
    jal term_putc
    u 8
    stl w0
    jal term_putc

    bra .Lrl_loop

.Lrl_char:
    ldl w0
    u 32
    blt .Lrl_loop

    ldl w11
    i LINE_MAX
    bge .Lrl_loop

    ldl w5
    ldl w0
    sb

    jal term_putc

    ldl w5
    inc
    stl w5
    ldl w11
    inc
    stl w11

    bra .Lrl_loop


; ============================================================
; Strings
; ============================================================

.data

banner:
    .ascii "R816 BASIC TERMINAL v0.0\r\n"
    .byte 0

ready:
    .ascii "READY. "
    .byte 0