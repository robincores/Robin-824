; ============================================================
; expr.asm - integers + variables, + and - only (v0.1)
; returns value in w0, uses w10 as input pointer
; ============================================================

parse_expr:
    stl w14

    jal parse_term
    ldl w0
    stl w12      ; lhs

.pe_loop:
    jal skip_spaces
    ldl w10
    lu
    stl w1

    ; '+' ?
    ldl w1
    b $2B
    beq .do_plus

    ; '-' ?
    ldl w1
    b $2D
    beq .do_minus

    j .pe_done

.do_plus:
    ldl w10
    inc
    stl w10

    jal parse_term

    ldl w12
    ldl w0
    add
    stl w12
    j .pe_loop

.do_minus:
    ldl w10
    inc
    stl w10

    jal parse_term

    ldl w12
    ldl w0
    sub
    stl w12
    j .pe_loop

.pe_done:
    ldl w12
    stl w0
    ldl w14
    jr

parse_term:
    stl w14
    jal skip_spaces

    ldl w10
    lu
    stl w1

    ; digit?
    ldl w1
    b $30
    blt .maybe_var
    ldl w1
    b $3A
    bge .maybe_var

    jal parse_decimal
    ldl w14
    jr

.maybe_var:
    ; A..Z
    ldl w1
    b $41
    blt .zero
    ldl w1
    b $5B
    bge .zero

    ; idx = ch - 'A'
    ldl w1
    b $41
    sub
    stl w2

    ; advance
    ldl w10
    inc
    stl w10

    ; addr = VAR_BASE + idx*2
    ldl w2
    sll 1
    stl w2

    i VAR_BASE
    ldl w2
    add
    ld
    stl w0

    ldl w14
    jr

.zero:
    i0
    stl w0
    ldl w14
    jr

parse_decimal:
    stl w14
    i0
    stl w0

.pd_loop:
    ldl w10
    lu
    stl w1

    ldl w1
    b $30
    blt .pd_done
    ldl w1
    b $3A
    bge .pd_done

    ; digit = ch - '0'
    ldl w1
    b $30
    sub
    stl w2

    ; value = value*10 + digit
    ldl w0
    b $0A
    mul
    stl w0

    ldl w0
    ldl w2
    add
    stl w0

    ldl w10
    inc
    stl w10
    j .pd_loop

.pd_done:
    ldl w14
    jr

; w0=value, prints unsigned decimal
print_int16:
    stl w14

    ldl w0
    i0
    beq .pi_zero

    i (LINE_BUF + 128)
    stl w10      ; scratch ptr
    i0
    stl w11      ; count

.pi_loop:
    ldl w0
    b $0A
    rem
    stl w2

    ldl w0
    b $0A
    div
    stl w0

    ldl w2
    b $30
    add
    stl w2

    ldl w10
    ldl w2
    sb

    ldl w10
    inc
    stl w10
    ldl w11
    inc
    stl w11

    ldl w0
    i0
    bne .pi_loop

.pi_rev:
    ldl w11
    i0
    beq .pi_done

    ldl w10
    dec
    stl w10
    ldl w11
    dec
    stl w11

    ldl w10
    lu
    stl w0
    jal con_putc

    j .pi_rev

.pi_zero:
    b $30
    stl w0
    jal con_putc

.pi_done:
    ldl w14
    jr
