.once

; Branch if (B > A), using existing bge + beq + bra
.macro bgt target
  bge .Lbgt_ge_\@
  bra .Lbgt_end_\@

.Lbgt_ge_\@:
  beq .Lbgt_end_\@
  bra \target

.Lbgt_end_\@:
.endm

; FAR branch shims (use rel16 J internally)
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
