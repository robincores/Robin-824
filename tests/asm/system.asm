.define StateReady      1
.define StateRunning    2
.define StateBlocked    3

.define PCB_IPtr        0
.define PCB_SPtr        3

    .org 0      ; Start of the boot sequence

    j start     ; 0x00_0000: Jump to the start label to begin execution

mtvec:          ; 0x00_0002: This is the Machine Trap-Vector Base Address (MTVEC),
                ;            used for interrupt handling

    ; ...

    i dispatch
    jr

    nop
    nop
    nop

start:

    ; Print BOOT Message
    i msg1
    b 0x06      ; PRINT STRING (OS/R824 booting...)
    ecall       ; Perform a system call to print the string

    ; Idle Process

    aiip PCB_Idle
    stl @0

    ldl @0
    aiip idle
    st          ; Store IPtr

    ldl @0
    b PCB_SPtr
    add
    aiip IdleStackEnd
    stl sp
    ldl sp
    st          ; Store SPtr

    ; Init Process

    aiip PCB_Idle
    stl @1

    ldl @1
    aiip init
    st          ; Store IPtr

    ldl @1
    b PCB_SPtr
    add
    aiip InitStackEnd
    st          ; Store SPtr

    ; Set Current Process
    aiip @CurrentProcess
    aiip @PCB_Idle
    stl @0      ; @0: PCB_Idle
    st

    ; Initialise System Timer
    i 0xF00000  ; Load address of the comparison value (mtimecmp)
    i 0x080000  ; Load the timer comparison value into the register
    st          ; Store the timer comparison value

    ei          ; Enable interrupts globally

idle:           ; Idle Process

    i msg2
    b 0x06
    ecall

    j idle

init:           ; Init Process

    i msg3
    b 0x06
    ecall

    j init

dispatch:

    ldl @0
    push

    ldl @1
    push

    ldl @2
    push

    ldl @3
    push

    ldl @4
    push

    ldl @5
    push

    ldl @6
    push

    ldl @7
    push

    ldl @8
    push

    ldl @9
    push

    ldl @10
    push

    ldl @11     ; CReg
    push

    ldl @12     ; BReg
    push

    ldl @13     ; AReg
    push

    ldl @14     ; IPtr
    push

    aiip CurrentProcess
    b PCB_SPtr
    add

    ldl sp
    st          ; Store SP to process table

    ; switch process

    aiip CurrentProcess
    b PCB_SPtr
    add

    ld
    stl sp      ; Restote SP to process table

    pop
    stl @14     ; IPtr

    pop
    stl @13     ; AReg

    pop
    stl @12     ; BReg

    pop
    stl @11     ; CReg

    pop
    stl @10

    pop
    stl @9

    pop
    stl @8

    pop
    stl @7

    pop
    stl @6

    pop
    stl @5

    pop
    stl @4

    pop
    stl @3

    pop
    stl @2

    pop
    stl @1

    pop
    stl @0

    ; Reset System Timer
    i 0xF00002  ; Load MSB address of the comparison value (mtimecmp)
    u 0x08      ; Load upper part of the comparison value
    sb          ; Store the timer comparison value

    iret        ; Return from interrupt, acknowledging the timer initialization

msg1:
    .string OS/R824 booting...
    .data 0

msg2:
    .string IDLE
    .data 0

msg3:
    .string ROOT
    .data 0

CurrentProcess:
    .data $00 $00 $00

PCB_Queue:              ; Process Control Block Queue

PCB_Idle:               ; Idle PCB

    .data $00 $00 $00   ; IPtr (start)
    .data $00 $00 $00   ; SPtr

    .data $00           ; state

PCB_Init:               ; Init PCB

    .data $00 $00 $00   ; IPtr (start)
    .data $00 $00 $00   ; SPtr

    .data $00           ; state

; ...

IdleStackBegin:
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF

IdleStackEnd:
    .data $FF

InitStackBegin:
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF
    .data $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF $FF

InitStackEnd:
    .data $FF
