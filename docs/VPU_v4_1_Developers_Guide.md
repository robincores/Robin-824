# VPU v4.1 Developer Guide (Frozen)

This guide targets the **frozen VPU v4.1** implementation (Java emulator + FPGA-first register model).
It’s written for **game/demo programmers** and for **future RTL implementation**.

---

## 1) Big Picture

VPU v4.1 is a “VGA-ish” planar video chip with:

- **Output:** 640×400 ARGB (emulator display)
- **Logical “game” resolution for playfields & sprites:** **640×200** (scanlines are doubled: `yOut = ySrc*2`)
- **Text overlay:** 80×25 characters @ 8×16 (1bpp glyphs), drawn at full 640×400
- **VRAM:** 8 bitplanes × 16K (planar 1bpp per plane) = 128K
- **Sprite pattern banks:** 7 × 16K packed **4bpp** (banks 0x9..0xF)
- **Copper tables bank:** 16K (bank 0x8), holds the copper program

Core idea (Nintendo/FPGA spirit): **simple contiguous destination walking** + **bitplane tricks** + **copper timing**.

---

## 2) Addressing Model (VideoWindow)

The CPU sees a **single 16K window** (call it `VIDWIN`, often mapped at `0xC000..0xFFFF`).
Two external selectors (outside the VPU itself) decide what that 16K window shows:

- `WIN_MMIO`  
  - `1` → MMIO view (registers, palette, OAM, group table, text/font)
  - `0` → banked memory view (VRAM planes, tables bank, sprite banks)

- `VBANK` (4-bit)
  - `0..7` → VRAM plane 0..7 (planar 1bpp)
  - `8` → Tables bank (copper program storage)
  - `9..F` → Sprite pattern banks (packed 4bpp)

**FPGA note:** this is exactly a mux on the 16K window: `WIN_MMIO ? mmio : banks[VBANK]`.

---

## 3) Memory Map in MMIO Mode (WIN_MMIO=1)

Offsets below are **inside the 16K window**.

### 3.1 Core regs (timing/IRQ)
- `0x0000` CTRL
- `0x0001` STATUS
- `0x0002..0x0003` SCAN (read-only current scanline)
- `0x0004..0x0005` RASTER_CMP (raster IRQ compare)

### 3.2 Text regs
- `0x0006` TX_CTRL
- `0x0007` TX_CUR_X
- `0x0008` TX_CUR_Y
- `0x0009` TX_CUR_START (scanline within glyph, 0..15)
- `0x000A` TX_CUR_END
- `0x000B..0x000C` TX_ORIGIN (ring-buffer origin in cells)
- `0x000D` TX_FINE_Y (0..15)
- `0x000E` TX_FINE_X (0..7)
- `0x000F` TX_ATTR (default attribute used by TX_PORT ops)
- `0x0030` TX_CMD (write-only command)
- `0x0031` TX_PORT (write-only “tty” port)

### 3.3 Copper-friendly SET/CLR (write-only)
These avoid read-modify-write (good for copper, good for FPGA simplicity):
- `0x0060/0x0061` CTRL_SET / CTRL_CLR
- `0x0062/0x0063` TX_CTRL_SET / TX_CTRL_CLR
- `0x0064/0x0065` SPR_CTRL_SET / SPR_CTRL_CLR
- `0x0066/0x0067` COP_CTRL_SET / COP_CTRL_CLR
- `0x0068/0x0069` WM_CTRL_SET / WM_CTRL_CLR
- `0x0070..0x0077` per-group FLAGS_SET / FLAGS_CLR (targets group FLAGS byte)

### 3.4 Palette (256× RGB565, little-endian)
- `0x0100..0x02FF` (512 bytes)
- Entry `i`:
  - low byte at `0x0100 + i*2`
  - high byte at `0x0100 + i*2 + 1`

### 3.5 Collision registers
- `0x0300..0x030F` (16 bytes of hit bits for sprites 0..127)
- `0x0310` write-anything clears hit flags

### 3.6 Group descriptor table (4 groups × 16 bytes)
- Base: `0x0400`, stride `0x10`
- Fields used by the current hardware model:
  - `+0` PLANE_START (0..7)
  - `+1` PLANE_COUNT (0 disables group)
  - `+2` PAL_BASE (added to pixel index)
  - `+3` SCROLL_X_L
  - `+4` SCROLL_X_H (signed 16-bit)
  - `+5` SCROLL_Y   (0..199; clamped if WRAP_Y off)
  - `+6` PRIORITY   (smaller draws earlier)
  - `+7` FLAGS
  - `+8` BPL_OFS_L  (14-bit base offset into each plane)
  - `+9` BPL_OFS_H
  - remaining bytes currently unused

### 3.7 OAM (Sprite Attribute Table)
- `0x1000..0x1FFF` (4K)
- 128 sprites × 32 bytes each

### 3.8 Text RAM + Font
- Text cells: `0x2000..0x2F9F` (80×25×2 = 4000 bytes)
- Reserved gap: `0x2FA0..0x2FFF`
- Font 8×16 1bpp: `0x3000..0x3FFF` (4096 bytes, 256 glyphs × 16 rows)

### 3.9 Write-modes (VGA-ish planar writes)
- `0x0510` WM_CTRL
- `0x0511` WM_PLANE_MASK
- `0x0512` WM_SETRESET
- `0x0513` WM_BITMASK

### 3.10 Blitter registers (VRAM-only operations)
- `0x0560..` (see Section 8)

### 3.11 VPU ID
- `0x00FF` returns `0x41` (v4.1)

---

## 4) CTRL / STATUS / IRQ

### CTRL bits
- `0x01` ENABLE
- `0x02` VBL_IRQ_EN
- `0x04` RASTER_IRQ_EN
- `0x08` GFX_DIS (force background color 0)

### STATUS bits
- `0x01` VBLANK (read-only timing owned)
- `0x02` FRAME (W1C)
- `0x04` RASTER (W1C)
- `0x08` CFG_ERR (W1C) — invalid config was clamped/ignored
- `0x10` BLT (W1C) — blitter finished

**W1C**: write `1` to clear the bit.

### IRQ behavior (high level)
- On vblank start: set `VBLANK|FRAME`, raise IRQ if `CTRL.VBL_IRQ_EN`
- On scanline == RASTER_CMP: set `RASTER`, raise IRQ if `CTRL.RASTER_IRQ_EN`
- On blitter finish: set `BLT`, raise IRQ if `BLT_CTRL.IRQ_EN`

---

## 5) Rendering Model (How pixels appear)

### 5.1 “Double-scan” rule
Playfields and sprites are authored in **200 lines**:
- For display scanline `yOut` (0..399):
  - `ySrc = yOut >> 1` (0..199)

Text uses the full 400 scanlines (25×16).

### 5.2 Groups (playfields)
A group is a **contiguous range of planes** (e.g., planes 0..7 = 8bpp).

- Group priority sorting: lower `PRIORITY` is drawn earlier
- **Plane overlap rule:** if a later group overlaps already-claimed planes, it’s disabled and `CFG_ERR` is set

Group flags:
- `LORES (0x01)` → group is 320-wide source but output is doubled horizontally (40 bytes/row instead of 80)
- `WRAP_X (0x02)` → wrap X addressing
- `WRAP_Y (0x04)` → wrap Y addressing
- `OPAQUE0 (0x08)` → treat pixel index 0 as opaque (normally transparent)

**Pseudo-code (conceptual):**
```text
for each output scanline yOut:
  ySrc = yOut >> 1
  underlay = background_color_0

  draw sprites in slot 0 (below all groups)

  for each group in sorted-by-priority:
    if group enabled:
      sy = ySrc + group.scrollY   (wrap or clamp)
      read bytes for this row from each plane
      expand plane bits -> colorIndex 0..(2^planeCount-1)
      if colorIndex != 0 or OPAQUE0: plot palette[palBase + colorIndex]
    draw sprites in next slot (between groups)
```

### 5.3 Per-plane base pointers (mid-frame trick)
MMIO provides 8 plane base registers (2 bytes each). The renderer adds them to plane offsets.
Use this for **cheap page flipping**, **copper-controlled splits**, or **multiple screens in one plane**.

---

## 6) Write Modes (WM_*) — Making an 8-bit CPU practical

When `WM_CTRL.WM_EN` is set, **writes to VRAM planes 0..7** go through a VGA-like planar write pipeline.

Registers:
- `WM_PLANE_MASK` (bit per plane; 0 disables all)
- `WM_BITMASK` (8-bit; selects which bits of the destination byte are affected)
- `WM_SETRESET` (8-bit; bit p controls plane p when USE_SR enabled)
- `WM_CTRL` low nibble:
  - bit0 `WM_EN`
  - bit1 `WM_USE_SR`
  - bits2-3 ROP:
    - 0 COPY (masked)
    - 1 OR
    - 2 AND
    - 3 XOR

**Pseudo-code (what hardware does per CPU byte write):**
```text
for each plane p where planeMask[p]==1:
  old = VRAM[p][addr]
  src = (USE_SR ? (setReset[p] ? 0xFF : 0x00) : cpuData)
  out = ROP(old, src, bitMask)
  VRAM[p][addr] = out
```

**Use cases**
- Plot pixels: set `WM_BITMASK` to a single bit, use `SETRESET` to choose color bits.
- Draw masked spans: set `WM_BITMASK` to a run.
- XOR cursor/selection effects.

FPGA note: this block is tiny and incredibly useful.

---

## 7) Sprites (Lynx-ish 4bpp with scaling)

### 7.1 Sprite bank addressing
Sprite pixel data is stored in **packed 4bpp** banks 0x9..0xF (7 banks).
Each byte stores 2 pixels: high nibble = even pixel, low nibble = odd pixel (as used by the renderer).

### 7.2 OAM layout (32 bytes per sprite)

Offsets inside a sprite entry (`base = 0x1000 + spriteIndex*32`):

| Off | Size | Name | Notes |
|---:|---:|---|---|
| 0..1 | s16 | X | output pixels (0..639 typical) |
| 2..3 | s16 | Y | **source-space** lines (0..199 typical) |
| 4 | u8 | W | 0 means 256 |
| 5 | u8 | H | 0 means 256 |
| 6..7 | u16 | DATA | bits13..0 = offset, bits15..14 = bankLo |
| 8 | u8 | ATTR | enable/flip/chain/collide/tilt + bankHi |
| 9 | u8 | PAL_BASE | palette base for sprite (adds to 0..15) |
| 10 | u8 | COL_ID | low nibble used; 0 disables collision tagging |
| 11 | u8 | PRIORITY | compared against group priorities for slot |
| 12..13 | u16 | XSTEP | 8.8; 0 treated as 1.0 |
| 14..15 | u16 | YSTEP | 8.8; 0 treated as 1.0 |
| 16 | s8 | TILT_DX | added per source row if TILT enabled |
| 17 | u8 | LINK | 0..127 used when CHAIN enabled |
| 18..31 | — | (reserved) | currently unused |

ATTR bits:
- bit0 EN
- bit1 HFLIP
- bit2 VFLIP
- bit3 CHAIN
- bit4 COLLIDE
- bit5 TILT
- bits6-7 bankHi

Bank select is combined:
```text
bank = (ATTR[7:6] << 2) | DATA[15:14]
clamped to 0x9..0xF in v4.1
```

### 7.3 Scaling model
- Output scanline uses `ySrc = yOut>>1`
- Sprite uses YSTEP to map output-y to source row
- XSTEP maps output-x to source pixel index

**Pseudo-code (one scanline, simplified):**
```text
dy = ySrc - spriteY
srcRow = (dy * YSTEP) >> 8
for each output pixel i in visible span:
  srcX = (i * XSTEP) >> 8
  pix4 = read4bpp(bank, baseOfs + srcRow*rowStride + srcX)
  if pix4 != 0: plot palette[palBase + pix4]
```

### 7.4 Collision flags
If COLLIDE enabled and COL_ID != 0, VPU tags pixels and sets hit bits when IDs differ.
Read hit bits at `0x0300..0x030F`, clear by writing `0x0310`.

---

## 8) Copper (scanline-synchronous MMIO writes)

Copper program lives in **tables bank** (`VBANK=8`) and is enabled via MMIO regs:

- `0x0020` COP_CTRL (bit0 COP_EN)
- `0x0021..0x0022` COP_LEN (bytes)
- `0x0023..0x0024` COP_OFS (offset within bank 8)

Each copper instruction is **8 bytes**:
```text
+0..1 : SCAN   (u16) scanline where this fires
+2..3 : REG    (u16) mmio register offset to write (0..0x3FFF)
+4    : VLO    (u8)
+5    : VHI    (u8) used only if WRITE16
+6    : FLAGS  (u8)
+7    : (unused/pad)
```

Flags:
- `0x01` WRITE16 → write VLO then VHI to REG and REG+1
- `0x80` END → stop compilation

**Safety rules (FPGA sanity + emulator hardening)**
- Copper may only write to `0x0000..0x1FFF` (text/font live above; denied)
- Copper may NOT modify `COP_CTRL/LEN/OFS` (prevents self-modifying loops)

**Typical copper uses**
- Mid-frame scroll changes (parallax)
- Palette changes at specific scanlines (raster bars)
- Toggling group flags via FLAGS_SET/CLR regs
- Switching plane base pointers for splits (advanced)

---

## 9) Blitter

The blitter is a **VRAM-only engine** with cycle-based progress (`BLT_CYCLES_PER_BYTE = 2` in the emulator).

### 9.1 Control and common regs
- `0x0560` BLT_CTRL:
  - bit0 START (write 1 to start)
  - bit1 FILL (byte fill mode for byte ops)
  - bit2 IRQ_EN
  - bit3 DIR (force backward)
  - bit4 TRANS (copy transparency semantics)
  - bit5 AFFINE (RECTBLIT uses affine source matrix; frozen v4.1)
  - bit7 BUSY (read-only)
- `0x0561..0x0562` BLT_SRC (u16, 0..0x3FFF)
- `0x0563..0x0564` BLT_DST (u16, 0..0x3FFF)
- `0x0565..0x0566` BLT_W  (u16 bytes/row)
- `0x0567` BLT_H (u8 rows; 0 means 1)
- `0x0568..0x0569` BLT_SRC_PITCH (s16 delta-after-row for byte ops)
- `0x056A..0x056B` BLT_DST_PITCH (s16 delta-after-row for byte ops)
- `0x056C` BLT_FILL (byte value)
- `0x056D` BLT_PLANE_MASK (0 means all planes)
- `0x056E` BLT_ROP:
  - 0 COPY, 1 OR, 2 AND, 3 XOR
  - 4 RECTFILL (pixel coords)
  - 5 RECTBLIT (packed 4bpp → planar)
- `0x056F` BLT_SHIFT (0..7) for byte COPY/ROP ops
- `0x0570` BLT_FIRST_MASK
- `0x0571` BLT_LAST_MASK

### 9.2 RECTFILL (pixel-space rectangle fill)
Uses pixel coordinates and expands into planar bytes.

- `0x0572..0x0573` X (pixels)
- `0x0574..0x0575` Y (pixels)
- `0x0576..0x0577` W (pixels)
- `0x0578` H (pixels; 0 means 1)

Important: For RECTFILL, `BLT_DST_PITCH` is treated as **bytes-per-row**.

**Pseudo-code:**
```text
for y in 0..H-1:
  for x in 0..W-1:
    dstBit = (X+x) & 7
    dstByte = dstBase + (Y+y)*pitch + ((X+x)>>3)
    for each plane p:
      write bit dstBit based on (colorIndex>>p)&1
```

(Implementation is optimized to byte-walk with masks; RTL would do similar.)

### 9.3 RECTBLIT (packed 4bpp → planar)
Copies from sprite banks into planar VRAM (useful for stamping sprites into a background).

Registers:
- `0x0579` SRCBANK (0x9..0xF)
- `0x057A..0x057B` SRCOFS (u16 base within bank)
- `0x057C..0x057D` SX (pixels in packed source)
- `0x057E..0x057F` SY (pixels)
- `0x0580..0x0581` PSRC_PITCH (bytes-per-row in packed source)
- `0x0586..0x0589` SXSTEP/SYSTEP (8.8 scaling steps)

Destination stepping registers exist in the map, but **frozen v4.1 enforces 1:1**:
- `0x0582..0x0585` DXSTEP/DYSTEP are clamped to `0x0100` and set `CFG_ERR` if not.

**Affine source mapping (optional)**
If `BLT_CTRL.AFFINE` is set, RECTBLIT uses a 2×2 matrix to map destination pixels → source pixels:
```text
src(x,y) = [X0,Y0] + x*[SXX,SYX] + y*[SXY,SYY]   (S** are signed 8.8)
```

Affine regs (MMIO):
- `0x058A..0x058B` AFF_X0 (u16 pixels)
- `0x058C..0x058D` AFF_Y0 (u16 pixels)
- `0x058E..0x058F` AFF_SXX (s16 8.8)
- `0x0590..0x0591` AFF_SYX (s16 8.8)
- `0x0592..0x0593` AFF_SXY (s16 8.8)
- `0x0594..0x0595` AFF_SYY (s16 8.8)

**FPGA note:** affine is accumulator-friendly. Once per row you add SXY/SYY; per pixel you add SXX/SYX.

---

## 10) Text Overlay (80×25 @ 8×16)

### 10.1 Attribute format
Text cell: 2 bytes:
- byte0: character code (0..255)
- byte1: attribute:
  - low nibble = FG color (0..15)
  - high nibble = BG color (0..15)

Text control bits:
- `TX_EN` enable
- `TX_CURSOR_EN`, `TX_CURSOR_BLINK`
- `TX_TRANSPARENT_BG` (HUD-friendly)
- `TX_CHAR_BLINK` (uses attr bit7 to blink)

### 10.2 TX_PORT behavior (tty-like)
Write bytes to `TX_PORT`:
- `0x0A` newline
- `0x0D` carriage return
- `0x08` backspace
- `0x09` tab (8-column)
- otherwise prints the char using `TX_ATTR`

### 10.3 TX_CMD commands
Write a bitmask to `TX_CMD`:
- `0x01` clear EOL
- `0x02` clear line
- `0x04` clear screen
- `0x08` scroll up
- `0x10` home

---

# Programming Cookbook (Practical Recipes)

Below, “MMIO_WRITE(addr,val)” means writing an 8-bit value to MMIO offset `addr` inside the VideoWindow.

Where 16-bit writes are needed, write low then high (or use copper WRITE16).

---

## Recipe 1: Minimal boot init

```text
# Enable VPU and vblank IRQ (optional)
MMIO_WRITE(0x0000, CTRL_ENABLE | CTRL_VBL_IRQ_EN)

# Enable sprites
MMIO_WRITE(0x0014, SPR_EN)

# Enable text (optional; useful early boot)
MMIO_WRITE(0x0006, TX_EN | TX_CURSOR_EN | TX_CURSOR_BLINK)
MMIO_WRITE(0x000F, 0x07)  # white on black
```

---

## Recipe 2: Configure one full 8bpp playfield (Group 0 uses planes 0..7)

Group 0 base is `0x0400`.

```text
G0 = 0x0400
MMIO_WRITE(G0+0, 0)      # PLANE_START
MMIO_WRITE(G0+1, 8)      # PLANE_COUNT => enabled
MMIO_WRITE(G0+2, 0)      # PAL_BASE
MMIO_WRITE(G0+3, 0)      # SCROLL_X_L
MMIO_WRITE(G0+4, 0)      # SCROLL_X_H
MMIO_WRITE(G0+5, 0)      # SCROLL_Y
MMIO_WRITE(G0+6, 0)      # PRIORITY
MMIO_WRITE(G0+7, GF_WRAP_X | GF_WRAP_Y)  # FLAGS (or 0 for clamped)
MMIO_WRITE(G0+8, 0)      # BPL_OFS_L
MMIO_WRITE(G0+9, 0)      # BPL_OFS_H
```

If you want the convenience split mode (groups 0+1), write `REG_AUTO_SPLIT (0x0015)`.

---

## Recipe 3: Upload palette entries

```text
PAL = 0x0100
# Write RGB565 = 0bRRRRRGGGGGGBBBBB (16-bit)
writePalette(i, rgb565):
  MMIO_WRITE(PAL + i*2 + 0, rgb565 & 0xFF)
  MMIO_WRITE(PAL + i*2 + 1, (rgb565 >> 8) & 0xFF)
```

---

## Recipe 4: Clear a rectangle fast (RECTFILL)

Assume:
- framebuffer top-left address in each plane is `BLT_DST = 0x0000`
- bytes-per-row in hires is `pitch=80`

```text
MMIO_WRITE(0x056E, 4)      # BLT_ROP = RECTFILL
MMIO_WRITE(0x056D, 0xFF)   # plane mask (0 => all; using 0xFF is explicit)
MMIO_WRITE(0x056C, colorIndex)

# BLT_DST (u16)
MMIO_WRITE(0x0563, 0x00)   # DST_L
MMIO_WRITE(0x0564, 0x00)   # DST_H

# BLT_DST_PITCH = bytes-per-row for RECTFILL
MMIO_WRITE(0x056A, 80)     # pitch low
MMIO_WRITE(0x056B, 0)      # pitch high

# X,Y,W,H in pixels
MMIO_WRITE(0x0572, x & 0xFF); MMIO_WRITE(0x0573, x >> 8)
MMIO_WRITE(0x0574, y & 0xFF); MMIO_WRITE(0x0575, y >> 8)
MMIO_WRITE(0x0576, w & 0xFF); MMIO_WRITE(0x0577, w >> 8)
MMIO_WRITE(0x0578, h)      # 0 => 1

# START
MMIO_WRITE(0x0560, BLT_START)
```

Poll `STATUS.BLT` or enable `BLT_CTRL.IRQ_EN`.

---

## Recipe 5: Stamp a 4bpp sprite into VRAM (RECTBLIT)

Goal: take packed sprite pixels from sprite bank and write into planes (e.g. planes 0..3) at (x,y).

Assume:
- packed sprite is in `SRCBANK=0x9`
- `SRCOFS` points to the sprite’s packed base
- packed pitch is `(spriteW+1)/2`

```text
MMIO_WRITE(0x056E, 5)      # BLT_ROP = RECTBLIT
MMIO_WRITE(0x056D, 0x0F)   # planes 0..3 for 4bpp look (or 0xFF if you want “8-bit expand” tricks)

# dest base & pitch
MMIO_WRITE(0x0563, 0x00); MMIO_WRITE(0x0564, 0x00)  # BLT_DST
MMIO_WRITE(0x056A, 80);   MMIO_WRITE(0x056B, 0)     # pitch bytes/row

# rect in pixels
MMIO_WRITE(0x0572, x & 0xFF); MMIO_WRITE(0x0573, x >> 8)
MMIO_WRITE(0x0574, y & 0xFF); MMIO_WRITE(0x0575, y >> 8)
MMIO_WRITE(0x0576, w & 0xFF); MMIO_WRITE(0x0577, w >> 8)
MMIO_WRITE(0x0578, h)

# source params
MMIO_WRITE(0x0579, 0x09)                       # SRCBANK
MMIO_WRITE(0x057A, srcofs & 0xFF); MMIO_WRITE(0x057B, srcofs >> 8)
MMIO_WRITE(0x057C, sx & 0xFF);     MMIO_WRITE(0x057D, sx >> 8)
MMIO_WRITE(0x057E, sy & 0xFF);     MMIO_WRITE(0x057F, sy >> 8)
MMIO_WRITE(0x0580, pitchPacked & 0xFF); MMIO_WRITE(0x0581, pitchPacked >> 8)

# scaling (8.8): 0x0100 = 1.0; 0x0080 = 0.5; 0x0200 = 2.0
MMIO_WRITE(0x0586, sxstep & 0xFF); MMIO_WRITE(0x0587, sxstep >> 8)
MMIO_WRITE(0x0588, systep & 0xFF); MMIO_WRITE(0x0589, systep >> 8)

# DXSTEP/DYSTEP must be 0x0100 in frozen v4.1
MMIO_WRITE(0x0582, 0x00); MMIO_WRITE(0x0583, 0x01)
MMIO_WRITE(0x0584, 0x00); MMIO_WRITE(0x0585, 0x01)

# START (optionally set TRANS)
MMIO_WRITE(0x0560, BLT_START | (useTransparent ? BLT_TRANS : 0))
```

---

## Recipe 6: Affine RECTBLIT (rotation / shear / scale)

Set `BLT_CTRL.AFFINE` and program the matrix.

Convert float → 8.8 fixed:
```text
fix88(x) = round(x * 256)
```

For rotation by angle θ:
```text
SXX =  cosθ
SYX = -sinθ
SXY =  sinθ
SYY =  cosθ
```

Write:
```text
MMIO_WRITE(0x058A..0x058B, X0 pixels)
MMIO_WRITE(0x058C..0x058D, Y0 pixels)
MMIO_WRITE(0x058E..0x0595, S** as signed 16-bit 8.8)
MMIO_WRITE(0x0560, BLT_START | BLT_AFFINE | (optional BLT_TRANS))
```

FPGA note: steady-state pixel loop is add-only.

---

## Recipe 7: Setup one sprite (scaled 4bpp)

```text
OAM = 0x1000 + spriteIndex*32

# X,Y in output/source space
MMIO_WRITE(OAM+0, x & 0xFF); MMIO_WRITE(OAM+1, x >> 8)
MMIO_WRITE(OAM+2, y & 0xFF); MMIO_WRITE(OAM+3, y >> 8)

MMIO_WRITE(OAM+4, w)   # 0 => 256
MMIO_WRITE(OAM+5, h)

# DATA: offset in bits13..0; plus bankLo in bits15..14
MMIO_WRITE(OAM+6, dataLo); MMIO_WRITE(OAM+7, dataHi)

# ATTR: enable + bankHi + options
MMIO_WRITE(OAM+8, SA_EN | (bankHi<<6))

MMIO_WRITE(OAM+9, palBase)
MMIO_WRITE(OAM+11, priority)

# XSTEP/YSTEP (8.8)
MMIO_WRITE(OAM+12, xstep & 0xFF); MMIO_WRITE(OAM+13, xstep >> 8)
MMIO_WRITE(OAM+14, ystep & 0xFF); MMIO_WRITE(OAM+15, ystep >> 8)
```

---

## Recipe 8: Copper raster split (change one palette entry at scanline 120)

In tables bank (VBANK=8), write an 8-byte op:

```text
op[0..1] = 120
op[2..3] = 0x0100 + entry*2   # palette low byte addr
op[4]    = newLow
op[5]    = newHigh
op[6]    = COP_FLAG_WRITE16
op[7]    = 0
```

Then an END op (flags 0x80).

Enable copper:
```text
MMIO_WRITE(0x0023..0x0024, ofs)   # COP_OFS
MMIO_WRITE(0x0021..0x0022, len)   # COP_LEN in bytes
MMIO_WRITE(0x0020, COP_EN)
```

---

## Recipe 9: HUD text with transparent background

```text
MMIO_WRITE(0x0006, TX_EN | TX_TRANSPARENT_BG) # keep cursor off if you want
MMIO_WRITE(0x000F, (bg<<4) | fg)              # TX_ATTR

for ch in "SCORE: 012345":
  MMIO_WRITE(0x0031, ch)
```

---

## Recipe 10: Detect and clear CFG_ERR

```text
status = MMIO_READ(0x0001)
if status & STATUS_CFG_ERR:
  MMIO_WRITE(0x0001, STATUS_CFG_ERR)  # W1C
```

Common causes:
- invalid group plane overlap
- illegal DXSTEP/DYSTEP for RECTBLIT
- copper tried to write forbidden regs

---

# FPGA-first Notes (Design Discipline)

- Prefer **latch-at-START** behavior (already used heavily by blitter).
- Keep “fast paths” simple and predictable (groups: byte-walk; sprites: 4bpp fetch + palette).
- Copper is a **scanline scheduler**; avoid making it too powerful (self-mod rules already help).
- For rotation/affine, keep destination marching contiguous; do source mapping via accumulators.
- For the emulator, keep the Java hot loops friendly to JIT: fewer branches, fewer allocations, stable array references.

---

## If you want me to keep this guide 100% in sync
Some earlier uploads can expire from the workspace. If you update/freeze the VPU again and want this guide regenerated from the exact source, re-upload the new frozen `VPU_v4_1.java` (or the frozen zip) and I’ll regenerate the tables from it.
