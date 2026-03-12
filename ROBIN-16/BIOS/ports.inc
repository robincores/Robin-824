.once

; BIOS/R816/ports.inc
; Pure constants / MMIO map (no code)

; ---------------- Ports ----------------
.equ KBD_BASE          0xBF00
.equ KBD_DATA          KBD_BASE + 0x0000
.equ KBD_STATUS        KBD_BASE + 0x0001       ; bit0=hasData

.equ SYS_MMIO_BASE     0xBFF0
.equ SYS_VIDWIN        SYS_MMIO_BASE + 0x0000
; VIDWIN bits
;   bits 0..3 : VBANK (0..15) selects which 16K bank is visible at 0xC000..0xFFFF
;   bit  4    : WIN_MMIO (1 => expose VPU MMIO, 0 => expose banked memory)
.equ VIDWIN_VBANK_MASK 0x0F
.equ VIDWIN_MMIO_BIT   0x10                    ; WIN_MMIO=1 -> 0xC000.. is VPU MMIO

.equ VPU_BASE          0xC000
.equ VPU_CTRL          VPU_BASE + 0x0000
.equ VPU_STATUS        VPU_BASE + 0x0001
; Legacy alias kept for older code (v4.1 offset 0x0002 is SCAN_L RO)
.equ VPU_MODE          VPU_BASE + 0x0002

; Text overlay (VPU v4.1)
.equ VPU_TX_CTRL       VPU_BASE + 0x0006
.equ VPU_TX_CUR_X      VPU_BASE + 0x0007
.equ VPU_TX_CUR_Y      VPU_BASE + 0x0008
.equ VPU_TX_CUR_START  VPU_BASE + 0x0009
.equ VPU_TX_CUR_END    VPU_BASE + 0x000A

.equ VPU_TX_ORG_L      VPU_BASE + 0x000B
.equ VPU_TX_ORG_H      VPU_BASE + 0x000C
.equ VPU_TX_FINE_Y     VPU_BASE + 0x000D
.equ VPU_TX_FINE_X     VPU_BASE + 0x000E
.equ VPU_TX_ATTR       VPU_BASE + 0x000F

.equ VPU_TX_CMD        VPU_BASE + 0x0030
.equ VPU_TX_PORT       VPU_BASE + 0x0031

; Legacy/unused by v4.1 (kept so old code assembles; writes are ignored by VPU_v4_1)
.equ VPU_OVL_MODE      VPU_BASE + 0x001F

; ---------------- VPU constants ----------------

; Choose graphics mode (0..3)
.equ GFX_MODE          0x02

; REG_CTRL bits (NEW: bit3 gfx disable)
.equ CTRL_ENABLE       0x01
.equ CTRL_GFX_DIS      0x08

; Overlay over graphics (default)
.equ CTRL_DEFAULT      0x01          ; ENABLE only (graphics underlay ON)

; TX_CTRL bits
.equ TX_EN             0x01
.equ TX_CURSOR_EN      0x02
.equ TX_TRANSPARENT_BG 0x04
.equ TX_CURSOR_BLINK   0x08

; OPAQUE terminal overlay (classic)
.equ TXCTRL_DEFAULT    0x0B          ; TX_EN | CURSOR | BLINK

; TX_CMD bits
.equ TXCMD_CLR_SCREEN  0x04
.equ TXCMD_HOME        0x10
.equ TXCMD_HOME_CLS    0x14          ; HOME|CLR_SCREEN

; Overlay personality
.equ OVL_TEXT          0x00
.equ OVL_TILES         0x01