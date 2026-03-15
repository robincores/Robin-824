; BIOS/R816/BASIC/vars.asm
; Variable tables
; - numeric vars: INT16 (default when no type suffix)
; - string vars: trailing '$' with fixed-size payload storage
; - case-insensitive names, stored uppercased in VAR_NAME_BUF
;
; Numeric entry layout:
;   [0] len (0 => empty)
;   [1..VAR_NAME_MAX] name bytes (upper)
;   [1+VAR_NAME_MAX] value_lo
;   [2+VAR_NAME_MAX] value_hi
;
; String entry layout:
;   [0] len (0 => empty)
;   [1..VAR_NAME_MAX] name bytes (upper, includes trailing '$')
;   [1+VAR_NAME_MAX] strlen
;   [2+VAR_NAME_MAX .. +STR_VAR_DATA_MAX-1] payload bytes

; ------------------------------------------------------------
; vars_name_is_string(w0=namePtr, w1=len) -> w0=1/0
; trailing '$' denotes a string variable
; ------------------------------------------------------------
vars_name_is_string:
  stl w14

  ldl w1
  i0
  bne .Lvnis_have
  i0
  stl w0
  ldl w14
  jr

.Lvnis_have:
  ldl w0
  ldl w1
  add
  dec
  stl w8          ; use scratch, not w10

  ldl w8
  lu
  stl w0
  ldl w0
  u 36            ; '$'
  beq .Lvnis_yes

  i0
  stl w0
  ldl w14
  jr

.Lvnis_yes:
  u 1
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; vars_init(): clear numeric + string tables
; ------------------------------------------------------------
vars_init:
  stl w14

  ; numeric vars
  i VAR_BASE
  stl w10
  i VAR_SLOTS
  stl w11
.Lvi_loop_num:
  ldl w11
  i0
  beq .Lvi_done_num
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
  bra .Lvi_loop_num
.Lvi_done_num:

  ; string vars
  i STR_VAR_BASE
  stl w10
  i STR_VAR_SLOTS
  stl w11
.Lvi_loop_str:
  ldl w11
  i0
  beq .Lvi_done_str
  ldl w10
  u 0
  sb
  ldl w10
  i STR_VAR_ENTRY_SIZE
  add
  stl w10
  ldl w11
  dec
  stl w11
  bra .Lvi_loop_str
.Lvi_done_str:

  ; array descriptors
  i ARR_DESC_BASE
  stl w10
  i ARR_SLOTS
  stl w11
.Lvi_loop_arr:
  ldl w11
  i0
  beq .Lvi_done
  ldl w10
  u 0
  sb
  ldl w10
  i ARR_DESC_ENTRY_SIZE
  add
  stl w10
  ldl w11
  dec
  stl w11
  bra .Lvi_loop_arr
.Lvi_done:
  ; reset array allocator top
  i SYS_ARR_TOP_LO
  u (ARR_DATA_BASE & 0xFF)
  sb
  i SYS_ARR_TOP_HI
  u ((ARR_DATA_BASE >> 8) & 0xFF)
  sb
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
  stl w3
  ldl w2
  sll 1
  ldl w3
  add
  stl w3
  i VAR_BASE
  ldl w3
  add
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; strvars_slot_to_entry(w0=slot) -> w0=entryPtr
; offset = slot*65 = (slot<<6) + slot
; ------------------------------------------------------------
strvars_slot_to_entry:
  stl w14
  ldl w0
  stl w2
  ldl w2
  sll 4
  sll 2
  stl w3               ; slot*64
  ldl w2
  ldl w3
  add
  stl w3               ; slot*65
  i STR_VAR_BASE
  ldl w3
  add
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; vars_name_match(w0=entryPtr, w1=namePtr, w2=len) -> w0=1/0
; works for both numeric and string tables because name layout matches.
; ------------------------------------------------------------
vars_name_match:
  stl w14
  ldl w0
  stl w10
  ldl w1
  stl w11
  ldl w2
  stl w12
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
  stl w4
  ldl w11
  stl w5
  ldl w12
  stl w6
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
  ldl w10
  ldl w12
  sb
  ldl w10
  u 1
  add
  stl w4
  ldl w11
  stl w5
  ldl w12
  stl w6
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
  ldl w10
  ldl w12
  sb
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
  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  u 0
  sb                     ; strlen = 0
  ldl w14
  jr

; ------------------------------------------------------------
; vars_find_entry(w0=namePtr, w1=len) -> w0=entryPtr (creates if missing)
; ------------------------------------------------------------
vars_find_entry:
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
  u 63
  and
  stl w9
.Lvf_loop:
  ldl w9
  stl w0
  CALL vars_slot_to_entry
  ldl w0
  stl w10
  ldl w10
  lu
  i0
  beq .Lvf_empty
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
  u 1
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
; vars_get(w0=namePtr, w1=len) -> w0=value
; numeric only; string names are not valid here.
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

; ------------------------------------------------------------
; strvars_get_ptr(w0=namePtr, w1=len) -> w0=ptr, w1=len
; creates the entry if missing; empty strings return len=0
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

; ------------------------------------------------------------
; strvars_set_range(w0=namePtr, w1=len, w2=srcPtr, w3=srcEndExclusive)
; stores up to STR_VAR_DATA_MAX bytes and sets strlen.
; out: w0 = stored length
; ------------------------------------------------------------
strvars_set_range:
  stl w14

  ; preserve source range across strvars_find_entry
  ldl w2
  push
  ldl w3
  push

  CALL strvars_find_entry
  ldl w0
  stl w10              ; entry

  ; restore source range
  pop
  stl w7               ; end
  pop
  stl w6               ; src

  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  stl w11              ; dst payload
  i0
  stl w12              ; stored len

.Lsvsr_loop:
  ldl w6
  ldl w7
  beq .Lsvsr_done
  ldl w12
  i STR_VAR_DATA_MAX
  beq .Lsvsr_done

  ldl w6
  lu
  stl w5

  ldl w11
  ldl w5
  sb

  ldl w11
  inc
  stl w11

  ldl w6
  inc
  stl w6

  ldl w12
  inc
  stl w12

  bra .Lsvsr_loop

.Lsvsr_done:
  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  ldl w12
  sb                    ; strlen

  ldl w12
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; strvars_set_z(w0=namePtr, w1=len, w2=srcZPtr)
; stores a NUL-terminated source string (up to STR_VAR_DATA_MAX).
; out: w0 = stored length
; ------------------------------------------------------------
strvars_set_z:
  stl w14
  ldl w2
  stl w6
.Lsvsz_find_end:
  ldl w6
  lu
  i0
  beq .Lsvsz_have_end
  ldl w6
  inc
  stl w6
  bra .Lsvsz_find_end
.Lsvsz_have_end:
  ldl w2
  stl w2
  ldl w6
  stl w3
  CALL strvars_set_range
  ldl w14
  jr


; ------------------------------------------------------------
; arr_slot_to_entry(w0=slot) -> w0=entryPtr
; offset = slot*36 = (slot<<5) + (slot<<2)
; ------------------------------------------------------------
arr_slot_to_entry:
  stl w14
  ldl w0
  stl w2
  ldl w2
  sll 4
  sll 1
  stl w3               ; *32
  ldl w2
  sll 2
  ldl w3
  add
  stl w3               ; *36
  i ARR_DESC_BASE
  ldl w3
  add
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; arr_find_desc(w0=namePtr, w1=len) -> w0=entryPtr, w1=1 found else w0=0,w1=0
; ------------------------------------------------------------
arr_find_desc:
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
  u 7
  and
  stl w9
  i ARR_SLOTS
  stl w13
.Lafd_loop:
  ldl w13
  i0
  beq .Lafd_not_found
  ldl w9
  stl w0
  CALL arr_slot_to_entry
  ldl w0
  stl w10
  ldl w10
  lu
  i0
  beq .Lafd_not_found
  ldl w10
  stl w0
  ldl w6
  stl w1
  ldl w7
  stl w2
  CALL vars_name_match
  ldl w0
  u 1
  beq .Lafd_found
  ldl w9
  inc
  u 7
  and
  stl w9
  ldl w13
  dec
  stl w13
  bra .Lafd_loop
.Lafd_found:
  ldl w10
  stl w0
  u 1
  stl w1
  ldl w14
  jr
.Lafd_not_found:
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; arr_dim(w0=namePtr, w1=len, w2=maxIndexInclusive) -> w0=1 success else 0
; ------------------------------------------------------------
arr_dim:
  stl w14
  ; reject negative max index
  ldl w2
  i 0x8000
  and
  i0
  beq .Lad_nonneg
  i0
  stl w0
  ldl w14
  jr
.Lad_nonneg:
  ; save args
  ldl w0
  push
  ldl w1
  push
  ldl w2
  push
  ; existing desc? fail
  CALL arr_find_desc
  ldl w1
  i1
  bne .Lad_restore_fail_found
  ; restore args
  pop
  stl w8               ; maxidx
  pop
  stl w7               ; len
  pop
  stl w6               ; namePtr
  ; elements = maxidx + 1
  ldl w8
  inc
  stl w12
  ; bytes = elements * 2
  ldl w12
  sll 1
  stl w11
  ; top = SYS_ARR_TOP
  i SYS_ARR_TOP_LO
  lu
  stl w1
  i SYS_ARR_TOP_HI
  lu
  stl w2
  ldl w2
  sll 4
  sll 4
  ldl w1
  add
  stl w9               ; base/new alloc start
  ; newTop = top + bytes
  ldl w9
  ldl w11
  add
  stl w10
  ; require newTop <= ARR_DATA_LIMIT
  ldl w10
  i ARR_DATA_LIMIT
  beq .Lad_space_ok
  ldl w10
  i ARR_DATA_LIMIT
  bltu .Lad_space_ok
  i0
  stl w0
  ldl w14
  jr
.Lad_space_ok:
  ; find first empty descriptor
  i0
  stl w13
.Lad_find_slot:
  ldl w13
  i ARR_SLOTS
  bltu .Lad_slot_check
  i0
  stl w0
  ldl w14
  jr
.Lad_slot_check:
  ldl w13
  stl w0
  CALL arr_slot_to_entry
  ldl w0
  stl w5
  ldl w5
  lu
  i0
  beq .Lad_slot_empty
  ldl w13
  inc
  stl w13
  bra .Lad_find_slot
.Lad_slot_empty:
  ; create desc: len+name
  ldl w5
  ldl w7
  sb
  ldl w5
  u 1
  add
  stl w3
  ldl w6
  stl w4
  ldl w7
  stl w2
.Lad_copy_name:
  ldl w2
  i0
  beq .Lad_meta
  ldl w4
  lu
  stl w1
  ldl w3
  ldl w1
  sb
  ldl w3
  inc
  stl w3
  ldl w4
  inc
  stl w4
  ldl w2
  dec
  stl w2
  bra .Lad_copy_name
.Lad_meta:
  ; store max index
  ldl w5
  i (1 + VAR_NAME_MAX)
  add
  ldl w8
  u 0xFF
  and
  sb
  ldl w5
  i (2 + VAR_NAME_MAX)
  add
  ldl w8
  srl 4
  srl 4
  u 0xFF
  and
  sb
  ; store base
  ldl w5
  i (3 + VAR_NAME_MAX)
  add
  ldl w9
  u 0xFF
  and
  sb
  ldl w5
  i (4 + VAR_NAME_MAX)
  add
  ldl w9
  srl 4
  srl 4
  u 0xFF
  and
  sb
  ; zero allocated bytes [base, newTop)
  ldl w9
  stl w6
.Lad_zero_loop:
  ldl w6
  ldl w10
  beq .Lad_zero_done
  ldl w6
  u 0
  sb
  ldl w6
  inc
  stl w6
  bra .Lad_zero_loop
.Lad_zero_done:
  ; publish new top
  i SYS_ARR_TOP_LO
  ldl w10
  u 0xFF
  and
  sb
  i SYS_ARR_TOP_HI
  ldl w10
  srl 4
  srl 4
  u 0xFF
  and
  sb
  u 1
  stl w0
  ldl w14
  jr
.Lad_restore_fail_found:
  pop
  stl w4
  pop
  stl w4
  pop
  stl w4
  i0
  stl w0
  ldl w14
  jr

; ------------------------------------------------------------
; arr_elem_ptr(w0=namePtr, w1=len, w2=index) -> w0=elemPtr, w1=1/0
; ------------------------------------------------------------
arr_elem_ptr:
  stl w14
  ldl w2
  stl w8               ; index
  ; reject negative
  ldl w8
  i 0x8000
  and
  i0
  beq .Laep_nonneg
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Laep_nonneg:
  CALL arr_find_desc
  ldl w1
  i1
  beq .Laep_have
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Laep_have:
  ldl w0
  stl w10
  ; maxidx
  ldl w10
  i (1 + VAR_NAME_MAX)
  add
  lu
  stl w2
  ldl w10
  i (2 + VAR_NAME_MAX)
  add
  lu
  stl w3
  ldl w3
  sll 4
  sll 4
  ldl w2
  add
  stl w4
  ; index <= maxidx ?
  ldl w8
  ldl w4
  beq .Laep_bounds_ok
  ldl w8
  ldl w4
  bltu .Laep_bounds_ok
  i0
  stl w0
  i0
  stl w1
  ldl w14
  jr
.Laep_bounds_ok:
  ; base ptr
  ldl w10
  i (3 + VAR_NAME_MAX)
  add
  lu
  stl w2
  ldl w10
  i (4 + VAR_NAME_MAX)
  add
  lu
  stl w3
  ldl w3
  sll 4
  sll 4
  ldl w2
  add
  stl w5
  ; ptr = base + index*2
  ldl w8
  sll 1
  ldl w5
  add
  stl w0
  u 1
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; arr_get(w0=namePtr, w1=len, w2=index) -> w0=value, w1=1/0
; ------------------------------------------------------------
arr_get:
  stl w14
  CALL arr_elem_ptr
  ldl w1
  i1
  beq .Lag_have
  ldl w14
  jr
.Lag_have:
  ldl w0
  stl w10
  ldl w10
  lu
  stl w2
  ldl w10
  inc
  lu
  stl w3
  ldl w3
  sll 4
  sll 4
  ldl w2
  add
  stl w0
  u 1
  stl w1
  ldl w14
  jr

; ------------------------------------------------------------
; arr_set(w0=namePtr, w1=len, w2=index, w3=value) -> w0=1/0
; ------------------------------------------------------------
arr_set:
  stl w14
  ldl w3
  stl w12              ; value
  CALL arr_elem_ptr
  ldl w1
  i1
  beq .Las_have
  i0
  stl w0
  ldl w14
  jr
.Las_have:
  ldl w0
  stl w10
  ldl w10
  ldl w12
  u 0xFF
  and
  sb
  ldl w10
  inc
  ldl w12
  srl 4
  srl 4
  u 0xFF
  and
  sb
  u 1
  stl w0
  ldl w14
  jr
