; ============================================================
; con.asm - text console for VPU MODE=4 (80x25, 2 bytes/cell)
; uses w8=x, w9=y
; ============================================================

con_sync_cursor:
    stl w14
    i VPU_CUR_X
    ldl w8
    sb
    i VPU_CUR_Y
    ldl w9
    sb
    ldl w14
    jr

con_cls:
    stl w14

    i0
    stl w8
    i0
    stl w9
    jal con_sync_cursor

    i VPU_TEXT_BASE
    stl w10

    i (VPU_TEXT_COLS * VPU_TEXT_ROWS)   ; 2000 cells
    stl w11

.con_cls_loop:
    ldl w11
    i0
    beq .con_cls_done

    ; char = ' '
    ldl w10
    b $20
    sb
    ldl w10
    inc
    stl w10

    ; attr = 0x0F
    ldl w10
    b $0F
    sb
    ldl w10
    inc
    stl w10

    ldl w11
    dec
    stl w11

    j .con_cls_loop

.con_cls_done:
    ldl w14
    jr

con_puts_z:
    stl w14
    ldl w0
    stl w10

.con_puts_loop:
    ldl w10
    lu
    stl w1

    ldl w1
    i0
    beq .con_puts_done

    ldl w1
    stl w0
    jal con_putc

    ldl w10
    inc
    stl w10
    j .con_puts_loop

.con_puts_done:
    ldl w14
    jr

con_putc:
    stl w14

    ; CR
    ldl w0
    b $0D
    beq .cr

    ; LF
    ldl w0
    b $0A
    beq .lf

    ; BS
    ldl w0
    b $08
    beq .bs

    ; normal printable
    jal con_cell_addr

    ; write char
    ldl w2
    ldl w0
    sb

    ; write attr
    ldl w3
    b $0F
    sb

    ; x++
    ldl w8
    inc
    stl w8

    ; if x >= 80 -> newline
    ldl w8
    i VPU_TEXT_COLS
    bge .lf

    jal con_sync_cursor
    ldl w14
    jr

.cr:
    i0
    stl w8
    jal con_sync_cursor
    ldl w14
    jr

.lf:
    i0
    stl w8
    ldl w9
    inc
    stl w9

    ; wrap for v0.1 (scroll later)
    ldl w9
    i VPU_TEXT_ROWS
    bge .wrap

    jal con_sync_cursor
    ldl w14
    jr

.wrap:
    i0
    stl w9
    jal con_sync_cursor
    ldl w14
    jr

.bs:
    ldl w8
    i0
    beq .bs_done

    ldl w8
    dec
    stl w8
    jal con_sync_cursor

    jal con_cell_addr
    ldl w2
    b $20
    sb
    ldl w3
    b $0F
    sb

.bs_done:
    ldl w14
    jr

; returns w2=charAddr, w3=attrAddr
con_cell_addr:
    stl w14

    ; y*160 = y*128 + y*32
    ldl w9
    sll 4
    sll 3
    stl w12        ; y*128

    ldl w9
    sll 4
    sll 1
    stl w13        ; y*32

    ldl w12
    ldl w13
    add
    stl w12        ; y*160

    ; x*2
    ldl w8
    sll 1
    stl w13

    ; offset = y*160 + x*2
    ldl w12
    ldl w13
    add
    stl w12

    ; charAddr = base + offset
    i VPU_TEXT_BASE
    ldl w12
    add
    stl w2

    ; attrAddr = charAddr + 1
    ldl w2
    inc
    stl w3

    ldl w14
    jr
