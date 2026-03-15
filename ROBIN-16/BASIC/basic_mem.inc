.once

; ROBIN-16/BASIC/basic_mem.inc
; Shared BASIC runtime memory map

.equ LINE_BUF       0x9100
.equ LINE_MAX       159

; Program store + UI/Run state
.equ SYS_PROG_TOP    0x9000
.equ SYS_PROMPT      0x9002
.equ SYS_RUNNING     0x9003
.equ SYS_GOTO_LO     0x9004
.equ SYS_GOTO_HI     0x9005
.equ SYS_GOTO_PEND   0x9006
.equ SYS_END_PEND    0x9007
.equ SYS_NEXT_PTR_LO 0x9008
.equ SYS_NEXT_PTR_HI 0x9009
.equ SYS_RET_PTR_LO  0x900A
.equ SYS_RET_PTR_HI  0x900B
.equ SYS_RET_PEND    0x900C
.equ SYS_GOSUB_SP    0x900D
.equ SYS_FOR_SP      0x900E

; Scratch bytes reserved for prog_store_line across prog_compact()
.equ SYS_STORE_LINE_LO 0x900F
.equ SYS_STORE_LINE_HI 0x9010
.equ SYS_STORE_TXT_LO  0x9011
.equ SYS_STORE_TXT_HI  0x9012
.equ SYS_STORE_LEN     0x9013
.equ SYS_STORE_RECSZ   0x9014

; Array allocator top (low/high)
.equ SYS_ARR_TOP_LO   0x9015
.equ SYS_ARR_TOP_HI   0x9016

.equ GOSUB_STACK_MAX   16
.equ GOSUB_FRAME_BYTES 2
.equ GOSUB_STACK_BYTES (GOSUB_STACK_MAX * GOSUB_FRAME_BYTES)
.equ GOSUB_STACK_BASE  0x91E0

.equ VAR_NAME_BUF    0x91A0
.equ VAR_NAME_MAX    31
.equ VAR_SLOTS       64
.equ VAR_ENTRY_SIZE  34
.equ VAR_BYTES       (VAR_SLOTS * VAR_ENTRY_SIZE)
.equ VAR_BASE        0x9200

.equ FOR_STACK_MAX    16
.equ FOR_FRAME_BYTES  8
.equ FOR_STACK_BYTES  (FOR_STACK_MAX * FOR_FRAME_BYTES)
.equ FOR_STACK_BASE   0x9A80

; Tokenized-line work buffer (enough for a full input tail + NUL)
.equ TOK_LINE_BUF    0x9B00
.equ TOK_LINE_MAX    LINE_MAX

; String variables: trailing $ names, fixed-size storage (strings pass)
.equ STR_VAR_SLOTS      16
.equ STR_VAR_DATA_MAX   32
.equ STR_VAR_ENTRY_SIZE (1 + VAR_NAME_MAX + 1 + STR_VAR_DATA_MAX)
.equ STR_VAR_BYTES      (STR_VAR_SLOTS * STR_VAR_ENTRY_SIZE)
.equ STR_VAR_BASE       0x9BA0

; Integer arrays: one-dimensional, DIM A(n), elements are INT16
.equ ARR_SLOTS          8
.equ ARR_DESC_ENTRY_SIZE (1 + VAR_NAME_MAX + 2 + 2)
.equ ARR_DESC_BYTES      (ARR_SLOTS * ARR_DESC_ENTRY_SIZE)
.equ ARR_DESC_BASE       0x9FB0
.equ ARR_DATA_BASE       (ARR_DESC_BASE + ARR_DESC_BYTES)

.equ PROG_BASE       0xA500
.equ PROG_LIMIT      0xBE00
.equ ARR_DATA_LIMIT  PROG_BASE
