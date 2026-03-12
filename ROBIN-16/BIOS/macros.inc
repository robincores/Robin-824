.once

; BIOS/R816/macros.inc
; Generic helper macros used by BIOS and apps

.macro WB addr val
  i \addr
  u \val
  sb
.endm

; Tiny immediates used all over BASIC/BIOS sources
.macro i0
  i 0
.endm

.macro i1
  i 1
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

; ------------------------------------------------------------
; bgt target  (macro shim)
; Branch if (B > A), using existing bge + beq + bra
; ------------------------------------------------------------
.macro bgt target
  bge .Lbgt_ge_\@
  bra .Lbgt_end_\@

.Lbgt_ge_\@:
  beq .Lbgt_end_\@
  bra \target

.Lbgt_end_\@:
.endm

; ------------------------------------------------------------
; FAR branch shims (use rel16 J internally)
; ------------------------------------------------------------
.macro brafar target
  j \target
.endm

.macro beqfar target
  bne 1f
  j \target
1:
.endm

.macro bnefar target
  beq 1f
  j \target
1:
.endm

.macro bltfar target
  bge 1f
  j \target
1:
.endm

.macro bgefar target
  blt 1f
  j \target
1:
.endm

.macro bltufar target
  bgeu 1f
  j \target
1:
.endm

.macro bgeufar target
  bltu 1f
  j \target
1:
.endm

; ------------------------------------------------------------------
; Compatibility pseudos still used by current BASIC/BIOS sources.
; Keep these until the sources are fully migrated.
; ------------------------------------------------------------------

; Old 'mod' mnemonic -> canonical 'rem' (signed remainder)
.macro mod
  rem
.endm

; Old constant shifts -> immediate + variable shift
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
