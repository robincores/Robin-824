; modex_ramp.asm — Sanity B
; MODE=2: 320x200, 8bpp, 4 planes (x&3 selects plane)
; Fill each plane with a different 64-color ramp so interleaving is visible.

.arch r816

start:
    ; enable MMIO window
    i  0xBFF0
    u  0x04              ; VIDWIN: WIN_MMIO=1
    sb

    ; VPU mode = 2 (ModeX)
    i  0xC002
    u  0x02
    sb

    ; framebuffer base = 0
    i  0xC005
    u  0x00              ; FB_BASE_L
    sb
    i  0xC006
    u  0x00              ; FB_BASE_H
    sb

    ; enable VPU
    i  0xC000
    u  0x01              ; CTRL: enable
    sb

    i0
    stl @2               ; plane = 0

plane_loop:
    ; select plane (must clear WIN_MMIO bit, so value is 0..3)
    i   0xBFF0
    ldl @2
    sb                   ; VIDWIN = plane

    i   0xC000
    stl @0               ; ptr = 0xC000

    i   0x3E80
    stl @1               ; count = 16000 bytes (320*200/4)

byte_loop:
    ldl @1
    i0
    beq next_plane

    ; tmp = ptr & 0x3F
    ldl @0
    dup
    u   0x3F
    and

    ; base = plane << 6  (<<4 then <<2)
    ldl @2
    sll 4
    sll 2

    add                  ; color = tmp + base
    sb                   ; [ptr] = color (addr in B, data in A)

    inc
    stl @0               ; ptr++

    ldl @1
    dec
    stl @1               ; count--

    j byte_loop

next_plane:
    ldl @2
    inc
    stl @2               ; plane++

    ldl @2
    i   4
    blt plane_loop       ; while (plane < 4)

done:
    j done
