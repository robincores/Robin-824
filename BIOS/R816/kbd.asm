; ============================================================
; kbd.asm - polled keyboard FIFO at 0xBF00
; STATUS bit0 = hasData
; DATA pops a byte
; ============================================================

kbd_getc_block:
    stl w14
.kwait:
    i KBD_STATUS
    lu
    i1
    and
    i0
    beq .kwait

    i KBD_DATA
    lu
    stl w0

    ldl w14
    jr

; w0 = buffer address, stores 0-terminated string, echoes
kbd_readline:
    stl w14

    ldl w0
    stl w10      ; ptr
    i0
    stl w11      ; len

.rl_loop:
    jal kbd_getc_block   ; w0=ch

    ; CR -> finish
    ldl w0
    b $0D
    beq .rl_done

    ; BS
    ldl w0
    b $08
    beq .rl_bs

    ; ignore < 0x20
    ldl w0
    b $20
    blt .rl_loop

    ; len >= LINE_MAX -> ignore
    ldl w11
    i LINE_MAX
    bge .rl_loop

    ; store byte
    ldl w10
    ldl w0
    sb

    ; echo
    jal con_putc

    ; ptr++, len++
    ldl w10
    inc
    stl w10
    ldl w11
    inc
    stl w11

    j .rl_loop

.rl_bs:
    ldl w11
    i0
    beq .rl_loop

    ldl w11
    dec
    stl w11
    ldl w10
    dec
    stl w10

    ; BS, space, BS
    b $08
    stl w0
    jal con_putc
    b $20
    stl w0
    jal con_putc
    b $08
    stl w0
    jal con_putc

    j .rl_loop

.rl_done:
    ; terminator
    ldl w10
    i0
    sb

    ; echo CRLF
    b $0D
    stl w0
    jal con_putc
    b $0A
    stl w0
    jal con_putc

    ldl w14
    jr