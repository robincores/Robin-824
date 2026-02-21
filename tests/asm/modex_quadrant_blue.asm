; modex_quadrant_blue_bra.asm
; MODE 2 (320x200, 8bpp Mode-X). Fill top-left quadrant (160x100) BLUE.
; Uses BRA (rel8) instead of J.

.arch r816

start:
    ; --- MMIO window on (0xC000.. = VPU regs) ---
    i  0xBFF0
    u  0x04                 ; VIDWIN: WIN_MMIO=1
    sb

    ; --- disable text overlay (otherwise it covers graphics) ---
    i  0xC007                ; TX_CTRL
    u  0x00                  ; TX_EN=0
    sb

    ; --- MODE=2 ---
    i  0xC002
    u  0x02
    sb

    ; --- FB base = 0x0000 ---
    i  0xC005
    u  0x00
    sb
    i  0xC006
    u  0x00
    sb

    ; --- enable VPU ---
    i  0xC000
    u  0x01
    sb

    ; plane = 0
    i0
    stl @2

plane_loop:
    ; map VRAM plane into 0xC000 window (WIN_MMIO=0, VBANK=plane)
    i   0xBFF0
    ldl @2
    sb

    ; y = 0
    i0
    stl @3

    ; yCount = 100
    i   100
    stl @6

y_loop:
    ; if (yCount == 0) next_plane
    ldl @6
    i0
    beq next_plane

    ; rowBase = 0xC000 + y*80  (80 = 64 + 16)
    ldl @3          ; y
    dup
    sll 4           ; 16y
    swap
    sll 4           ; 16y
    sll 2           ; 64y
    add             ; 80y
    i   0xC000
    add
    stl @4          ; rowBase

    ; ptr = rowBase
    ldl @4
    stl @5

    ; xCount = 40 bytes (160 pixels / 4)
    i   40
    stl @1

x_loop:
    ; if (xCount == 0) end_row
    ldl @1
    i0
    beq end_row

    ; [ptr] = BLUE (use 0x01 for blue in default VGA-ish palette)
    ldl @5
    u   0x01
    sb

    ; ptr++
    inc
    stl @5

    ; xCount--
    ldl @1
    dec
    stl @1

    bra x_loop

end_row:
    ; y++
    ldl @3
    inc
    stl @3

    ; yCount--
    ldl @6
    dec
    stl @6

    bra y_loop

next_plane:
    ; plane++
    ldl @2
    inc
    stl @2

    ; while (plane < 4)
    ldl @2
    i   4
    blt plane_loop

done:
    bra done