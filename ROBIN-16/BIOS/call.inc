.once

; Generic helper macros used by BIOS and apps
.macro WB addr val
  i \addr
  u \val
  sb
.endm

; CALL target
; Saves/restores w14 around jal (so nested calls are safe)
.macro CALL target
  ldl w14
  push
  jal \target
  pop
  stl w14
.endm
