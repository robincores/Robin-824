; BIOS/R816/macros.inc
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

; ------------------------------------------------------------
; bgt target  (macro shim)
; Branch if (B > A), using existing bge + beq + bra
;
; Implementation: if (B >= A) and (B != A) => B > A
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
; Use when a rel8 branch overflows (e.g., -131).
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