; ============================================================
; stmt.asm - dispatch + PRINT + LET/A= assignment (v0.1)
; uses w10 as input pointer
; ============================================================

exec_line:
    stl w14
    ldl w0
    stl w10

    jal skip_spaces

    ldl w10
    lu
    stl w1

    ldl w1
    i0
    beq .ex_done

    ; '?' => PRINT
    ldl w1
    b $3F
    beq .do_print_q

    ; 'P' => PRINT (assume exact "PRINT")
    ldl w1
    b $50
    beq .do_print_kw

    ; 'L' => LET (assume exact "LET")
    ldl w1
    b $4C
    beq .do_let_kw

    ; else: assignment
    jal do_assign
    j .ex_done

.do_print_q:
    ldl w10
    inc
    stl w10
    jal do_print
    j .ex_done

.do_print_kw:
    ; skip PRINT (5 chars)
    ldl w10
    inc
    inc
    inc
    inc
    inc
    stl w10
    jal do_print
    j .ex_done

.do_let_kw:
    ; skip LET (3 chars)
    ldl w10
    inc
    inc
    inc
    stl w10
    jal do_assign
    j .ex_done

.ex_done:
    ldl w14
    jr

skip_spaces:
    stl w14
.ss_loop:
    ldl w10
    lu
    stl w1
    ldl w1
    b $20
    bne .ss_done
    ldl w10
    inc
    stl w10
    j .ss_loop
.ss_done:
    ldl w14
    jr

do_print:
    stl w14
    jal skip_spaces

    ldl w10
    lu
    stl w1

    ; string literal?
    ldl w1
    b $22
    beq .print_string

    ; else: expression
    jal parse_expr
    jal print_int16

    b $0D
    stl w0
    jal con_putc
    b $0A
    stl w0
    jal con_putc

    ldl w14
    jr

.print_string:
    ; skip opening quote
    ldl w10
    inc
    stl w10

.ps_loop:
    ldl w10
    lu
    stl w1

    ldl w1
    i0
    beq .ps_end
    ldl w1
    b $22
    beq .ps_end

    ldl w1
    stl w0
    jal con_putc

    ldl w10
    inc
    stl w10
    j .ps_loop

.ps_end:
    ; skip closing quote if present
    ldl w1
    b $22
    bne .ps_nl
    ldl w10
    inc
    stl w10

.ps_nl:
    b $0D
    stl w0
    jal con_putc
    b $0A
    stl w0
    jal con_putc

    ldl w14
    jr

do_assign:
    stl w14
    jal skip_spaces

    ; read var letter
    ldl w10
    lu
    stl w1

    ; must be A..Z
    ldl w1
    b $41
    blt .as_err
    ldl w1
    b $5B
    bge .as_err

    ; idx = ch - 'A'
    ldl w1
    b $41
    sub
    stl w2

    ; advance
    ldl w10
    inc
    stl w10

    jal skip_spaces

    ; expect '='
    ldl w10
    lu
    stl w1
    ldl w1
    b $3D
    bne .as_err

    ldl w10
    inc
    stl w10

    jal skip_spaces

    ; parse expr -> w0
    jal parse_expr

    ; store VAR_BASE + idx*2
    ldl w2
    sll 1
    stl w2

    i VAR_BASE
    ldl w2
    add
    stl w3

    ldl w3
    ldl w0
    st

    ldl w14
    jr

.as_err:
    i syntax_err
    stl w0
    jal con_puts_z
    ldl w14
    jr