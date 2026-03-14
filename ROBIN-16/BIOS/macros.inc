.once

; BIOS/R816/macros.inc
; Public umbrella include for helper macro families. Callers should include
; macros.inc rather than the split macro shards directly unless they are
; deliberately building a lower-level translation unit.
.include "ROBIN-16/BIOS/imm.inc"
.include "ROBIN-16/BIOS/call.inc"
.include "ROBIN-16/BIOS/branch.inc"
.include "ROBIN-16/BIOS/compat.inc"
