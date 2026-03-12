; ============================================================
; vpu41.inc  (R816 / r8as-style macros)
; Assumes:
;   i <imm16>   pushes address/immediate
;   u <imm8>    pushes byte immediate
;   sb          store byte  (addr, val)
; ============================================================

; --------------------------
; VideoWindow control (system-side)
; --------------------------
.equ VIDWIN_REG,        0xBFF0

; NOTE: you used 0x04 in your old demos for WIN_MMIO.
; Keep it, but bank encoding may differ on your system.
.equ VIDWIN_WIN_MMIO,   0x04

; Guess/placeholder: bank lives in upper nibble.
; If your hardware uses a different encoding, change this one line.
.equ VIDWIN_BANK_SHIFT, 4

; Window base (your demos use 0xC000..0xFFFF as the 16K VideoWindow)
.equ VPU_WIN_BASE,      0xC000

; --------------------------
; VPU v4.1 MMIO offsets (these are OFFSETS inside the window)
; --------------------------
.equ VPU_REG_CTRL,          0x0000
.equ VPU_REG_STATUS,        0x0001
.equ VPU_REG_SCAN_L,        0x0002
.equ VPU_REG_SCAN_H,        0x0003
.equ VPU_REG_RASTER_CMP_L,  0x0004
.equ VPU_REG_RASTER_CMP_H,  0x0005

.equ VPU_REG_TX_CTRL,       0x0006
.equ VPU_REG_TX_CUR_X,      0x0007
.equ VPU_REG_TX_CUR_Y,      0x0008
.equ VPU_REG_TX_ATTR,       0x000F
.equ VPU_REG_TX_CMD,        0x0030
.equ VPU_REG_TX_PORT,       0x0031

.equ VPU_REG_SPR_CTRL,      0x0014

.equ VPU_REG_COP_CTRL,      0x0020
.equ VPU_REG_COP_LEN_L,     0x0021
.equ VPU_REG_COP_LEN_H,     0x0022
.equ VPU_REG_COP_OFS_L,     0x0023
.equ VPU_REG_COP_OFS_H,     0x0024

; Palette MMIO region
.equ VPU_PAL_BASE,          0x0100   ; 256 * RGB565 little-endian

; Group table MMIO region
.equ VPU_GROUP_BASE,        0x0400
.equ VPU_GROUP_STRIDE,      0x0010

; OAM MMIO region
.equ VPU_OAM_BASE,          0x1000

; Blitter (needed for RECTFILL/RECTBLIT convenience)
.equ VPU_BLT_CTRL,          0x0560
.equ VPU_BLT_DST_L,         0x0563
.equ VPU_BLT_DST_H,         0x0564
.equ VPU_BLT_DST_PITCH_L,   0x056A
.equ VPU_BLT_DST_PITCH_H,   0x056B
.equ VPU_BLT_FILL,          0x056C
.equ VPU_BLT_PLANE_MASK,    0x056D
.equ VPU_BLT_ROP,           0x056E
.equ VPU_BLT_FIRST_MASK,    0x0570
.equ VPU_BLT_LAST_MASK,     0x0571
.equ VPU_BLT_X_L,           0x0572
.equ VPU_BLT_X_H,           0x0573
.equ VPU_BLT_Y_L,           0x0574
.equ VPU_BLT_Y_H,           0x0575
.equ VPU_BLT_WPX_L,         0x0576
.equ VPU_BLT_WPX_H,         0x0577
.equ VPU_BLT_HPX,           0x0578

.equ VPU_BLT_SRCBANK,       0x0579
.equ VPU_BLT_SRCOFS_L,      0x057A
.equ VPU_BLT_SRCOFS_H,      0x057B
.equ VPU_BLT_SX_L,          0x057C
.equ VPU_BLT_SX_H,          0x057D
.equ VPU_BLT_SY_L,          0x057E
.equ VPU_BLT_SY_H,          0x057F
.equ VPU_BLT_PSRC_PITCH_L,  0x0580
.equ VPU_BLT_PSRC_PITCH_H,  0x0581

; BLT_CTRL bits
.equ BLT_START,             0x01
.equ BLT_FILL_BIT,          0x02
.equ BLT_IRQ_EN,            0x04
.equ BLT_DIR,               0x08
.equ BLT_TRANS,             0x10

; BLT_ROP opcodes (your Java)
.equ BLT_OP_COPY,           0
.equ BLT_OP_OR,             1
.equ BLT_OP_AND,            2
.equ BLT_OP_XOR,            3
.equ BLT_OP_RECTFILL,       4
.equ BLT_OP_RECTBLIT,       5

; --------------------------
; NEW SET/CLR offsets you added
; --------------------------
.equ VPU_REG_CTRL_SET,      0x0060
.equ VPU_REG_CTRL_CLR,      0x0061
.equ VPU_REG_TX_CTRL_SET,   0x0062
.equ VPU_REG_TX_CTRL_CLR,   0x0063
.equ VPU_REG_SPR_CTRL_SET,  0x0064
.equ VPU_REG_SPR_CTRL_CLR,  0x0065
.equ VPU_REG_COP_CTRL_SET,  0x0066
.equ VPU_REG_COP_CTRL_CLR,  0x0067
.equ VPU_REG_WM_CTRL_SET,   0x0068
.equ VPU_REG_WM_CTRL_CLR,   0x0069

.equ VPU_REG_G0_FLAGS_SET,  0x0070
.equ VPU_REG_G0_FLAGS_CLR,  0x0071
.equ VPU_REG_G1_FLAGS_SET,  0x0072
.equ VPU_REG_G1_FLAGS_CLR,  0x0073
.equ VPU_REG_G2_FLAGS_SET,  0x0074
.equ VPU_REG_G2_FLAGS_CLR,  0x0075
.equ VPU_REG_G3_FLAGS_SET,  0x0076
.equ VPU_REG_G3_FLAGS_CLR,  0x0077

; --------------------------
; Convenience bit masks (from your Java)
; --------------------------
.equ TX_EN,                 0x01
.equ TX_CURSOR_EN,          0x02
.equ TX_TRANSPARENT_BG,     0x04
.equ TX_CURSOR_BLINK,       0x08
.equ TX_CHAR_BLINK,         0x10

.equ SPR_EN,                0x01

; Group FLAGS bits
.equ GF_LORES,              0x01
.equ GF_WRAP_X,             0x02
.equ GF_WRAP_Y,             0x04
.equ GF_OPAQUE0,            0x08


; ============================================================
; Window selection macros
; ============================================================

.macro VPU_SEL_MMIO
    i  VIDWIN_REG
    u  VIDWIN_WIN_MMIO
    sb
.endm

.macro VPU_SEL_BANK bank
    i  VIDWIN_REG
    u  ((\bank & 0x0F) << VIDWIN_BANK_SHIFT)
    sb
.endm


; ============================================================
; Basic MMIO write helpers
; ============================================================

; Write one byte to MMIO offset (assumes MMIO already selected)
.macro VPU_MMIO8 ofs, val8
    i  (VPU_WIN_BASE + (\ofs & 0x3FFF))
    u  (\val8 & 0xFF)
    sb
.endm

; Write little-endian 16-bit to MMIO offset (two byte writes)
.macro VPU_MMIO16 ofs, val16
    VPU_MMIO8 \ofs,      (\val16 & 0xFF)
    VPU_MMIO8 (\ofs+1),  ((\val16 >> 8) & 0xFF)
.endm


; ============================================================
; SET/CLR convenience macros
; ============================================================

.macro VPU_CTRL_SET mask
    VPU_MMIO8 VPU_REG_CTRL_SET, (\mask)
.endm
.macro VPU_CTRL_CLR mask
    VPU_MMIO8 VPU_REG_CTRL_CLR, (\mask)
.endm

.macro VPU_TX_SET mask
    VPU_MMIO8 VPU_REG_TX_CTRL_SET, (\mask)
.endm
.macro VPU_TX_CLR mask
    VPU_MMIO8 VPU_REG_TX_CTRL_CLR, (\mask)
.endm

.macro VPU_SPR_SET mask
    VPU_MMIO8 VPU_REG_SPR_CTRL_SET, (\mask)
.endm
.macro VPU_SPR_CLR mask
    VPU_MMIO8 VPU_REG_SPR_CTRL_CLR, (\mask)
.endm

; Group flags: gi = 0..3
.macro VPU_GFLAGS_SET gi, mask
    VPU_MMIO8 (VPU_REG_G0_FLAGS_SET + ((\gi & 3) << 1)), (\mask)
.endm
.macro VPU_GFLAGS_CLR gi, mask
    VPU_MMIO8 (VPU_REG_G0_FLAGS_CLR + ((\gi & 3) << 1)), (\mask)
.endm


; ============================================================
; Palette helpers (RGB565 little-endian)
; idx 0..255, rgb565 0..65535
; ============================================================

.macro VPU_PAL_WRITE idx, rgb565
    .equ __palOfs, (VPU_PAL_BASE + ((\idx & 0xFF) << 1))
    VPU_MMIO8 __palOfs,     (\rgb565 & 0xFF)
    VPU_MMIO8 (__palOfs+1), ((\rgb565 >> 8) & 0xFF)
.endm


; ============================================================
; RECTFILL helper
; dstBase = BLT_DST byte address for (0,0)
; pitch   = bytes per row in framebuffer (40 for lores 320, 80 for hires 640)
; x,y,w,h in pixels, color is 0..255, planeMask bits0..7
; ============================================================

.macro VPU_RECTFILL dstBase, pitch, x, y, w, h, color, planeMask
    ; program
    VPU_MMIO16 VPU_BLT_DST_L,        (\dstBase & 0x3FFF)
    VPU_MMIO16 VPU_BLT_DST_PITCH_L,  (\pitch & 0xFFFF)
    VPU_MMIO8  VPU_BLT_PLANE_MASK,   (\planeMask & 0xFF)
    VPU_MMIO8  VPU_BLT_FILL,         (\color & 0xFF)
    VPU_MMIO8  VPU_BLT_ROP,          BLT_OP_RECTFILL

    VPU_MMIO16 VPU_BLT_X_L,          (\x & 0xFFFF)
    VPU_MMIO16 VPU_BLT_Y_L,          (\y & 0xFFFF)
    VPU_MMIO16 VPU_BLT_WPX_L,        (\w & 0xFFFF)
    VPU_MMIO8  VPU_BLT_HPX,          (\h & 0xFF)

    ; start
    VPU_MMIO8  VPU_BLT_CTRL,         BLT_START
.endm


; ============================================================
; RECTBLIT helper (packed 4bpp sprite bank -> planar)
; srcBank: 0x9..0xF
; srcOfs : 0..0x3FFF
; sx,sy  : source pixel coords in packed source
; srcPitchBytes : bytes per packed row (0 => auto in VPU)
; Uses dest rect regs (x,y,w,h) like RECTFILL
; ============================================================

.macro VPU_RECTBLIT dstBase, pitch, x, y, w, h, planeMask, srcBank, srcOfs, sx, sy, srcPitchBytes, trans
    VPU_MMIO16 VPU_BLT_DST_L,        (\dstBase & 0x3FFF)
    VPU_MMIO16 VPU_BLT_DST_PITCH_L,  (\pitch & 0xFFFF)
    VPU_MMIO8  VPU_BLT_PLANE_MASK,   (\planeMask & 0xFF)
    VPU_MMIO8  VPU_BLT_ROP,          BLT_OP_RECTBLIT

    VPU_MMIO16 VPU_BLT_X_L,          (\x & 0xFFFF)
    VPU_MMIO16 VPU_BLT_Y_L,          (\y & 0xFFFF)
    VPU_MMIO16 VPU_BLT_WPX_L,        (\w & 0xFFFF)
    VPU_MMIO8  VPU_BLT_HPX,          (\h & 0xFF)

    VPU_MMIO8  VPU_BLT_SRCBANK,      (\srcBank & 0xFF)
    VPU_MMIO16 VPU_BLT_SRCOFS_L,     (\srcOfs & 0x3FFF)
    VPU_MMIO16 VPU_BLT_SX_L,         (\sx & 0xFFFF)
    VPU_MMIO16 VPU_BLT_SY_L,         (\sy & 0xFFFF)
    VPU_MMIO16 VPU_BLT_PSRC_PITCH_L, (\srcPitchBytes & 0xFFFF)

    ; start: optionally include TRANS in BLT_CTRL
    .if (\trans)
        VPU_MMIO8 VPU_BLT_CTRL, (BLT_START | BLT_TRANS)
    .else
        VPU_MMIO8 VPU_BLT_CTRL, BLT_START
    .endif
.endm