.once

; Compatibility pseudos still used by current BASIC/BIOS sources.
.macro mod
  rem
.endm

.macro sll n
  u \n
  shl
.endm

.macro srl n
  u \n
  shr
.endm

.macro sra n
  u \n
  sar
.endm

; Legacy 'jsr label' -> CALL label (saves/restores w14)
.macro jsr target
  CALL \target
.endm
