; ------------------------------------------------------------
; RGB332 palette showcase (4 blue slices side-by-side)
; R816 / ModeX 320x200 planar (4 planes)
;
; Assumptions (edit to match your system):
;   VID_MODE  = 0xFF00   ; write 1 to enable ModeX (example)
;   VID_PLANE = 0xFF01   ; write 0..3 selects plane
;   VRAM window for selected plane starts at 0xC000
;
; Grid:
;   swatchW = 8 px  (=> 2 bytes per plane per scanline)
;   swatchH = 24 px
;   cols = 32, rows = 8
;   left margin = 32 px => leftBytes = 32/4 = 8 bytes
;   top margin  = 4 lines
;
; RGB332:
;   color = (r<<5) | (g<<2) | b
; ------------------------------------------------------------

.arch r816

        .text

start:
; --- set ModeX (example) ---
        i   0xFF00          ; VID_MODE
        u   1               ; ModeX ON (example value)
        sb

; --- clear VRAM: all 4 planes, 16000 bytes/plane using 8000 word stores ---
        u   0
        stl w0              ; w0 = plane

plane_clear:
        ; select plane
        i   0xFF01          ; VID_PLANE
        ldl w0
        sb

        i   0xC000
        stl w3              ; w3 = ptr

        i   8000
        stl w9              ; w9 = wordCount

        u   0
        stl w10             ; w10 = 0 (word)

clear_loop:
        ldl w3
        ldl w10
        st                  ; [ptr] = 0x0000

        ldl w3
        inc
        inc
        stl w3              ; ptr += 2

        ldl w9
        dec
        stl w9              ; wordCount--

        ldl w9
        i0
        bne clear_loop

        ; next plane
        ldl w0
        inc
        stl w0

        ; if plane == 4 => done clearing, else repeat
        ldl w0
        u   4
        beq clear_done
        j plane_clear

clear_done:

; --- draw palette (repeat for each plane) ---
        u   0
        stl w0              ; w0 = plane

plane_draw:
        ; select plane
        i   0xFF01          ; VID_PLANE
        ldl w0
        sb

        ; rowStart = VRAM_BASE + topMargin*80 + leftBytes
        ;          = 0xC000 + 4*80 + 8 = 0xC148
        i   0xC148
        stl w4              ; w4 = rowStart (current scanline start)

        u   0
        stl w1              ; w1 = g (0..7)

g_start:
        ; gPart = g << 2
        ldl w1
        sll 2
        stl w7              ; w7 = gPart

        u   0
        stl w2              ; w2 = yrep (0..23)

y_start:
        ; ptr = rowStart
        ldl w4
        stl w3              ; w3 = ptr

        u   0
        stl w5              ; w5 = b (0..3)

b_start:
        ; gbBase = gPart + b
        ldl w7
        ldl w5
        add
        stl w8              ; w8 = gbBase

        u   0
        stl w6              ; w6 = r (0..7)

r_start:
        ; rPart = r << 5  (<<4 then <<1)
        ldl w6
        sll 4
        sll 1

        ; color = rPart + gbBase
        ldl w8
        add
        stl w9              ; w9 = color (0..255)

        ; packed = color | (color<<8)  => color + (color<<8)
        ldl w9
        stl w13             ; w13 = color

        ldl w9
        sll 4
        sll 4               ; (color << 8)

        ldl w13
        add
        stl w10             ; w10 = packed word (color repeated)

        ; store packed at ptr
        ldl w3
        ldl w10
        st

        ; ptr += 2
        ldl w3
        inc
        inc
        stl w3

        ; r++
        ldl w6
        inc
        stl w6

        ldl w6
        u   8
        bne r_start

        ; b++
        ldl w5
        inc
        stl w5

        ldl w5
        u   4
        bne b_start

        ; next scanline: rowStart += 80
        ldl w4
        u   80
        add
        stl w4

        ; yrep++
        ldl w2
        inc
        stl w2

        ldl w2
        u   24
        beq y_done
        j y_start

y_done:
        ; g++
        ldl w1
        inc
        stl w1

        ldl w1
        u   8
        beq g_done
        j g_start

g_done:
        ; next plane
        ldl w0
        inc
        stl w0

        ldl w0
        u   4
        beq draw_done
        j plane_draw

draw_done:
        hlt
