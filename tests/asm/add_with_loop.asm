start:
    ldl #0x05       ; Load value 0x05 into the stack
    ldl #0x07       ; Load value 0x07 into the stack
    add             ; Add the two values
    st #0x1000      ; Store result in memory location 0x1000

loop:
    ldl #0x01       ; Load value 0x01 into the stack
    sub             ; Subtract 1 from the stack value
    bne loop        ; Branch to 'loop' if not equal to zero

    hlt             ; Halt the program
