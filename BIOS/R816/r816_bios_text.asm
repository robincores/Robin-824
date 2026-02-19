; ------------------------------------------------------------
; R816 BIOS-style text console for Mode-X planar VRAM
; 320x200x8bpp, 4 planes, 80 bytes/scanline/plane
; Text: 40 cols x 25 rows, 8x8 font (CP-437)
;
; VRAM window base (selected plane): 0xC000
; VIDWIN (plane select register):    0xBFF0   (write 0..3)
;
; Requires: cp437_ega_8x8_font.asm providing:
;   cp437_ega_8x8_font: .byte ... (2048 bytes)
; ------------------------------------------------------------

.arch r816

; -------------------------
; Includes
; -------------------------
.include "cp437_ega_8x8_font.asm"

; -------------------------
; Constants
; -------------------------
; (No .equ assumed; we just inline immediates)

; -------------------------
; BIOS state (in RAM)
; -------------------------
.section .data

bios_cursor_x: .byte 0      ; 0..39
bios_cursor_y: .byte 0      ; 0..24
bios_fg:       .byte 0x0F   ; default white-ish
bios_bg:       .byte 0x00   ; default black

; bit masks for glyph bits (pixel 0..7)
mask8:
  .byte 0x80,0x40,0x20,0x10,0x08,0x04,0x02,0x01


; ============================================================
; Low-level VPU helpers
; ============================================================

.section .text

; ------------------------------------------------------------
; vpu_set_plane(p)
;   input: @0 = plane (0..3)
;   clobbers: A,B
; ------------------------------------------------------------
vpu_set_plane:
  ; write plane to VIDWIN (0xBFF0)
  i 0xBFF0
  ldl @0
  sb
  jr


; ------------------------------------------------------------
; vpu_cls_color(bg)
;   input: @4 = bg color
;   clears full 320x200 for all 4 planes
;   clobbers: @0,@6..@11 (NOT @12+)
; ------------------------------------------------------------
vpu_cls_color:
  ; save return address in @11 (leaf-ish)
  stl @11

  ; plane = 0
  u 0
  stl @0

.vpu_cls_plane_loop:
  ; set plane(@0)
  jal vpu_set_plane

  ; ptr = 0xC000
  i 0xC000
  stl @6

  ; count = 16000 (80*200)
  i 16000
  stl @7

.vpu_cls_loop:
  ; if count == 0 -> done plane
  ldl @7
  u 0
  beq .vpu_cls_plane_done

  ; *ptr = bg
  ldl @6
  ldl @4
  sb

  ; ptr++
  ldl @6
  u 1
  add
  stl @6

  ; count--
  ldl @7
  u 1
  sub
  stl @7

  j .vpu_cls_loop

.vpu_cls_plane_done:
  ; plane++
  ldl @0
  u 1
  add
  stl @0

  ; if plane == 4 -> done
  ldl @0
  u 4
  beq .vpu_cls_done

  j .vpu_cls_plane_loop

.vpu_cls_done:
  ldl @11
  jr


; ------------------------------------------------------------
; vpu_putc_xy_opaque(cx, cy, ch, fg, bg)
;   inputs:
;     @0 = cx (0..39)
;     @1 = cy (0..24)
;     @2 = ch (0..255)
;     @3 = fg color
;     @4 = bg color
;
; Planar correct:
;   Each plane holds pixels where x%4 == plane.
;   Each byte in plane memory is ONE pixel value.
;
; For an 8-pixel glyph:
;   group0 bytes at [base+0] for pixels 0..3 via planes 0..3
;   group1 bytes at [base+1] for pixels 4..7 via planes 0..3
;
; Clobbers: @5..@11 (NOT @12+)
; ------------------------------------------------------------
vpu_putc_xy_opaque:
  stl @11   ; save RA

  ; --------------------------------------
  ; base = 0xC000 + (cy*640) + (cx*2)
  ; where 640 = 8*80 bytes (8 scanlines per text row)
  ; --------------------------------------

  ; yoff = cy * 640
  i 640
  ldl @1
  mul
  stl @5          ; yoff

  ; xoff = cx * 2
  u 2
  ldl @0
  mul
  stl @6          ; xoff

  ; baseOff = yoff + xoff
  ldl @5
  ldl @6
  add
  stl @5          ; baseOff

  ; baseAddr = 0xC000 + baseOff
  i 0xC000
  ldl @5
  add
  stl @5          ; baseAddr

  ; --------------------------------------
  ; glyphPtr = &font[ch*8]
  ; --------------------------------------
  ldl @2
  sll 3
  stl @6          ; ch*8

  i cp437_ega_8x8_font
  ldl @6
  add
  stl @6          ; glyphPtr

  ; row = 0
  u 0
  stl @7

  ; rowBase = baseAddr
  ldl @5
  stl @8

.row_loop:
  ; if row == 8 -> done
  ldl @7
  u 8
  beq .done_rows

  ; glyphRow = *glyphPtr
  ldl @6
  lu
  stl @9          ; glyphRow

  ; glyphPtr++
  ldl @6
  u 1
  add
  stl @6

  ; plane = 0
  u 0
  stl @10

.plane_loop:
  ; if plane == 4 -> next row
  ldl @10
  u 4
  beq .next_row

  ; set plane
  ldl @10
  stl @0
  jal vpu_set_plane

  ; mask0 = mask8[plane]
  i mask8
  ldl @10
  add
  lu
  stl @0          ; mask0

  ; mask1 = mask8[plane+4]
  i mask8
  ldl @10
  u 4
  add
  add
  lu
  stl @1          ; mask1

  ; byte0 = (glyphRow & mask0) ? fg : bg
  ldl @9
  ldl @0
  and
  u 0
  beq .b0_is_bg
  ldl @3
  j .b0_set
.b0_is_bg:
  ldl @4
.b0_set:
  stl @0          ; byte0

  ; byte1 = (glyphRow & mask1) ? fg : bg
  ldl @9
  ldl @1
  and
  u 0
  beq .b1_is_bg
  ldl @3
  j .b1_set
.b1_is_bg:
  ldl @4
.b1_set:
  stl @1          ; byte1

  ; store byte0 at rowBase+0
  ldl @8
  ldl @0
  sb

  ; store byte1 at rowBase+1
  ldl @8
  u 1
  add
  ldl @1
  sb

  ; plane++
  ldl @10
  u 1
  add
  stl @10
  j .plane_loop

.next_row:
  ; rowBase += 80
  ldl @8
  i 80
  add
  stl @8

  ; row++
  ldl @7
  u 1
  add
  stl @7

  j .row_loop

.done_rows:
  ldl @11
  jr


; ============================================================
; BIOS-style API
; ============================================================

; ------------------------------------------------------------
; bios_init()
;   sets default fg/bg, cursor to 0,0, clears screen
; ------------------------------------------------------------
bios_init:
  stl @12  ; save RA (bios layer uses @12+)

  ; cursor = 0,0
  i bios_cursor_x
  u 0
  sb
  i bios_cursor_y
  u 0
  sb

  ; default attr
  i bios_fg
  u 0x0F
  sb
  i bios_bg
  u 0x00
  sb

  ; cls(bg)
  u 0x00
  stl @4
  jal vpu_cls_color

  ldl @12
  jr


; ------------------------------------------------------------
; bios_set_attr(fg,bg)
;   input: @3=fg, @4=bg
; ------------------------------------------------------------
bios_set_attr:
  stl @12
  i bios_fg
  ldl @3
  sb
  i bios_bg
  ldl @4
  sb
  ldl @12
  jr


; ------------------------------------------------------------
; bios_gotoxy(x,y)
;   input: @0=x, @1=y
; ------------------------------------------------------------
bios_gotoxy:
  stl @12
  i bios_cursor_x
  ldl @0
  sb
  i bios_cursor_y
  ldl @1
  sb
  ldl @12
  jr


; ------------------------------------------------------------
; bios_cls()
;   clears with current bg and homes cursor
; ------------------------------------------------------------
bios_cls:
  stl @12

  ; load bg
  i bios_bg
  lu
  stl @4
  jal vpu_cls_color

  ; home cursor
  i bios_cursor_x
  u 0
  sb
  i bios_cursor_y
  u 0
  sb

  ldl @12
  jr


; ------------------------------------------------------------
; bios_putc(ch)  (teletype)
;   input: @2=ch
;   handles: \n \r \b \t, wrap, scroll
; ------------------------------------------------------------
bios_putc:
  stl @12

  ; --- handle CR (13)
  ldl @2
  u 13
  beq .cr

  ; --- handle LF (10)  (we treat as newline: CR+LF)
  ldl @2
  u 10
  beq .lf

  ; --- handle BS (8)
  ldl @2
  u 8
  beq .bs

  ; --- handle TAB (9)
  ldl @2
  u 9
  beq .tab

  ; otherwise printable -> draw at cursor and advance
.printable:
  ; load cursor x,y
  i bios_cursor_x
  lu
  stl @0
  i bios_cursor_y
  lu
  stl @1

  ; load fg/bg
  i bios_fg
  lu
  stl @3
  i bios_bg
  lu
  stl @4

  ; draw char (@0,@1,@2,@3,@4)
  jal vpu_putc_xy_opaque

  ; x++
  i bios_cursor_x
  lu
  u 1
  add
  stl @0

  ; if x == 40 -> newline
  ldl @0
  u 40
  beq .newline_from_wrap

  ; store x
  i bios_cursor_x
  ldl @0
  sb

  ldl @12
  jr

.newline_from_wrap:
  ; x=0; y++
  u 0
  stl @0
  i bios_cursor_x
  ldl @0
  sb

  i bios_cursor_y
  lu
  u 1
  add
  stl @1
  i bios_cursor_y
  ldl @1
  sb

  ; if y == 25 -> scroll
  ldl @1
  u 25
  beq .scroll_up

  ldl @12
  jr


.cr:
  ; x = 0
  i bios_cursor_x
  u 0
  sb
  ldl @12
  jr

.lf:
  ; newline = x=0; y++
  i bios_cursor_x
  u 0
  sb

  i bios_cursor_y
  lu
  u 1
  add
  stl @1
  i bios_cursor_y
  ldl @1
  sb

  ; if y == 25 -> scroll
  ldl @1
  u 25
  beq .scroll_up

  ldl @12
  jr


.tab:
  ; x = (x + 8) & 0xF8; if x==40 -> newline
  i bios_cursor_x
  lu
  u 8
  add
  u 0xF8
  and
  stl @0

  ldl @0
  u 40
  beq .lf   ; tab hitting col 40 -> newline

  i bios_cursor_x
  ldl @0
  sb

  ldl @12
  jr


.bs:
  ; backspace: if x>0: x-- else if y>0: y--, x=39 else do nothing
  i bios_cursor_x
  lu
  stl @0

  ldl @0
  u 0
  beq .bs_x0

  ; x--
  ldl @0
  u 1
  sub
  stl @0
  i bios_cursor_x
  ldl @0
  sb
  j .bs_erase

.bs_x0:
  i bios_cursor_y
  lu
  stl @1

  ldl @1
  u 0
  beq .bs_done  ; at (0,0) do nothing

  ; y--
  ldl @1
  u 1
  sub
  stl @1
  i bios_cursor_y
  ldl @1
  sb

  ; x = 39
  u 39
  stl @0
  i bios_cursor_x
  ldl @0
  sb

.bs_erase:
  ; draw space at new cursor position
  u 32
  stl @2

  ; load y
  i bios_cursor_y
  lu
  stl @1
  ; load x
  i bios_cursor_x
  lu
  stl @0

  ; load fg/bg
  i bios_fg
  lu
  stl @3
  i bios_bg
  lu
  stl @4

  jal vpu_putc_xy_opaque

.bs_done:
  ldl @12
  jr


; ------------------------------------------------------------
; scroll up by 1 text row (8 scanlines)
; copies 192 scanlines up and clears last 8 scanlines
; ------------------------------------------------------------
.scroll_up:
  ; bg needed for clear
  i bios_bg
  lu
  stl @4

  ; set y = 24
  u 24
  i bios_cursor_y
  swap
  sb

  ; plane = 0..3
  u 0
  stl @0

.scroll_plane_loop:
  ldl @0
  u 4
  beq .scroll_done

  ; set plane
  jal vpu_set_plane

  ; src = 0xC000 + 640
  i 0xC000
  i 640
  add
  stl @6

  ; dst = 0xC000
  i 0xC000
  stl @7

  ; count = 15360 (80*192)
  i 15360
  stl @8

.copy_loop:
  ldl @8
  u 0
  beq .clear_last

  ; tmp = *src
  ldl @6
  lu
  stl @9

  ; *dst = tmp
  ldl @7
  ldl @9
  sb

  ; src++, dst++, count--
  ldl @6
  u 1
  add
  stl @6

  ldl @7
  u 1
  add
  stl @7

  ldl @8
  u 1
  sub
  stl @8

  j .copy_loop

.clear_last:
  ; clearPtr = 0xC000 + 15360
  i 0xC000
  i 15360
  add
  stl @7

  ; clearCount = 640
  i 640
  stl @8

.clear_loop:
  ldl @8
  u 0
  beq .next_plane

  ldl @7
  ldl @4
  sb

  ldl @7
  u 1
  add
  stl @7

  ldl @8
  u 1
  sub
  stl @8

  j .clear_loop

.next_plane:
  ldl @0
  u 1
  add
  stl @0
  j .scroll_plane_loop

.scroll_done:
  ldl @12
  jr


; ------------------------------------------------------------
; bios_puts(ptr)
;   input: @5 = pointer to null-terminated string
; ------------------------------------------------------------
bios_puts:
  stl @13   ; deeper save than bios_putc (@12)

.puts_loop:
  ; ch = *ptr
  ldl @5
  lu
  stl @2

  ; if ch == 0 -> done
  ldl @2
  u 0
  beq .puts_done

  jal bios_putc

  ; ptr++
  ldl @5
  u 1
  add
  stl @5

  j .puts_loop

.puts_done:
  ldl @13
  jr
