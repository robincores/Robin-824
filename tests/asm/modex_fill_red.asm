; modex_fill_red.asm — Sanity: fill ModeX (320x200, 8bpp, 4 planes) with solid RED
; RGB332 red = 0xE0

.arch r816

start:
    ; --- select MMIO window so 0xC000.. hits VPU regs ---
    i  0xBFF0
    u  0x04         ; VIDWIN: WIN_MMIO=1
    sb

    ; --- MODE=2 (320x200, 8bpp ModeX) ---
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
    ; map VRAM plane into 0xC000 window (WIN_MMIO=0 when writing 0..3)
    i   0xBFF0
    ldl @2
    sb

    ; ptr = 0xC000
    i   0xC000
    stl @0

    ; count = 16000 bytes (320*200/4)
    i   0x3E80
    stl @1

byte_loop:
    ; if (count == 0) goto next_plane
    ldl @1
    i0
    beq next_plane

    ; [ptr] = 0xE0 (red)
    ldl @0
    u   0xE0
    sb

    ; ptr++
    ldl @0
    inc
    stl @0

    ; count--
    ldl @1
    dec
    stl @1

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
