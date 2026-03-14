; BIOS/R816/BASIC/vars.asm
; Variable table (QuickBASIC-ish):
; - case-insensitive names
; - max VAR_NAME_MAX chars
; - numeric vars: INT16 only (signed)
; - string vars: trailing $ with fixed-size storage (v0.4.3 foundation)
;
; Table: open addressing, fixed-size entries
; entry layout:
;   [0] len (0 => empty)
;   [1..VAR_NAME_MAX] name bytes (upper)
;   [1+VAR_NAME_MAX] value_lo
;   [2+VAR_NAME_MAX] value_hi

; ------------------------------------------------------------
; vars_init(): clear lengths to 0
; ------------------------------------------------------------
vars_init:
  stl w14

  i VAR_BASE
  stl w10
  i VAR_SLOTS
  stl w11

.Lvi_loop:
  ldl w11
  i0
  beq .Lvi_done

  ldl w10
  u 0
  sb

  ldl w10
  i VAR_ENTRY_SIZE
  add
  stl w10

  ldl w11
  dec
  stl w11
  bra .Lvi_loop

.Lvi_done:
  ldl w14
  jr

; ------------------------------------------------------------
; vars_hash(w0=namePtr, w1=len) -> w0 = hash (0..255)
; h = (h*33 + ch) & 0xFF
; ------------------------------------------------------------
vars_hash:
  stl w14
  ldl w0
  stl w10
  ldl w1
  stl w11
  i0
  stl w2

.Lvh_loop:
  ldl w11
  i0
  beq .Lvh_done

  ldl w10
  lu
  stl w3

  ldl w2
  sll 4
  sll 1
  ldl w2
  add
  ldl w3
  add
  u 0xFF
  and
  stl w2

  ldl w10
  inc
  stl w10
  ldl w11
  dec
  stl w11
  bra .Lvh_loop

.Lvh_done:
  ldl w2
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; vars_slot_to_entry(w0=slot) -> w0=entryPtr
; offset = slot*34 = (slot<<5) + (slot<<1)
; ------------------------------------------------------------
vars_slot_to_entry:
  stl w14

  ldl w0
  stl w2

  ldl w2
  sll 4
  sll 1
  stl w3               ; slot*32

  ldl w2
  sll 1
  ldl w3
  add
  stl w3               ; slot*34

  i VAR_BASE
  ldl w3
  add
  stl w0

  ldl w14
  jr

; ------------------------------------------------------------
; vars_name_match(w0=entryPtr, w1=namePtr, w2=len) -> w0=1/0
; ------------------------------------------------------------
vars_name_match:
  stl w14

  ldl w0
  stl w10              ; entry
  ldl w1
  stl w11              ; name
  ldl w2
  stl w12              ; len

  ; entryLen
  ldl w10
  lu
  stl w3

  ldl w3
  ldl w12
  beq .Lvm_cmp

  i0
  stl w0
  ldl w14
  jr

.Lvm_cmp:
  ldl w10
  u 1
  add
  stl w4               ; entName

  ldl w11
  stl w5               ; src

  ldl w12
  stl w6               ; remaining

.Lvm_loop:
  ldl w6
  i0
  beq .Lvm_yes

  ldl w4
  lu
  stl w7
  ldl w5
  lu
  stl w8

  ldl w7
  ldl w8
  beq .Lvm_ok

  i0
  stl w0
  ldl w14
  jr

.Lvm_ok:
  ldl w4
  inc
  stl w4
  ldl w5
  inc
  stl w5
  ldl w6
  dec
  stl w6
  bra .Lvm_loop

.Lvm_yes:
  u 1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; vars_create(w0=entryPtr, w1=namePtr, w2=len)
; ------------------------------------------------------------
vars_create:
  stl w14

  ldl w0
  stl w10
  ldl w1
  stl w11
  ldl w2
  stl w12

  ; write len
  ldl w10
  ldl w12
  sb

  ; copy bytes
  ldl w10
  u 1
  add
  stl w4               ; dst
  ldl w11
  stl w5               ; src
  ldl w12
  stl w6               ; remaining

.Lvc_loop:
  ldl w6
  i0
  beq .Lvc_zero

  ldl w5
  lu
  stl w7
  ldl w4
  ldl w7
  sb

  ldl w4
  inc
  stl w4
  ldl w5
  inc
  stl w5
  ldl w6
  dec
  stl w6
  bra .Lvc_loop

.Lvc_zero:
  ; value=0
  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  u 0
  sb

  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  u 0
  sb

  ldl w14
  jr

; ------------------------------------------------------------
; vars_find_entry(w0=namePtr, w1=len) -> w0=entryPtr (creates if missing)
; ------------------------------------------------------------
vars_find_entry:
  stl w14

  ldl w0
  stl w6               ; namePtr
  ldl w1
  stl w7               ; len

  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_hash

  ldl w0
  u 63
  and
  stl w9               ; slot

.Lvf_loop:
  ldl w9
  stl w0
  CALL vars_slot_to_entry
  ldl w0
  stl w10              ; entry

  ; entryLen
  ldl w10
  lu
  i0
  beq .Lvf_empty

  ; match?
  ldl w10
  stl w0
  ldl w6
  stl w1
  ldl w7
  stl w2
  CALL vars_name_match

  ldl w0
  u 1
  beq .Lvf_found

  ; next slot
  ldl w9
  inc
  u 63
  and
  stl w9
  bra .Lvf_loop

.Lvf_empty:
  ldl w10
  stl w0
  ldl w6
  stl w1
  ldl w7
  stl w2
  CALL vars_create

.Lvf_found:
  ldl w10
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; vars_get(w0=namePtr, w1=len) -> w0=value
; ------------------------------------------------------------
vars_get:
  stl w14

  CALL vars_find_entry
  ldl w0
  stl w10

  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  lu
  stl w1

  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  lu
  stl w2

  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w0

  ldl w14
  jr

; ------------------------------------------------------------
; vars_set(w0=namePtr, w1=len, w2=value) -> w0=value
; ------------------------------------------------------------
vars_set:
  stl w14

  ldl w2
  stl w8

  CALL vars_find_entry
  ldl w0
  stl w10

  ldl w8
  u 0xFF
  and
  stl w1

  ldl w8
  srl 4
  srl 4
  stl w2

  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  ldl w1
  sb

  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  ldl w2
  sb

  ldl w8
  stl w0
  ldl w14
  jr


; ============================================================
; String variables (trailing $)
; Layout per entry (64 bytes):
;   [0] len (0 => empty)
;   [1..VAR_NAME_MAX] uppercased name bytes
;   [1+VAR_NAME_MAX] str_len (0..STR_VAR_MAX)
;   [2+VAR_NAME_MAX .. 2+VAR_NAME_MAX+STR_VAR_MAX-1] data bytes
; ============================================================

; ------------------------------------------------------------
; strvars_init(): clear lengths to 0
; ------------------------------------------------------------
strvars_init:
  stl w14

  i STR_VAR_BASE
  stl w10
  i STR_VAR_SLOTS
  stl w11

.Lsvi_loop:
  ldl w11
  i0
  beq .Lsvi_done

  ldl w10
  u 0
  sb

  ldl w10
  i STR_ENTRY_SIZE
  add
  stl w10

  ldl w11
  dec
  stl w11
  bra .Lsvi_loop

.Lsvi_done:
  ldl w14
  jr

; ------------------------------------------------------------
; strvars_slot_to_entry(w0=slot) -> w0=entryPtr
; offset = slot * 64
; ------------------------------------------------------------
strvars_slot_to_entry:
  stl w14

  ldl w0
  sll 4
  sll 4
  sll 4
  sll 4
  sll 4
  sll 4
  stl w3

  i STR_VAR_BASE
  ldl w3
  add
  stl w0

  ldl w14
  jr

; ------------------------------------------------------------
; strvars_create(w0=entryPtr, w1=namePtr, w2=len)
; ------------------------------------------------------------
strvars_create:
  stl w14

  ldl w0
  stl w10
  ldl w1
  stl w11
  ldl w2
  stl w12

  ; write name len
  ldl w10
  ldl w12
  sb

  ; copy name bytes
  ldl w10
  u 1
  add
  stl w4
  ldl w11
  stl w5
  ldl w12
  stl w6

.Lsvc_loop:
  ldl w6
  i0
  beq .Lsvc_zero

  ldl w5
  lu
  stl w7
  ldl w4
  ldl w7
  sb

  ldl w4
  inc
  stl w4
  ldl w5
  inc
  stl w5
  ldl w6
  dec
  stl w6
  bra .Lsvc_loop

.Lsvc_zero:
  ; string len = 0
  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  u 0
  sb

  ldl w14
  jr

; ------------------------------------------------------------
; strvars_find_entry(w0=namePtr, w1=len) -> w0=entryPtr (creates if missing)
; ------------------------------------------------------------
strvars_find_entry:
  stl w14

  ldl w0
  stl w6
  ldl w1
  stl w7

  ldl w6
  stl w0
  ldl w7
  stl w1
  CALL vars_hash

  ldl w0
  u 15
  and
  stl w9

.Lsvf_loop:
  ldl w9
  stl w0
  CALL strvars_slot_to_entry
  ldl w0
  stl w10

  ldl w10
  lu
  i0
  beq .Lsvf_empty

  ldl w10
  stl w0
  ldl w6
  stl w1
  ldl w7
  stl w2
  CALL vars_name_match

  ldl w0
  i1
  beq .Lsvf_found

  ldl w9
  inc
  u 15
  and
  stl w9
  bra .Lsvf_loop

.Lsvf_empty:
  ldl w10
  stl w0
  ldl w6
  stl w1
  ldl w7
  stl w2
  CALL strvars_create

.Lsvf_found:
  ldl w10
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; strvars_set_z(w0=namePtr, w1=len, w2=srcPtr NUL-terminated)
; ------------------------------------------------------------
strvars_set_z:
  stl w14

  ldl w2
  stl w8               ; src

  CALL strvars_find_entry
  ldl w0
  stl w10              ; entry

  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  stl w11              ; dst data

  i0
  stl w12              ; count

.Lsvs_loop:
  ldl w8
  lu
  stl w1

  ldl w1
  i0
  beq .Lsvs_done

  ldl w12
  i STR_VAR_MAX
  bge .Lsvs_done

  ldl w11
  ldl w1
  sb

  ldl w11
  inc
  stl w11
  ldl w8
  inc
  stl w8
  ldl w12
  inc
  stl w12
  bra .Lsvs_loop

.Lsvs_done:
  ; store string length
  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  ldl w12
  sb

  ldl w14
  jr

; ------------------------------------------------------------
; strvars_get_ptr(w0=namePtr, w1=len) -> w0=dataPtr, w1=strLen
; ------------------------------------------------------------
strvars_get_ptr:
  stl w14

  CALL strvars_find_entry
  ldl w0
  stl w10

  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  lu
  stl w1

  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  stl w0

  ldl w14
  jr
