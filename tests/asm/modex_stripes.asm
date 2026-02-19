; Mode X sanity demo (STRIPES) for R816 v1 VPU
; MODE=2: 320x200, 8bpp, 4 byte-planes (x&3 selects plane)
; Writes constant per plane -> 1-pixel vertical stripes:
;   plane0=0x10, plane1=0x50, plane2=0x90, plane3=0xD0  (0x10 + plane*64)

.arch r816

start:
    i  0xBFF0
    u  0x04                 ; VIDWIN: WIN_MMIO=1
    sb

    i  0xC002
    u  0x02                 ; MODE=2
    sb

    i  0xC005
    u  0x00                 ; FB_BASE_L
    sb
    i  0xC006
    u  0x00                 ; FB_BASE_H
    sb

    i  0xC000
    u  0x01                 ; CTRL enable
    sb

    i0
    stl @2                  ; plane=0

plane_loop:
    i   0xBFF0
    ldl @2
    sb                      ; VIDWIN=plane (WIN_MMIO=0)

    i   0xC000
    stl @0                  ; ptr=0xC000

    i   0x3E80
    stl @1                  ; count=16000

    ; color = 0x10 + (plane << 6)   [<<6 = (<<4) then (<<2)]
    ldl @2
    sll 4
    sll 2
    u   0x10
    add
    stl @3                  ; save color

byte_loop:
    ldl @1
    i0
    beq next_plane

    ldl @0
    ldl @3
    sb                      ; [ptr]=color

    ldl @0
    inc
    stl @0                  ; ptr++

    ldl @1
    dec
    stl @1                  ; count--

    j byte_loop

next_plane:
    ldl @2
    inc
    stl @2

    ldl @2
    i   4
    blt plane_loop

done:
    j done
