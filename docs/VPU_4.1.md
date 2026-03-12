# VPU v4.1 Developer’s Guide

## 1) What VPU v4.1 is

VPU v4.1 is a **planar, scanline-based video processor** designed for an 8-bit data bus CPU (R8 family) that still wants:

* **fast filled graphics** (planar write modes + blitter),
* **sprites with scaling** (Lynx-ish),
* **copper** for raster tricks,
* **always-on text console** for boot/debug.

The defining idea: the CPU sees **one 16KB “VideoWindow”** at a time. That window can expose:

* **MMIO** (registers + text/font + OAM + palette), or
* **banked video memory** (VRAM planes, sprite banks, tables bank).

---

## 2) Output model and timing

### 2.1 Output resolution and scan mapping

* Output framebuffer: **640×400** ARGB (host side).
* Source graphics space: **640×200** effective for graphics.
* VPU renders **each output scanline `yOut` from source scanline `ySrc = yOut >> 1`** (so 200 source lines doubled to 400).

This means:

* Any graphics plane, sprites, groups operate in **200-line space**.
* The text layer is **80×25 with 16 scanlines per row = 400 scanlines**, so text is naturally aligned to the 640×400 output.

### 2.2 Key scanline counters and events

Internally:

* `scanline` counts scanlines across the full frame timing (including vblank region).
* At `scanline == 0`, `beginFrame()` latches per-frame decisions and rebuilds sprite/group state.
* At `scanline == vblankStart`, VPU:

    * sets `STATUS_VBLANK` and `STATUS_FRAME`,
    * queues/publishes the completed frame,
    * optionally raises an IRQ (if enabled).

### 2.3 Raster IRQ vs “live rendering”

If `CTRL_RASTER_IRQ_EN` is enabled, the VPU may render scanlines “live” as time progresses (instead of rendering the whole frame at vblank). This exists to support raster/copper-like effects with predictable ordering.

---

## 3) Memory map / VideoWindow model

### 3.1 The 16KB window

The CPU maps a 16KB region as **VideoWindow**. A separate system register (`WIN_MMIO`) selects whether that window points to:

* **MMIO view** (register space, palette, OAM, text/font), or
* **banked view** (VBANK selects which 16KB bank is visible).

VPU v4.1 assumes this contract:

* **WIN_MMIO=1** → CPU reads/writes VPU registers and MMIO memory.
* **WIN_MMIO=0** → CPU reads/writes the selected 16KB VBANK.

> This guide documents only the VPU side. Your system’s memory-mapper defines the actual CPU address where the VideoWindow appears (e.g. `0xC000..0xFFFF`).

### 3.2 VBANK map (WIN_MMIO=0)

| VBANK | Meaning              | Backing                 | Format                                      |
| ----: | -------------------- | ----------------------- | ------------------------------------------- |
|  0..7 | VRAM planes 0..7     | `vram[plane*16K + ofs]` | **1bpp per plane**                          |
|     8 | Tables bank          | `tblBank[ofs]`          | packed bytes (copper program, future lists) |
|  9..F | Sprite pattern banks | `sprBanks[bank-9][ofs]` | **packed 4bpp** (2 pixels per byte)         |

All banks are exactly **16KB**; offset always wraps with `ofs & 0x3FFF`.

---

## 4) Rendering pipeline (high level)

Each output scanline `yOut`:

1. **Copper** (optional): if enabled, apply any copper writes scheduled for this scanline.
2. **Underlay** (graphics):

    * background color index 0,
    * sprite slot 0 (below groups),
    * group 0..N in priority order, with sprite slots between groups.
3. **Text overlay** (optional): 80×25 terminal layer drawn on top (opaque or transparent).

Text is always “available” because the text RAM/font are in MMIO.

---

## 5) Core registers (MMIO)

MMIO offsets are **within the 16KB MMIO view** (i.e. `offset & 0x3FFF`).

### 5.1 Version / ID

| Reg      |     Addr | R/W | Meaning                     |
| -------- | -------: | :-: | --------------------------- |
| `VPU_ID` | `0x00FF` |  RO | Returns `0x41` for **v4.1** |

Use this in ROM to detect you’re running on v4+.

---

### 5.2 Control & Status

| Reg            |     Addr |    R/W   | Bits / Meaning                                                                                 |
| -------------- | -------: | :------: | ---------------------------------------------------------------------------------------------- |
| `CTRL`         | `0x0000` |    RW    | `bit0 ENABLE` (1=run), `bit1 VBL_IRQ_EN`, `bit2 RASTER_IRQ_EN`, `bit3 GFX_DIS`                 |
| `STATUS`       | `0x0001` | RO + W1C | `bit0 VBLANK`, `bit1 FRAME` (W1C), `bit2 RASTER` (W1C), `bit3 CFG_ERR` (W1C), `bit4 BLT` (W1C) |
| `SCAN_L`       | `0x0002` |    RO    | current scanline low byte                                                                      |
| `SCAN_H`       | `0x0003` |    RO    | current scanline high byte                                                                     |
| `RASTER_CMP_L` | `0x0004` |    RW    | compare scanline low byte                                                                      |
| `RASTER_CMP_H` | `0x0005` |    RW    | compare scanline high byte                                                                     |

**Behavior notes**

* When `CTRL.ENABLE=0`, VPU does not advance timing or render.
* `GFX_DIS=1` forces graphics underlay to solid palette index 0 (text may still draw).
* `STATUS` bits 1..4 are **write-one-to-clear**. Write a mask containing the bits you want to clear.

---

## 6) Palette (MMIO)

Palette is **256 entries of RGB565** stored in MMIO:

* Base: `PAL_BASE = 0x0100`
* Size: `PAL_SIZE = 0x0200` bytes (512)

Each entry is **little-endian RGB565**:

* low byte at `PAL_BASE + idx*2`
* high byte at `PAL_BASE + idx*2 + 1`

Example:

* entry 5:

    * low at `0x010A`
    * high at `0x010B`

Whenever you update palette entries, the renderer uses them immediately. Text fast-path caches are invalidated when palette changes.

---

## 7) Text console (MMIO)

Text is a built-in terminal layer:

* **80×25**
* **8×16 font**
* Total: 2000 cells
* Each cell = 2 bytes:

    * `char`
    * `attr`

### 7.1 Text control registers

| Reg            |     Addr | R/W | Meaning                                                                                       |
| -------------- | -------: | :-: | --------------------------------------------------------------------------------------------- |
| `TX_CTRL`      | `0x0006` |  RW | `bit0 TX_EN`, `bit1 CURSOR_EN`, `bit2 TRANSPARENT_BG`, `bit3 CURSOR_BLINK`, `bit4 CHAR_BLINK` |
| `TX_CUR_X`     | `0x0007` |  RW | cursor X (0..79)                                                                              |
| `TX_CUR_Y`     | `0x0008` |  RW | cursor Y (0..24)                                                                              |
| `TX_CUR_START` | `0x0009` |  RW | cursor start scanline within cell row (0..15)                                                 |
| `TX_CUR_END`   | `0x000A` |  RW | cursor end scanline (0..15)                                                                   |
| `TX_ORIGIN_L`  | `0x000B` |  RW | text origin (cell index) low                                                                  |
| `TX_ORIGIN_H`  | `0x000C` |  RW | text origin (cell index) high                                                                 |
| `TX_FINE_Y`    | `0x000D` |  RW | fine scroll Y (0..15)                                                                         |
| `TX_FINE_X`    | `0x000E` |  RW | fine scroll X (0..7)                                                                          |
| `TX_ATTR`      | `0x000F` |  RW | default attribute for TX_PORT writes                                                          |
| `TX_CMD`       | `0x0030` |  WO | command bits (see below)                                                                      |
| `TX_PORT`      | `0x0031` |  WO | write ASCII/control codes to terminal                                                         |

### 7.2 Text memory (MMIO)

| Region     | Addr range       |            Size | Meaning               |
| ---------- | ---------------- | --------------: | --------------------- |
| Text cells | `0x2000..0x2F9F` | `0x0FA0` (4000) | 2000 cells × 2 bytes  |
| Reserved   | `0x2FA0..0x2FFF` |        `0x0060` | reserved              |
| Font 8×16  | `0x3000..0x3FFF` |        `0x1000` | 256 glyphs × 16 bytes |

**Cell layout**

* `textRam[cell*2 + 0]` = character code
* `textRam[cell*2 + 1]` = `attr`

**Attribute format**

* low nibble: FG color (0..15) uses palette indices **0..15**
* high nibble: BG color (0..15) uses palette indices **0..15**
* if `TX_CHAR_BLINK=1`, attribute bit7 (0x80) enables blinking char behavior (renderer masks BG to 0..7 and blanks glyph on off phase)

### 7.3 TX_CMD bits

Write any combination (bitmask) to `TX_CMD`:

* `0x01` clear to end of line (from cursor)
* `0x02` clear entire current line
* `0x04` clear screen
* `0x08` scroll up 1 row
* `0x10` home cursor (0,0)

### 7.4 TX_PORT behavior

Writing a byte to `TX_PORT` interprets:

* `0x0A` LF → newline
* `0x0D` CR → cursor X = 0
* `0x08` BS → cursor X-- (if >0)
* `0x09` TAB → next multiple-of-8 column (or newline if past end)
* otherwise → print character at cursor with `TX_ATTR`, advance cursor

---

## 8) Groups (playfields) – 4 compositing layers

Groups are the way you describe **playfields** built from contiguous plane ranges.

* There are **4 groups**
* Each group has **16 bytes** in the group descriptor table.
* Table base: `GROUP_BASE = 0x0400`
* Stride: `0x10`
* Total table size: `0x40`

### 8.1 Group descriptor layout (per group)

For group `g` in 0..3:

* base = `0x0400 + g*0x10`

| Offset | Name          | Size | Meaning                                    |
| -----: | ------------- | ---: | ------------------------------------------ |
|     +0 | `PLANE_START` |   u8 | 0..7                                       |
|     +1 | `PLANE_COUNT` |   u8 | 0..8 (0 disables group)                    |
|     +2 | `PAL_BASE`    |   u8 | palette base added to computed pixel index |
|     +3 | `SCROLL_X_L`  |   u8 | scrollX low (signed16)                     |
|     +4 | `SCROLL_X_H`  |   u8 | scrollX high                               |
|     +5 | `SCROLL_Y`    |   u8 | scrollY (0..255, clamped if wrapY off)     |
|     +6 | `PRIORITY`    |   u8 | lower draws first; higher overlays later   |
|     +7 | `FLAGS`       |   u8 | see below                                  |
|     +8 | `BPL_OFS_L`   |   u8 | base offset inside each plane (14-bit)     |
|     +9 | `BPL_OFS_H`   |   u8 | high bits (mask 0x3F used internally)      |
| +A..+F | reserved      |    6 | currently unused                           |

### 8.2 Group flags

| Bit | Name      | Meaning                                                                             |
| --: | --------- | ----------------------------------------------------------------------------------- |
|   0 | `LORES`   | group uses 320×200 source (40 bytes/row), expanded to 640 output by doubling pixels |
|   1 | `WRAP_X`  | X wraps within row (byte wrap, useful for scrolling playfields)                     |
|   2 | `WRAP_Y`  | Y wraps within 200 lines                                                            |
|   3 | `OPAQUE0` | color index 0 is treated as opaque (otherwise 0 is transparent)                     |

### 8.3 Overlap rule (important)

Groups **must not overlap plane ownership**.

At frame begin:

* groups are sorted by `PRIORITY` (stable)
* if a later (front) group uses planes already claimed by an earlier group, it is **disabled** and `STATUS_CFG_ERR` is set.

This is a *hard* rule: it simplifies both programming and performance.

### 8.4 Scroll semantics

* `scrollX` is signed 16-bit in pixels (fractionless).

    * internally decomposed into:

        * byte offset = `scrollX >> 3`
        * bit shift = `scrollX & 7`
* `scrollY` is byte (0..255). If `WRAP_Y` is off and `scrollY >= 200`, it is clamped to 199 and sets `CFG_ERR`.

### 8.5 Base addressing (`BPL_OFS`)

Each group has a `BPL_OFS` (14-bit) added to row address per plane:

* row base = `(BPL_OFS + sy * bytesPerLine) & 0x3FFF`

This lets you:

* keep multiple buffers inside the 16K plane and “page flip” by changing `BPL_OFS`,
* store multiple bitmaps in-plane and select one.

---

## 9) Per-plane base pointers (MMIO)

In addition to per-group `BPL_OFS`, v4.1 introduces **per-plane base pointers** at:

* `0x0040..0x004F` (2 bytes per plane × 8 planes)

For plane `p`:

* low: `0x0040 + p*2`
* high: `0x0040 + p*2 + 1`
* value is **14-bit** (high masked to 6 bits)

This is applied as an extra add when reading plane bytes:

* final address = `(groupLineBase + planeBase[p]) & 0x3FFF`

Use cases:

* copper-driven bank switching within a frame,
* per-plane “page flip” while keeping group descriptors constant,
* effects like split-screen scrolling without rewriting group table.

Performance note: the renderer auto-detects whether any plane base is non-zero each frame to avoid overhead when unused.

---

## 10) VGA-style planar write modes (VRAM writes)

When CPU writes to **VBANK 0..7** (planes) and write-mode is enabled, the write is transformed into a **planar RMW** across selected planes.

### 10.1 Registers

| Reg             |     Addr | R/W | Meaning                                                 |
| --------------- | -------: | :-: | ------------------------------------------------------- |
| `WM_CTRL`       | `0x0510` |  RW | `bit0 WM_EN`, `bit1 USE_SR`, `bits2..3 ROP`             |
| `WM_PLANE_MASK` | `0x0511` |  RW | bitmask of planes to affect (bit0=plane0 … bit7=plane7) |
| `WM_SETRESET`   | `0x0512` |  RW | 8 bits: per-plane set/reset source bit                  |
| `WM_BITMASK`    | `0x0513` |  RW | 8-bit mask applied to the byte (bit7=leftmost pixel)    |

### 10.2 Behavior

When `WM_EN=1` and CPU writes a byte `d` to a plane address `addr`:

* For each plane `p` where `WM_PLANE_MASK` bit is 1:

    * `old = vram[p][addr]`
    * `src = d` (normal)
      or if `USE_SR=1`: `src = 0xFF` if `WM_SETRESET[p]=1` else `0x00`
    * apply bitmask `m = WM_BITMASK`
    * apply ROP:

ROPs:

* `0`: COPY masked → `out = (old & ~m) | (src & m)`
* `1`: OR masked   → `out = old | (src & m)`
* `2`: AND masked  → `out = old & (src | ~m)`  (keeps old bits where mask=0)
* `3`: XOR masked  → `out = old ^ (src & m)`

Practical use:

* fast 8-pixel spans, masked sprites, line drawing, etc. on an 8-bit CPU.

---

## 11) Sprites

Sprites are a separate overlay system:

* Up to **128 sprites**
* OAM (sprite attribute table) lives in **MMIO 0x1000–0x1FFF** (4KB).
* Sprite patterns come from **VBANK 9..F** (packed 4bpp).
* Supports:

    * enable/disable,
    * hflip/vflip,
    * scaling in 8.8 fixed point,
    * “tilt” (shear-like) effect per row,
    * chaining (linked list),
    * collision marking.

### 11.1 Sprite enable

Global:

* `SPR_CTRL` at `0x0014`: `bit0 SPR_EN`

### 11.2 OAM layout (per sprite)

Each sprite slot is **32 bytes**.

* Sprite `si` base = `0x1000 + si*32`

From the renderer you have these fields actively used:

| OAM byte | Name     | Type | Meaning                                                                                                   |
| -------: | -------- | ---- | --------------------------------------------------------------------------------------------------------- |
|    +0,+1 | X        | s16  | destination X in pixels (source 640 space)                                                                |
|    +2,+3 | Y        | s16  | destination Y in **source** scanlines (0..199 typically)                                                  |
|       +4 | W        | u8   | sprite width in pixels (0 means 256)                                                                      |
|       +5 | H        | u8   | sprite height in pixels (0 means 256)                                                                     |
|    +6,+7 | DATA     | u16  | bits 0..13 = pattern offset (0..0x3FFF); bits14..15 participate in bank select                            |
|       +8 | ATTR     | u8   | bit0 EN, bit1 HFLIP, bit2 VFLIP, bit3 CHAIN, bit4 COLLIDE, bit5 TILT, bits6..7 participate in bank select |
|       +9 | PAL_BASE | u8   | added to 4bpp pixel (0..15) to choose palette entry                                                       |
|      +10 | COL_ID   | u8   | low nibble used as collision id (0 disables collision reporting)                                          |
|      +11 | PRIORITY | u8   | compared against group priorities to pick sprite slot (below/between/above)                               |
|  +12,+13 | XSTEP    | u16  | 8.8 fixed point horizontal step (0 => 1.0) (clamped to `>= 0x0040`)                                       |
|  +14,+15 | YSTEP    | u16  | 8.8 fixed point vertical step (0 => 1.0) (clamped to `>= 0x0040`)                                         |
|      +16 | TILT_DX  | s8   | per-source-row X add if TILT set                                                                          |
|      +17 | LINK     | u8   | next sprite index (0..127) used if CHAIN set                                                              |
|   others | reserved |      | currently ignored by v4.1                                                                                 |

#### Bank selection (important)

Sprite bank is computed as:

* `bankLo = DATA >> 14` (2 bits)
* `bankHi = ATTR >> 6` (2 bits)
* `bank = (bankHi<<2) | bankLo` (0..15)

Then v4.1 clamps:

* if bank < 9 → bank = 9
* if bank > 15 → bank = 15

So, in practice, you can choose **banks 9..F** by setting those top bits appropriately.

### 11.3 Scaling semantics

`XSTEP`/`YSTEP`:

* are interpreted so that **output pixels correspond to stepping through source**.
* `0x0100` = 1.0 (no scaling)
* smaller step = larger output (upscale), but is clamped to **min 0x0040** (max 4× upscale).
* Output width is approximated as:

    * `outWidth = ceil(W*256 / XSTEP)` (capped to `SPR_MAX_OUT_PIX`)

### 11.4 Collision reporting

Collision memory:

* `COL_BASE = 0x0300`, 16 bytes = 128 bits (`COL_HIT_BYTES=16`)
* `COL_CLR = 0x0310` write any value to clear all hits

Rules:

* Sprite participates if:

    * sprite has `ATTR.COLLIDE=1`
    * `COL_ID != 0`
    * and at least one sprite that frame has COLLIDE enabled (optimization)
* Collision is detected when **two sprites draw non-zero pixels at same X on same scanline** and their collision IDs differ.
* When collision occurs, both sprite indices are marked in `colHit[]`.

---

## 12) Sprite priority slots (how sprites layer with groups)

Sprites are not a single layer. They are distributed into **5 slots**:

* Slot 0: below all groups
* Slot 1: between group 0 and group 1 in sorted order
* Slot 2: between group 1 and group 2
* Slot 3: between group 2 and group 3
* Slot 4: above all groups

Assignment:

* collect active group priorities (sorted)
* for each sprite with `ATTR.EN=1`:

    * `slot = number of group priorities <= spritePriority` (clamped 0..4)

This gives an Amiga-like “between playfields” effect without per-pixel priority logic.

---

## 13) Copper (scanline MMIO sequencer)

Copper is a list of MMIO writes triggered by scanline, stored in **tables bank (VBANK=8)**.

### 13.1 Copper registers

| Reg         |     Addr | R/W | Meaning                           |
| ----------- | -------: | :-: | --------------------------------- |
| `COP_CTRL`  | `0x0020` |  RW | `bit0 COP_EN`                     |
| `COP_LEN_L` | `0x0021` |  RW | program length low (bytes)        |
| `COP_LEN_H` | `0x0022` |  RW | program length high               |
| `COP_OFS_L` | `0x0023` |  RW | program offset within VBANK=8 low |
| `COP_OFS_H` | `0x0024` |  RW | offset high                       |

Notes:

* length is rounded down to a multiple of 8 bytes
* max ops: 2048 entries (16KB / 8)

### 13.2 Copper instruction format (8 bytes per entry)

At `VBANK=8`, offset `COP_OFS + i*8`:

| Byte | Field    | Type   | Meaning                                                    |
| ---: | -------- | ------ | ---------------------------------------------------------- |
| 0..1 | `SCAN`   | u16 LE | scanline to trigger (0..399). Only `scan < height` is kept |
| 2..3 | `REG`    | u16 LE | MMIO register offset to write                              |
|    4 | `VAL_LO` | u8     | value low                                                  |
|    5 | `VAL_HI` | u8     | value high (used if WRITE16)                               |
|    6 | `FLAGS`  | u8     | bit0 WRITE16, bit7 END                                     |
|    7 | reserved | u8     | ignored                                                    |

### 13.3 Execution rules

* Copper program is compiled at frame begin if “dirty”.
* It is sorted by scanline (counting sort).
* On scanline `y`, all entries whose `SCAN==y` execute in order.
* If `WRITE16=1`, it writes:

    * `REG = VAL_LO`
    * `REG+1 = VAL_HI`
* Otherwise only single-byte write.

### 13.4 Safety restrictions

Copper writes are restricted to prevent self-modification loops:

* Copper may only write MMIO `0x0000..0x1FFF`
  (text/font are above that)
* Copper cannot write into `COP_CTRL..COP_OFS_H`

Violations set `STATUS_CFG_ERR`.

---

## 14) Blitter

The blitter is **VRAM-only** and works on the plane banks. It is time-based and consumes cycles in `tick()`.

### 14.1 Blitter registers

(Addresses in MMIO)

**Control and base geometry**

| Reg              |            Addr | Meaning                                                                             |
| ---------------- | --------------: | ----------------------------------------------------------------------------------- |
| `BLT_CTRL`       |        `0x0560` | bit0 START, bit1 FILL, bit2 IRQ_EN, bit3 DIR(force back), bit4 TRANS, bit7 BUSY(RO) |
| `BLT_SRC`        | `0x0561/0x0562` | u16 source offset (low/high)                                                        |
| `BLT_DST`        | `0x0563/0x0564` | u16 dest offset                                                                     |
| `BLT_W`          | `0x0565/0x0566` | u16 bytes per row                                                                   |
| `BLT_H`          |        `0x0567` | u8 rows (0 => 1)                                                                    |
| `BLT_SRC_PITCH`  | `0x0568/0x0569` | s16 delta added after each row                                                      |
| `BLT_DST_PITCH`  | `0x056A/0x056B` | s16 delta added after each row                                                      |
| `BLT_FILL`       |        `0x056C` | fill byte or color index (depends on op)                                            |
| `BLT_PLANE_MASK` |        `0x056D` | plane mask (0 => all planes)                                                        |
| `BLT_ROP`        |        `0x056E` | 0 COPY, 1 OR, 2 AND, 3 XOR, 4 RECTFILL, 5 RECTBLIT                                  |
| `BLT_SHIFT`      |        `0x056F` | 0..7 right shift for source alignment                                               |
| `BLT_FIRST_MASK` |        `0x0570` | mask applied to first byte of each row                                              |
| `BLT_LAST_MASK`  |        `0x0571` | mask applied to last byte of each row                                               |

**RECTFILL / RECTBLIT destination rectangle**

| Reg       |            Addr | Meaning                      |
| --------- | --------------: | ---------------------------- |
| `BLT_X`   | `0x0572/0x0573` | u16 pixel X                  |
| `BLT_Y`   | `0x0574/0x0575` | u16 pixel Y                  |
| `BLT_WPX` | `0x0576/0x0577` | u16 width in pixels          |
| `BLT_HPX` |        `0x0578` | u8 height in pixels (0 => 1) |

**RECTBLIT packed source parameters**

| Reg              |            Addr | Meaning                                      |
| ---------------- | --------------: | -------------------------------------------- |
| `BLT_SRCBANK`    |        `0x0579` | packed source bank (9..F, others clamp to 9) |
| `BLT_SRCOFS`     | `0x057A/0x057B` | u16 base offset in packed bank (0..0x3FFF)   |
| `BLT_SX`         | `0x057C/0x057D` | u16 src X in pixels                          |
| `BLT_SY`         | `0x057E/0x057F` | u16 src Y in pixels                          |
| `BLT_PSRC_PITCH` | `0x0580/0x0581` | u16 packed bytes per row                     |
| `BLT_DXSTEP`     | `0x0582/0x0583` | dst step 8.8 (v4.1 currently forces 1.0)     |
| `BLT_DYSTEP`     | `0x0584/0x0585` | dst step 8.8 (forced 1.0)                    |
| `BLT_SXSTEP`     | `0x0586/0x0587` | src step 8.8                                 |
| `BLT_SYSTEP`     | `0x0588/0x0589` | src step 8.8                                 |

### 14.2 Starting a blit

Write `BLT_CTRL` with `START=1`. START is not latched; it triggers `bltStart()`.

Rules:

* If `BUSY=1`, START is ignored.
* Blit runs at a rate of **1 byte per `BLT_CYCLES_PER_BYTE` cycles** (currently 2).

Completion:

* sets `STATUS_BLT`
* if `IRQ_EN=1`, raises IRQ.

### 14.3 COPY/OR/AND/XOR path (byte blit)

* Operates per plane (maskable).
* Optional `SHIFT` (0..7) aligns source by shifting in bits from the “next” byte.
* Optional `FIRST_MASK`/`LAST_MASK` mask edges per row.
* Optional `DIR` auto/backward for overlap safety (“memmove semantics”) if it can prove linear non-wrapping case.

TRANS behavior:

* `TRANS` only changes COPY behavior: destination bits are preserved where source bits are 0 (within mask).

### 14.4 RECTFILL (BLT_ROP=4)

RECTFILL is a pixel-space rectangle fill expanded into planes.

Inputs:

* `BLT_DST` = byte address for pixel (0,0) in each plane (framebuffer base)
* `BLT_DST_PITCH` = bytes per row (not delta) for RECTFILL mode
* `BLT_X/Y/WPX/HPX` = rectangle in pixels
* `BLT_PLANE_MASK` selects which planes are written
* `BLT_FILL` is treated as an **8-bit color index**; each plane gets 0xFF or 0x00 depending on that bit

It computes proper edge masks based on pixel alignment and combines with user FIRST/LAST masks.

### 14.5 RECTBLIT (BLT_ROP=5)

RECTBLIT copies a **packed 4bpp source** (sprite bank) into planar VRAM destination rectangle.

Inputs:

* destination rectangle uses same regs as RECTFILL (`BLT_X/Y/WPX/HPX`, `BLT_DST`, `BLT_DST_PITCH`)
* source is a packed 4bpp image in a sprite bank:

    * `BLT_SRCBANK` 9..F
    * `BLT_SRCOFS` base
    * `BLT_SX/SY` pixel position
    * `BLT_PSRC_PITCH` bytes per row (0 means auto = ceil(width/2))
    * `BLT_SXSTEP/SYSTEP` allow sampling/scaling in source space
* `TRANS` means pixel value 0 is transparent (preserve dest).

v4.1 note:

* destination steps `DXSTEP/DYSTEP` are forced to 1.0 for now (the code clamps and sets `CFG_ERR` if you try other values).

---

## 15) How to program it (practical recipes)

### 15.1 Minimal “boot screen” text

1. Map VideoWindow → MMIO
2. Ensure `CTRL.ENABLE=1` (default)
3. Write attributes and characters using `TX_ATTR` + `TX_PORT`

Example sequence (conceptual):

* `TX_ATTR = 0x1F` (FG=15 white, BG=1 blue)
* write string bytes to `TX_PORT`

### 15.2 Set up a graphics playfield (single group, 8bpp)

Goal: 640×200 graphics in 8bpp using all 8 planes.

1. Map VideoWindow → MMIO
2. Configure group 0:

    * `PLANE_START=0`
    * `PLANE_COUNT=8`
    * `PAL_BASE=0`
    * `PRIORITY=0`
    * `FLAGS = WRAP_X|WRAP_Y` as desired
3. Optionally disable other groups by setting their `PLANE_COUNT=0`.
4. Map VideoWindow → banked mode and write into planes.

Pixel encoding:

* For a pixel color index `c` (0..255):

    * plane p contains bit p of c at that pixel position.

Practical drawing:

* use write modes or blitter to avoid per-pixel bit twiddling in CPU code.

### 15.3 Two playfields split (AUTO_SPLIT)

`REG_AUTO_SPLIT` at `0x0015` configures group0 and group1 with one write:

* group0 = planes 0..(n-1)
* group1 = planes n..7
* both LORES + wrap
* priorities 0 and 1
* PAL_BASE for group1 = 16

This is a fast way to get “foreground/background” planes.

### 15.4 Use planar write mode for fast masked drawing

Typical “draw 8 pixels at once” in planar:

* set `WM_EN=1`
* choose plane mask and bitmask
* write one byte to a plane address → VPU updates all selected planes.

Example: write a solid color index `c` to a full byte (8 pixels):

* `WM_USE_SR=1`
* `WM_SETRESET = c` (bits select planes)
* `WM_BITMASK = 0xFF`
* `WM_PLANE_MASK = 0xFF`
* write any value (ignored under USE_SR) to the plane address

### 15.5 Sprites: put one 16×16 sprite on screen

1. Upload sprite pixels into a sprite bank (VBANK 9..F) in packed 4bpp:

    * 2 pixels per byte: high nibble = left pixel, low nibble = right pixel.
2. In MMIO OAM for sprite 0:

    * set X, Y
    * set W=16, H=16
    * set DATA offset to pattern start
    * set ATTR.EN=1
    * set PAL_BASE to where your 16-color palette lives
    * set XSTEP=YSTEP=0x0100
3. Enable sprites globally via `SPR_CTRL |= 1`.

### 15.6 Copper: palette split on scanline

1. Write copper program into VBANK=8.
2. Set `COP_OFS`, `COP_LEN`.
3. Enable `COP_CTRL |= COP_EN`.

Example entry:

* scanline = 100
* reg = `PAL_BASE + idx*2` (low byte)
* valLo = ...
* flags = WRITE16 if you want to write full RGB565

Use this for:

* sky gradients,
* palette cycling,
* enabling/disabling text mid-frame,
* changing plane base pointers mid-frame.

### 15.7 Blitter: fill a rectangle in 8bpp

Use RECTFILL (BLT_ROP=4):

* set `BLT_DST` to framebuffer base (byte address)
* set `BLT_DST_PITCH` to bytes per row (80 for 640 mode, 40 for LORES if you use that mapping)
* set `BLT_X/Y/WPX/HPX`
* set `BLT_PLANE_MASK=0xFF`
* set `BLT_FILL=colorIndex`
* set `BLT_ROP=4`
* trigger `START`

---

## 16) Error handling and “hardening”

VPU sets `STATUS_CFG_ERR` (W1C) when it detects:

* group plane overlaps or invalid plane ranges,
* illegal scrollY values (when wrapY off),
* copper truncation or illegal register writes,
* sprite slot overflow / bucket overflow protection,
* invalid forced settings (e.g. RECTBLIT dest steps not 1.0).

Recommended driver behavior:

* If `STATUS_CFG_ERR` becomes set, dump diagnostic info:

    * group descriptors
    * copper params
    * blitter params
    * sprite head entries
* Then W1C it after logging.

---

## 17) Appendix: quick register summary (by subsystem)

### Core

* `0x0000 CTRL`
* `0x0001 STATUS`
* `0x0002..0x0003 SCAN`
* `0x0004..0x0005 RASTER_CMP`
* `0x00FF VPU_ID`

### Text

* `0x0006 TX_CTRL`
* `0x0007 TX_CUR_X`
* `0x0008 TX_CUR_Y`
* `0x0009 TX_CUR_START`
* `0x000A TX_CUR_END`
* `0x000B..0x000C TX_ORIGIN`
* `0x000D TX_FINE_Y`
* `0x000E TX_FINE_X`
* `0x000F TX_ATTR`
* `0x0030 TX_CMD`
* `0x0031 TX_PORT`
* `0x2000..0x2F9F text RAM`
* `0x3000..0x3FFF font RAM`

### Palette

* `0x0100..0x02FF` palette RGB565 (256×2)

### Groups

* `0x0400..0x043F` group descriptors (4×16)

### Per-plane base pointers

* `0x0040..0x004F` (8× u16 14-bit)

### Sprites / OAM

* `0x0014 SPR_CTRL`
* `0x1000..0x1FFF` OAM (128×32)

### Collision

* `0x0300..0x030F` hit bits
* `0x0310` clear

### Copper

* `0x0020 COP_CTRL`
* `0x0021..0x0022 COP_LEN`
* `0x0023..0x0024 COP_OFS`

### Write modes

* `0x0510 WM_CTRL`
* `0x0511 WM_PLANE_MASK`
* `0x0512 WM_SETRESET`
* `0x0513 WM_BITMASK`

### Blitter

* `0x0560..0x0571` base blitter + masks
* `0x0572..0x0578` RECT destination
* `0x0579..0x0589` RECTBLIT packed source + steps

---

Below is a **developer’s guide** you can drop into your repo as `docs/vpu_v4_1_developers_guide.md` (or similar). It’s written for **ROM/game/OS** authors *and* for emulator/device developers.

---

# VPU v4.1 Developer’s Guide

## 1) What VPU v4.1 is

VPU v4.1 is a **planar, scanline-based video processor** designed for an 8-bit data bus CPU (R8 family) that still wants:

* **fast filled graphics** (planar write modes + blitter),
* **sprites with scaling** (Lynx-ish),
* **copper** for raster tricks,
* **always-on text console** for boot/debug.

The defining idea: the CPU sees **one 16KB “VideoWindow”** at a time. That window can expose:

* **MMIO** (registers + text/font + OAM + palette), or
* **banked video memory** (VRAM planes, sprite banks, tables bank).

---

## 2) Output model and timing

### 2.1 Output resolution and scan mapping

* Output framebuffer: **640×400** ARGB (host side).
* Source graphics space: **640×200** effective for graphics.
* VPU renders **each output scanline `yOut` from source scanline `ySrc = yOut >> 1`** (so 200 source lines doubled to 400).

This means:

* Any graphics plane, sprites, groups operate in **200-line space**.
* The text layer is **80×25 with 16 scanlines per row = 400 scanlines**, so text is naturally aligned to the 640×400 output.

### 2.2 Key scanline counters and events

Internally:

* `scanline` counts scanlines across the full frame timing (including vblank region).
* At `scanline == 0`, `beginFrame()` latches per-frame decisions and rebuilds sprite/group state.
* At `scanline == vblankStart`, VPU:

    * sets `STATUS_VBLANK` and `STATUS_FRAME`,
    * queues/publishes the completed frame,
    * optionally raises an IRQ (if enabled).

### 2.3 Raster IRQ vs “live rendering”

If `CTRL_RASTER_IRQ_EN` is enabled, the VPU may render scanlines “live” as time progresses (instead of rendering the whole frame at vblank). This exists to support raster/copper-like effects with predictable ordering.

---

## 3) Memory map / VideoWindow model

### 3.1 The 16KB window

The CPU maps a 16KB region as **VideoWindow**. A separate system register (`WIN_MMIO`) selects whether that window points to:

* **MMIO view** (register space, palette, OAM, text/font), or
* **banked view** (VBANK selects which 16KB bank is visible).

VPU v4.1 assumes this contract:

* **WIN_MMIO=1** → CPU reads/writes VPU registers and MMIO memory.
* **WIN_MMIO=0** → CPU reads/writes the selected 16KB VBANK.

> This guide documents only the VPU side. Your system’s memory-mapper defines the actual CPU address where the VideoWindow appears (e.g. `0xC000..0xFFFF`).

### 3.2 VBANK map (WIN_MMIO=0)

| VBANK | Meaning              | Backing                 | Format                                      |
| ----: | -------------------- | ----------------------- | ------------------------------------------- |
|  0..7 | VRAM planes 0..7     | `vram[plane*16K + ofs]` | **1bpp per plane**                          |
|     8 | Tables bank          | `tblBank[ofs]`          | packed bytes (copper program, future lists) |
|  9..F | Sprite pattern banks | `sprBanks[bank-9][ofs]` | **packed 4bpp** (2 pixels per byte)         |

All banks are exactly **16KB**; offset always wraps with `ofs & 0x3FFF`.

---

## 4) Rendering pipeline (high level)

Each output scanline `yOut`:

1. **Copper** (optional): if enabled, apply any copper writes scheduled for this scanline.
2. **Underlay** (graphics):

    * background color index 0,
    * sprite slot 0 (below groups),
    * group 0..N in priority order, with sprite slots between groups.
3. **Text overlay** (optional): 80×25 terminal layer drawn on top (opaque or transparent).

Text is always “available” because the text RAM/font are in MMIO.

---

## 5) Core registers (MMIO)

MMIO offsets are **within the 16KB MMIO view** (i.e. `offset & 0x3FFF`).

### 5.1 Version / ID

| Reg      |     Addr | R/W | Meaning                     |
| -------- | -------: | :-: | --------------------------- |
| `VPU_ID` | `0x00FF` |  RO | Returns `0x41` for **v4.1** |

Use this in ROM to detect you’re running on v4+.

---

### 5.2 Control & Status

| Reg            |     Addr |    R/W   | Bits / Meaning                                                                                 |
| -------------- | -------: | :------: | ---------------------------------------------------------------------------------------------- |
| `CTRL`         | `0x0000` |    RW    | `bit0 ENABLE` (1=run), `bit1 VBL_IRQ_EN`, `bit2 RASTER_IRQ_EN`, `bit3 GFX_DIS`                 |
| `STATUS`       | `0x0001` | RO + W1C | `bit0 VBLANK`, `bit1 FRAME` (W1C), `bit2 RASTER` (W1C), `bit3 CFG_ERR` (W1C), `bit4 BLT` (W1C) |
| `SCAN_L`       | `0x0002` |    RO    | current scanline low byte                                                                      |
| `SCAN_H`       | `0x0003` |    RO    | current scanline high byte                                                                     |
| `RASTER_CMP_L` | `0x0004` |    RW    | compare scanline low byte                                                                      |
| `RASTER_CMP_H` | `0x0005` |    RW    | compare scanline high byte                                                                     |

**Behavior notes**

* When `CTRL.ENABLE=0`, VPU does not advance timing or render.
* `GFX_DIS=1` forces graphics underlay to solid palette index 0 (text may still draw).
* `STATUS` bits 1..4 are **write-one-to-clear**. Write a mask containing the bits you want to clear.

---

## 6) Palette (MMIO)

Palette is **256 entries of RGB565** stored in MMIO:

* Base: `PAL_BASE = 0x0100`
* Size: `PAL_SIZE = 0x0200` bytes (512)

Each entry is **little-endian RGB565**:

* low byte at `PAL_BASE + idx*2`
* high byte at `PAL_BASE + idx*2 + 1`

Example:

* entry 5:

    * low at `0x010A`
    * high at `0x010B`

Whenever you update palette entries, the renderer uses them immediately. Text fast-path caches are invalidated when palette changes.

---

## 7) Text console (MMIO)

Text is a built-in terminal layer:

* **80×25**
* **8×16 font**
* Total: 2000 cells
* Each cell = 2 bytes:

    * `char`
    * `attr`

### 7.1 Text control registers

| Reg            |     Addr | R/W | Meaning                                                                                       |
| -------------- | -------: | :-: | --------------------------------------------------------------------------------------------- |
| `TX_CTRL`      | `0x0006` |  RW | `bit0 TX_EN`, `bit1 CURSOR_EN`, `bit2 TRANSPARENT_BG`, `bit3 CURSOR_BLINK`, `bit4 CHAR_BLINK` |
| `TX_CUR_X`     | `0x0007` |  RW | cursor X (0..79)                                                                              |
| `TX_CUR_Y`     | `0x0008` |  RW | cursor Y (0..24)                                                                              |
| `TX_CUR_START` | `0x0009` |  RW | cursor start scanline within cell row (0..15)                                                 |
| `TX_CUR_END`   | `0x000A` |  RW | cursor end scanline (0..15)                                                                   |
| `TX_ORIGIN_L`  | `0x000B` |  RW | text origin (cell index) low                                                                  |
| `TX_ORIGIN_H`  | `0x000C` |  RW | text origin (cell index) high                                                                 |
| `TX_FINE_Y`    | `0x000D` |  RW | fine scroll Y (0..15)                                                                         |
| `TX_FINE_X`    | `0x000E` |  RW | fine scroll X (0..7)                                                                          |
| `TX_ATTR`      | `0x000F` |  RW | default attribute for TX_PORT writes                                                          |
| `TX_CMD`       | `0x0030` |  WO | command bits (see below)                                                                      |
| `TX_PORT`      | `0x0031` |  WO | write ASCII/control codes to terminal                                                         |

### 7.2 Text memory (MMIO)

| Region     | Addr range       |            Size | Meaning               |
| ---------- | ---------------- | --------------: | --------------------- |
| Text cells | `0x2000..0x2F9F` | `0x0FA0` (4000) | 2000 cells × 2 bytes  |
| Reserved   | `0x2FA0..0x2FFF` |        `0x0060` | reserved              |
| Font 8×16  | `0x3000..0x3FFF` |        `0x1000` | 256 glyphs × 16 bytes |

**Cell layout**

* `textRam[cell*2 + 0]` = character code
* `textRam[cell*2 + 1]` = `attr`

**Attribute format**

* low nibble: FG color (0..15) uses palette indices **0..15**
* high nibble: BG color (0..15) uses palette indices **0..15**
* if `TX_CHAR_BLINK=1`, attribute bit7 (0x80) enables blinking char behavior (renderer masks BG to 0..7 and blanks glyph on off phase)

### 7.3 TX_CMD bits

Write any combination (bitmask) to `TX_CMD`:

* `0x01` clear to end of line (from cursor)
* `0x02` clear entire current line
* `0x04` clear screen
* `0x08` scroll up 1 row
* `0x10` home cursor (0,0)

### 7.4 TX_PORT behavior

Writing a byte to `TX_PORT` interprets:

* `0x0A` LF → newline
* `0x0D` CR → cursor X = 0
* `0x08` BS → cursor X-- (if >0)
* `0x09` TAB → next multiple-of-8 column (or newline if past end)
* otherwise → print character at cursor with `TX_ATTR`, advance cursor

---

## 8) Groups (playfields) – 4 compositing layers

Groups are the way you describe **playfields** built from contiguous plane ranges.

* There are **4 groups**
* Each group has **16 bytes** in the group descriptor table.
* Table base: `GROUP_BASE = 0x0400`
* Stride: `0x10`
* Total table size: `0x40`

### 8.1 Group descriptor layout (per group)

For group `g` in 0..3:

* base = `0x0400 + g*0x10`

| Offset | Name          | Size | Meaning                                    |
| -----: | ------------- | ---: | ------------------------------------------ |
|     +0 | `PLANE_START` |   u8 | 0..7                                       |
|     +1 | `PLANE_COUNT` |   u8 | 0..8 (0 disables group)                    |
|     +2 | `PAL_BASE`    |   u8 | palette base added to computed pixel index |
|     +3 | `SCROLL_X_L`  |   u8 | scrollX low (signed16)                     |
|     +4 | `SCROLL_X_H`  |   u8 | scrollX high                               |
|     +5 | `SCROLL_Y`    |   u8 | scrollY (0..255, clamped if wrapY off)     |
|     +6 | `PRIORITY`    |   u8 | lower draws first; higher overlays later   |
|     +7 | `FLAGS`       |   u8 | see below                                  |
|     +8 | `BPL_OFS_L`   |   u8 | base offset inside each plane (14-bit)     |
|     +9 | `BPL_OFS_H`   |   u8 | high bits (mask 0x3F used internally)      |
| +A..+F | reserved      |    6 | currently unused                           |

### 8.2 Group flags

| Bit | Name      | Meaning                                                                             |
| --: | --------- | ----------------------------------------------------------------------------------- |
|   0 | `LORES`   | group uses 320×200 source (40 bytes/row), expanded to 640 output by doubling pixels |
|   1 | `WRAP_X`  | X wraps within row (byte wrap, useful for scrolling playfields)                     |
|   2 | `WRAP_Y`  | Y wraps within 200 lines                                                            |
|   3 | `OPAQUE0` | color index 0 is treated as opaque (otherwise 0 is transparent)                     |

### 8.3 Overlap rule (important)

Groups **must not overlap plane ownership**.

At frame begin:

* groups are sorted by `PRIORITY` (stable)
* if a later (front) group uses planes already claimed by an earlier group, it is **disabled** and `STATUS_CFG_ERR` is set.

This is a *hard* rule: it simplifies both programming and performance.

### 8.4 Scroll semantics

* `scrollX` is signed 16-bit in pixels (fractionless).

    * internally decomposed into:

        * byte offset = `scrollX >> 3`
        * bit shift = `scrollX & 7`
* `scrollY` is byte (0..255). If `WRAP_Y` is off and `scrollY >= 200`, it is clamped to 199 and sets `CFG_ERR`.

### 8.5 Base addressing (`BPL_OFS`)

Each group has a `BPL_OFS` (14-bit) added to row address per plane:

* row base = `(BPL_OFS + sy * bytesPerLine) & 0x3FFF`

This lets you:

* keep multiple buffers inside the 16K plane and “page flip” by changing `BPL_OFS`,
* store multiple bitmaps in-plane and select one.

---

## 9) Per-plane base pointers (MMIO)

In addition to per-group `BPL_OFS`, v4.1 introduces **per-plane base pointers** at:

* `0x0040..0x004F` (2 bytes per plane × 8 planes)

For plane `p`:

* low: `0x0040 + p*2`
* high: `0x0040 + p*2 + 1`
* value is **14-bit** (high masked to 6 bits)

This is applied as an extra add when reading plane bytes:

* final address = `(groupLineBase + planeBase[p]) & 0x3FFF`

Use cases:

* copper-driven bank switching within a frame,
* per-plane “page flip” while keeping group descriptors constant,
* effects like split-screen scrolling without rewriting group table.

Performance note: the renderer auto-detects whether any plane base is non-zero each frame to avoid overhead when unused.

---

## 10) VGA-style planar write modes (VRAM writes)

When CPU writes to **VBANK 0..7** (planes) and write-mode is enabled, the write is transformed into a **planar RMW** across selected planes.

### 10.1 Registers

| Reg             |     Addr | R/W | Meaning                                                 |
| --------------- | -------: | :-: | ------------------------------------------------------- |
| `WM_CTRL`       | `0x0510` |  RW | `bit0 WM_EN`, `bit1 USE_SR`, `bits2..3 ROP`             |
| `WM_PLANE_MASK` | `0x0511` |  RW | bitmask of planes to affect (bit0=plane0 … bit7=plane7) |
| `WM_SETRESET`   | `0x0512` |  RW | 8 bits: per-plane set/reset source bit                  |
| `WM_BITMASK`    | `0x0513` |  RW | 8-bit mask applied to the byte (bit7=leftmost pixel)    |

### 10.2 Behavior

When `WM_EN=1` and CPU writes a byte `d` to a plane address `addr`:

* For each plane `p` where `WM_PLANE_MASK` bit is 1:

    * `old = vram[p][addr]`
    * `src = d` (normal)
      or if `USE_SR=1`: `src = 0xFF` if `WM_SETRESET[p]=1` else `0x00`
    * apply bitmask `m = WM_BITMASK`
    * apply ROP:

ROPs:

* `0`: COPY masked → `out = (old & ~m) | (src & m)`
* `1`: OR masked   → `out = old | (src & m)`
* `2`: AND masked  → `out = old & (src | ~m)`  (keeps old bits where mask=0)
* `3`: XOR masked  → `out = old ^ (src & m)`

Practical use:

* fast 8-pixel spans, masked sprites, line drawing, etc. on an 8-bit CPU.

---

## 11) Sprites

Sprites are a separate overlay system:

* Up to **128 sprites**
* OAM (sprite attribute table) lives in **MMIO 0x1000–0x1FFF** (4KB).
* Sprite patterns come from **VBANK 9..F** (packed 4bpp).
* Supports:

    * enable/disable,
    * hflip/vflip,
    * scaling in 8.8 fixed point,
    * “tilt” (shear-like) effect per row,
    * chaining (linked list),
    * collision marking.

### 11.1 Sprite enable

Global:

* `SPR_CTRL` at `0x0014`: `bit0 SPR_EN`

### 11.2 OAM layout (per sprite)

Each sprite slot is **32 bytes**.

* Sprite `si` base = `0x1000 + si*32`

From the renderer you have these fields actively used:

| OAM byte | Name     | Type | Meaning                                                                                                   |
| -------: | -------- | ---- | --------------------------------------------------------------------------------------------------------- |
|    +0,+1 | X        | s16  | destination X in pixels (source 640 space)                                                                |
|    +2,+3 | Y        | s16  | destination Y in **source** scanlines (0..199 typically)                                                  |
|       +4 | W        | u8   | sprite width in pixels (0 means 256)                                                                      |
|       +5 | H        | u8   | sprite height in pixels (0 means 256)                                                                     |
|    +6,+7 | DATA     | u16  | bits 0..13 = pattern offset (0..0x3FFF); bits14..15 participate in bank select                            |
|       +8 | ATTR     | u8   | bit0 EN, bit1 HFLIP, bit2 VFLIP, bit3 CHAIN, bit4 COLLIDE, bit5 TILT, bits6..7 participate in bank select |
|       +9 | PAL_BASE | u8   | added to 4bpp pixel (0..15) to choose palette entry                                                       |
|      +10 | COL_ID   | u8   | low nibble used as collision id (0 disables collision reporting)                                          |
|      +11 | PRIORITY | u8   | compared against group priorities to pick sprite slot (below/between/above)                               |
|  +12,+13 | XSTEP    | u16  | 8.8 fixed point horizontal step (0 => 1.0) (clamped to `>= 0x0040`)                                       |
|  +14,+15 | YSTEP    | u16  | 8.8 fixed point vertical step (0 => 1.0) (clamped to `>= 0x0040`)                                         |
|      +16 | TILT_DX  | s8   | per-source-row X add if TILT set                                                                          |
|      +17 | LINK     | u8   | next sprite index (0..127) used if CHAIN set                                                              |
|   others | reserved |      | currently ignored by v4.1                                                                                 |

#### Bank selection (important)

Sprite bank is computed as:

* `bankLo = DATA >> 14` (2 bits)
* `bankHi = ATTR >> 6` (2 bits)
* `bank = (bankHi<<2) | bankLo` (0..15)

Then v4.1 clamps:

* if bank < 9 → bank = 9
* if bank > 15 → bank = 15

So, in practice, you can choose **banks 9..F** by setting those top bits appropriately.

### 11.3 Scaling semantics

`XSTEP`/`YSTEP`:

* are interpreted so that **output pixels correspond to stepping through source**.
* `0x0100` = 1.0 (no scaling)
* smaller step = larger output (upscale), but is clamped to **min 0x0040** (max 4× upscale).
* Output width is approximated as:

    * `outWidth = ceil(W*256 / XSTEP)` (capped to `SPR_MAX_OUT_PIX`)

### 11.4 Collision reporting

Collision memory:

* `COL_BASE = 0x0300`, 16 bytes = 128 bits (`COL_HIT_BYTES=16`)
* `COL_CLR = 0x0310` write any value to clear all hits

Rules:

* Sprite participates if:

    * sprite has `ATTR.COLLIDE=1`
    * `COL_ID != 0`
    * and at least one sprite that frame has COLLIDE enabled (optimization)
* Collision is detected when **two sprites draw non-zero pixels at same X on same scanline** and their collision IDs differ.
* When collision occurs, both sprite indices are marked in `colHit[]`.

---

## 12) Sprite priority slots (how sprites layer with groups)

Sprites are not a single layer. They are distributed into **5 slots**:

* Slot 0: below all groups
* Slot 1: between group 0 and group 1 in sorted order
* Slot 2: between group 1 and group 2
* Slot 3: between group 2 and group 3
* Slot 4: above all groups

Assignment:

* collect active group priorities (sorted)
* for each sprite with `ATTR.EN=1`:

    * `slot = number of group priorities <= spritePriority` (clamped 0..4)

This gives an Amiga-like “between playfields” effect without per-pixel priority logic.

---

## 13) Copper (scanline MMIO sequencer)

Copper is a list of MMIO writes triggered by scanline, stored in **tables bank (VBANK=8)**.

### 13.1 Copper registers

| Reg         |     Addr | R/W | Meaning                           |
| ----------- | -------: | :-: | --------------------------------- |
| `COP_CTRL`  | `0x0020` |  RW | `bit0 COP_EN`                     |
| `COP_LEN_L` | `0x0021` |  RW | program length low (bytes)        |
| `COP_LEN_H` | `0x0022` |  RW | program length high               |
| `COP_OFS_L` | `0x0023` |  RW | program offset within VBANK=8 low |
| `COP_OFS_H` | `0x0024` |  RW | offset high                       |

Notes:

* length is rounded down to a multiple of 8 bytes
* max ops: 2048 entries (16KB / 8)

### 13.2 Copper instruction format (8 bytes per entry)

At `VBANK=8`, offset `COP_OFS + i*8`:

| Byte | Field    | Type   | Meaning                                                    |
| ---: | -------- | ------ | ---------------------------------------------------------- |
| 0..1 | `SCAN`   | u16 LE | scanline to trigger (0..399). Only `scan < height` is kept |
| 2..3 | `REG`    | u16 LE | MMIO register offset to write                              |
|    4 | `VAL_LO` | u8     | value low                                                  |
|    5 | `VAL_HI` | u8     | value high (used if WRITE16)                               |
|    6 | `FLAGS`  | u8     | bit0 WRITE16, bit7 END                                     |
|    7 | reserved | u8     | ignored                                                    |

### 13.3 Execution rules

* Copper program is compiled at frame begin if “dirty”.
* It is sorted by scanline (counting sort).
* On scanline `y`, all entries whose `SCAN==y` execute in order.
* If `WRITE16=1`, it writes:

    * `REG = VAL_LO`
    * `REG+1 = VAL_HI`
* Otherwise only single-byte write.

### 13.4 Safety restrictions

Copper writes are restricted to prevent self-modification loops:

* Copper may only write MMIO `0x0000..0x1FFF`
  (text/font are above that)
* Copper cannot write into `COP_CTRL..COP_OFS_H`

Violations set `STATUS_CFG_ERR`.

---

## 14) Blitter

The blitter is **VRAM-only** and works on the plane banks. It is time-based and consumes cycles in `tick()`.

### 14.1 Blitter registers

(Addresses in MMIO)

**Control and base geometry**

| Reg              |            Addr | Meaning                                                                             |
| ---------------- | --------------: | ----------------------------------------------------------------------------------- |
| `BLT_CTRL`       |        `0x0560` | bit0 START, bit1 FILL, bit2 IRQ_EN, bit3 DIR(force back), bit4 TRANS, bit7 BUSY(RO) |
| `BLT_SRC`        | `0x0561/0x0562` | u16 source offset (low/high)                                                        |
| `BLT_DST`        | `0x0563/0x0564` | u16 dest offset                                                                     |
| `BLT_W`          | `0x0565/0x0566` | u16 bytes per row                                                                   |
| `BLT_H`          |        `0x0567` | u8 rows (0 => 1)                                                                    |
| `BLT_SRC_PITCH`  | `0x0568/0x0569` | s16 delta added after each row                                                      |
| `BLT_DST_PITCH`  | `0x056A/0x056B` | s16 delta added after each row                                                      |
| `BLT_FILL`       |        `0x056C` | fill byte or color index (depends on op)                                            |
| `BLT_PLANE_MASK` |        `0x056D` | plane mask (0 => all planes)                                                        |
| `BLT_ROP`        |        `0x056E` | 0 COPY, 1 OR, 2 AND, 3 XOR, 4 RECTFILL, 5 RECTBLIT                                  |
| `BLT_SHIFT`      |        `0x056F` | 0..7 right shift for source alignment                                               |
| `BLT_FIRST_MASK` |        `0x0570` | mask applied to first byte of each row                                              |
| `BLT_LAST_MASK`  |        `0x0571` | mask applied to last byte of each row                                               |

**RECTFILL / RECTBLIT destination rectangle**

| Reg       |            Addr | Meaning                      |
| --------- | --------------: | ---------------------------- |
| `BLT_X`   | `0x0572/0x0573` | u16 pixel X                  |
| `BLT_Y`   | `0x0574/0x0575` | u16 pixel Y                  |
| `BLT_WPX` | `0x0576/0x0577` | u16 width in pixels          |
| `BLT_HPX` |        `0x0578` | u8 height in pixels (0 => 1) |

**RECTBLIT packed source parameters**

| Reg              |            Addr | Meaning                                      |
| ---------------- | --------------: | -------------------------------------------- |
| `BLT_SRCBANK`    |        `0x0579` | packed source bank (9..F, others clamp to 9) |
| `BLT_SRCOFS`     | `0x057A/0x057B` | u16 base offset in packed bank (0..0x3FFF)   |
| `BLT_SX`         | `0x057C/0x057D` | u16 src X in pixels                          |
| `BLT_SY`         | `0x057E/0x057F` | u16 src Y in pixels                          |
| `BLT_PSRC_PITCH` | `0x0580/0x0581` | u16 packed bytes per row                     |
| `BLT_DXSTEP`     | `0x0582/0x0583` | dst step 8.8 (v4.1 currently forces 1.0)     |
| `BLT_DYSTEP`     | `0x0584/0x0585` | dst step 8.8 (forced 1.0)                    |
| `BLT_SXSTEP`     | `0x0586/0x0587` | src step 8.8                                 |
| `BLT_SYSTEP`     | `0x0588/0x0589` | src step 8.8                                 |

### 14.2 Starting a blit

Write `BLT_CTRL` with `START=1`. START is not latched; it triggers `bltStart()`.

Rules:

* If `BUSY=1`, START is ignored.
* Blit runs at a rate of **1 byte per `BLT_CYCLES_PER_BYTE` cycles** (currently 2).

Completion:

* sets `STATUS_BLT`
* if `IRQ_EN=1`, raises IRQ.

### 14.3 COPY/OR/AND/XOR path (byte blit)

* Operates per plane (maskable).
* Optional `SHIFT` (0..7) aligns source by shifting in bits from the “next” byte.
* Optional `FIRST_MASK`/`LAST_MASK` mask edges per row.
* Optional `DIR` auto/backward for overlap safety (“memmove semantics”) if it can prove linear non-wrapping case.

TRANS behavior:

* `TRANS` only changes COPY behavior: destination bits are preserved where source bits are 0 (within mask).

### 14.4 RECTFILL (BLT_ROP=4)

RECTFILL is a pixel-space rectangle fill expanded into planes.

Inputs:

* `BLT_DST` = byte address for pixel (0,0) in each plane (framebuffer base)
* `BLT_DST_PITCH` = bytes per row (not delta) for RECTFILL mode
* `BLT_X/Y/WPX/HPX` = rectangle in pixels
* `BLT_PLANE_MASK` selects which planes are written
* `BLT_FILL` is treated as an **8-bit color index**; each plane gets 0xFF or 0x00 depending on that bit

It computes proper edge masks based on pixel alignment and combines with user FIRST/LAST masks.

### 14.5 RECTBLIT (BLT_ROP=5)

RECTBLIT copies a **packed 4bpp source** (sprite bank) into planar VRAM destination rectangle.

Inputs:

* destination rectangle uses same regs as RECTFILL (`BLT_X/Y/WPX/HPX`, `BLT_DST`, `BLT_DST_PITCH`)
* source is a packed 4bpp image in a sprite bank:

    * `BLT_SRCBANK` 9..F
    * `BLT_SRCOFS` base
    * `BLT_SX/SY` pixel position
    * `BLT_PSRC_PITCH` bytes per row (0 means auto = ceil(width/2))
    * `BLT_SXSTEP/SYSTEP` allow sampling/scaling in source space
* `TRANS` means pixel value 0 is transparent (preserve dest).

v4.1 note:

* destination steps `DXSTEP/DYSTEP` are forced to 1.0 for now (the code clamps and sets `CFG_ERR` if you try other values).

---

## 15) How to program it (practical recipes)

### 15.1 Minimal “boot screen” text

1. Map VideoWindow → MMIO
2. Ensure `CTRL.ENABLE=1` (default)
3. Write attributes and characters using `TX_ATTR` + `TX_PORT`

Example sequence (conceptual):

* `TX_ATTR = 0x1F` (FG=15 white, BG=1 blue)
* write string bytes to `TX_PORT`

### 15.2 Set up a graphics playfield (single group, 8bpp)

Goal: 640×200 graphics in 8bpp using all 8 planes.

1. Map VideoWindow → MMIO
2. Configure group 0:

    * `PLANE_START=0`
    * `PLANE_COUNT=8`
    * `PAL_BASE=0`
    * `PRIORITY=0`
    * `FLAGS = WRAP_X|WRAP_Y` as desired
3. Optionally disable other groups by setting their `PLANE_COUNT=0`.
4. Map VideoWindow → banked mode and write into planes.

Pixel encoding:

* For a pixel color index `c` (0..255):

    * plane p contains bit p of c at that pixel position.

Practical drawing:

* use write modes or blitter to avoid per-pixel bit twiddling in CPU code.

### 15.3 Two playfields split (AUTO_SPLIT)

`REG_AUTO_SPLIT` at `0x0015` configures group0 and group1 with one write:

* group0 = planes 0..(n-1)
* group1 = planes n..7
* both LORES + wrap
* priorities 0 and 1
* PAL_BASE for group1 = 16

This is a fast way to get “foreground/background” planes.

### 15.4 Use planar write mode for fast masked drawing

Typical “draw 8 pixels at once” in planar:

* set `WM_EN=1`
* choose plane mask and bitmask
* write one byte to a plane address → VPU updates all selected planes.

Example: write a solid color index `c` to a full byte (8 pixels):

* `WM_USE_SR=1`
* `WM_SETRESET = c` (bits select planes)
* `WM_BITMASK = 0xFF`
* `WM_PLANE_MASK = 0xFF`
* write any value (ignored under USE_SR) to the plane address

### 15.5 Sprites: put one 16×16 sprite on screen

1. Upload sprite pixels into a sprite bank (VBANK 9..F) in packed 4bpp:

    * 2 pixels per byte: high nibble = left pixel, low nibble = right pixel.
2. In MMIO OAM for sprite 0:

    * set X, Y
    * set W=16, H=16
    * set DATA offset to pattern start
    * set ATTR.EN=1
    * set PAL_BASE to where your 16-color palette lives
    * set XSTEP=YSTEP=0x0100
3. Enable sprites globally via `SPR_CTRL |= 1`.

### 15.6 Copper: palette split on scanline

1. Write copper program into VBANK=8.
2. Set `COP_OFS`, `COP_LEN`.
3. Enable `COP_CTRL |= COP_EN`.

Example entry:

* scanline = 100
* reg = `PAL_BASE + idx*2` (low byte)
* valLo = ...
* flags = WRITE16 if you want to write full RGB565

Use this for:

* sky gradients,
* palette cycling,
* enabling/disabling text mid-frame,
* changing plane base pointers mid-frame.

### 15.7 Blitter: fill a rectangle in 8bpp

Use RECTFILL (BLT_ROP=4):

* set `BLT_DST` to framebuffer base (byte address)
* set `BLT_DST_PITCH` to bytes per row (80 for 640 mode, 40 for LORES if you use that mapping)
* set `BLT_X/Y/WPX/HPX`
* set `BLT_PLANE_MASK=0xFF`
* set `BLT_FILL=colorIndex`
* set `BLT_ROP=4`
* trigger `START`

---

## 16) Error handling and “hardening”

VPU sets `STATUS_CFG_ERR` (W1C) when it detects:

* group plane overlaps or invalid plane ranges,
* illegal scrollY values (when wrapY off),
* copper truncation or illegal register writes,
* sprite slot overflow / bucket overflow protection,
* invalid forced settings (e.g. RECTBLIT dest steps not 1.0).

Recommended driver behavior:

* If `STATUS_CFG_ERR` becomes set, dump diagnostic info:

    * group descriptors
    * copper params
    * blitter params
    * sprite head entries
* Then W1C it after logging.

---

## 17) Appendix: quick register summary (by subsystem)

### Core

* `0x0000 CTRL`
* `0x0001 STATUS`
* `0x0002..0x0003 SCAN`
* `0x0004..0x0005 RASTER_CMP`
* `0x00FF VPU_ID`

### Text

* `0x0006 TX_CTRL`
* `0x0007 TX_CUR_X`
* `0x0008 TX_CUR_Y`
* `0x0009 TX_CUR_START`
* `0x000A TX_CUR_END`
* `0x000B..0x000C TX_ORIGIN`
* `0x000D TX_FINE_Y`
* `0x000E TX_FINE_X`
* `0x000F TX_ATTR`
* `0x0030 TX_CMD`
* `0x0031 TX_PORT`
* `0x2000..0x2F9F text RAM`
* `0x3000..0x3FFF font RAM`

### Palette

* `0x0100..0x02FF` palette RGB565 (256×2)

### Groups

* `0x0400..0x043F` group descriptors (4×16)

### Per-plane base pointers

* `0x0040..0x004F` (8× u16 14-bit)

### Sprites / OAM

* `0x0014 SPR_CTRL`
* `0x1000..0x1FFF` OAM (128×32)

### Collision

* `0x0300..0x030F` hit bits
* `0x0310` clear

### Copper

* `0x0020 COP_CTRL`
* `0x0021..0x0022 COP_LEN`
* `0x0023..0x0024 COP_OFS`

### Write modes

* `0x0510 WM_CTRL`
* `0x0511 WM_PLANE_MASK`
* `0x0512 WM_SETRESET`
* `0x0513 WM_BITMASK`

### Blitter

* `0x0560..0x0571` base blitter + masks
* `0x0572..0x0578` RECT destination
* `0x0579..0x0589` RECTBLIT packed source + steps

---

## Common constants (edit if your map differs)

```asm
.arch r816

; --- system / mapper ---
VIDWIN      = 0xBFF0      ; you used this already (WIN_MMIO bit)
VIDBANK     = 0xBFF1      ; (ASSUMED) select 0x0..0xF when WIN_MMIO=0

VPU         = 0xC000      ; MMIO base when WIN_MMIO=1

; --- VPU core regs (MMIO offsets) ---
REG_CTRL    = VPU + 0x0000
REG_STATUS  = VPU + 0x0001

REG_TX_CTRL = VPU + 0x0006
REG_SPR_CTRL= VPU + 0x0014

REG_COP_CTRL= VPU + 0x0020
REG_COP_LENL= VPU + 0x0021
REG_COP_LENH= VPU + 0x0022
REG_COP_OFSL= VPU + 0x0023
REG_COP_OFSH= VPU + 0x0024

PAL_BASE    = VPU + 0x0100
GROUP_BASE  = VPU + 0x0400
OAM_BASE    = VPU + 0x1000

COL_CLR     = VPU + 0x0310

; --- Blitter regs (MMIO offsets) ---
BLT_CTRL    = VPU + 0x0560
BLT_SRC_L   = VPU + 0x0561
BLT_SRC_H   = VPU + 0x0562
BLT_DST_L   = VPU + 0x0563
BLT_DST_H   = VPU + 0x0564
BLT_W_L     = VPU + 0x0565
BLT_W_H     = VPU + 0x0566
BLT_H       = VPU + 0x0567
BLT_SRCPL_L = VPU + 0x0568
BLT_SRCPL_H = VPU + 0x0569
BLT_DSTPL_L = VPU + 0x056A
BLT_DSTPL_H = VPU + 0x056B
BLT_FILL    = VPU + 0x056C
BLT_PMASK   = VPU + 0x056D
BLT_ROP     = VPU + 0x056E
BLT_SHIFT   = VPU + 0x056F
BLT_FMASK   = VPU + 0x0570
BLT_LMASK   = VPU + 0x0571

BLT_X_L     = VPU + 0x0572
BLT_X_H     = VPU + 0x0573
BLT_Y_L     = VPU + 0x0574
BLT_Y_H     = VPU + 0x0575
BLT_WPX_L   = VPU + 0x0576
BLT_WPX_H   = VPU + 0x0577
BLT_HPX     = VPU + 0x0578

BLT_SRCBANK = VPU + 0x0579
BLT_SRCOFS_L= VPU + 0x057A
BLT_SRCOFS_H= VPU + 0x057B
BLT_SX_L    = VPU + 0x057C
BLT_SX_H    = VPU + 0x057D
BLT_SY_L    = VPU + 0x057E
BLT_SY_H    = VPU + 0x057F
BLT_PSRC_L  = VPU + 0x0580
BLT_PSRC_H  = VPU + 0x0581
BLT_DXSTEP_L= VPU + 0x0582
BLT_DXSTEP_H= VPU + 0x0583
BLT_DYSTEP_L= VPU + 0x0584
BLT_DYSTEP_H= VPU + 0x0585
BLT_SXSTEP_L= VPU + 0x0586
BLT_SXSTEP_H= VPU + 0x0587
BLT_SYSTEP_L= VPU + 0x0588
BLT_SYSTEP_H= VPU + 0x0589
```

### Enable MMIO window (you already do this)

```asm
; WIN_MMIO=1
i  VIDWIN
u  0x04
sb
```

---

# 1) Palette write (RGB565 little-endian)

Example: set palette index `0x20` to RGB565 `0xF81F` (magenta).

Palette entry address:
`PAL_BASE + idx*2`

```asm
; idx = 0x20 -> addr = 0x0100 + 0x40 = 0x0140 (MMIO)
; absolute = VPU + 0x0140 = 0xC140

i  0xC140
u  0x1F          ; low byte
sb
i  0xC141
u  0xF8          ; high byte
sb
```

---

# 2) Group setup (one 8bpp playfield, group0 owns planes 0..7)

This sets:

* Group0: `planeStart=0`, `planeCount=8`, `palBase=0`, `scroll=0`, `priority=0`, `flags=WRAP_X|WRAP_Y`, `bplOfs=0`
* Groups 1..3 disabled (planeCount=0)

```asm
; group0 base = GROUP_BASE + 0x00 = 0xC400
; bytes:
; +0 planeStart
; +1 planeCount
; +2 palBase
; +3 scrollX_L
; +4 scrollX_H
; +5 scrollY
; +6 priority
; +7 flags (WRAP_X=0x02, WRAP_Y=0x04 -> 0x06)
; +8 bplOfs_L
; +9 bplOfs_H

; --- Group0 ---
i  0xC400  u  0x00  sb     ; planeStart=0
i  0xC401  u  0x08  sb     ; planeCount=8 (8bpp)
i  0xC402  u  0x00  sb     ; palBase=0
i  0xC403  u  0x00  sb     ; scrollX_L
i  0xC404  u  0x00  sb     ; scrollX_H
i  0xC405  u  0x00  sb     ; scrollY
i  0xC406  u  0x00  sb     ; priority=0
i  0xC407  u  0x06  sb     ; flags=WRAP_X|WRAP_Y
i  0xC408  u  0x00  sb     ; bplOfs_L
i  0xC409  u  0x00  sb     ; bplOfs_H

; --- Disable group1..3 (write planeCount=0 at base+1) ---
i  0xC411  u  0x00  sb     ; group1 planeCount=0
i  0xC421  u  0x00  sb     ; group2 planeCount=0
i  0xC431  u  0x00  sb     ; group3 planeCount=0
```

---

# 3) Sprite init (sprite0 @ (100,50), 16×16, bank=9, palBase=0x20)

Key detail: v4.1 bank selection is:
`bank = (ATTR bits7..6 << 2) | (DATA bits15..14)` and then clamped to 9..F.

To force **bank=9 (0b1001)**:

* bankHi (ATTR bits7..6) = `0b10` → `0x80`
* bankLo (DATA bits15..14) = `0b01` → `0x4000`

So:

* `ATTR = 0x80 | SA_EN(0x01) = 0x81`
* `DATA = 0x4000 | patternOffset`

```asm
; sprite0 base = OAM_BASE + 0*32 = 0xD000
; layout used by v4.1:
; +0..1 X (s16 LE)
; +2..3 Y (s16 LE)   (in source 0..199 space)
; +4 W (0=>256)
; +5 H (0=>256)
; +6..7 DATA (u16 LE)  (ofs low14 + bankLo bits)
; +8 ATTR
; +9 PAL_BASE
; +10 COL_ID (low nibble)
; +11 PRIORITY
; +12..13 XSTEP (u16 8.8) (0x0100 = 1.0)
; +14..15 YSTEP (u16 8.8)
; +16 TILT_DX (s8)
; +17 LINK (0..127)

; X=100 (0x0064)
i  0xD000  u  0x64  sb
i  0xD001  u  0x00  sb

; Y=50 (0x0032)
i  0xD002  u  0x32  sb
i  0xD003  u  0x00  sb

; W=16, H=16
i  0xD004  u  0x10  sb
i  0xD005  u  0x10  sb

; DATA = 0x4000 | 0x0000  (pattern offset 0)
i  0xD006  u  0x00  sb     ; DATA_L
i  0xD007  u  0x40  sb     ; DATA_H

; ATTR = 0x81  (EN=1 + bankHi=0b10)
i  0xD008  u  0x81  sb

; PAL_BASE = 0x20
i  0xD009  u  0x20  sb

; COL_ID = 1 (optional)
i  0xD00A  u  0x01  sb

; PRIORITY = 0  (with group0 priority 0 => this lands in slot *after* group0)
i  0xD00B  u  0x00  sb

; XSTEP=0x0100
i  0xD00C  u  0x00  sb
i  0xD00D  u  0x01  sb

; YSTEP=0x0100
i  0xD00E  u  0x00  sb
i  0xD00F  u  0x01  sb

; TILT_DX=0, LINK=0
i  0xD010  u  0x00  sb
i  0xD011  u  0x00  sb

; Enable sprites globally (SPR_CTRL bit0)
i  REG_SPR_CTRL
u  0x01
sb
```

> If you want the sprite **below** group0, set group0 priority to `1` and sprite priority to `0` (so sprPri < gp[0]).

---

# 4) Copper list example (disable text at top, enable later, palette change)

Copper program lives in **VBANK=8** (tables bank) as 8-byte entries.
Then you set:

* `COP_OFS = 0`
* `COP_LEN = entries * 8`
* `COP_CTRL = 1`

### 4.1 Switch window to VBANK=8 (ASSUMED mapper)

```asm
; WIN_MMIO=0 (banked)
i  VIDWIN
u  0x00
sb

; select VBANK=8 (tables)
i  VIDBANK
u  0x08
sb
```

### 4.2 Write copper entries into tables bank

We’ll place program at offset 0.

Entry format (8 bytes):

* SCAN (u16 LE)
* REG  (u16 LE)  ; MMIO offset, not absolute
* VAL_LO, VAL_HI
* FLAGS (bit0 WRITE16, bit7 END)
* pad byte

We’ll do:

1. scan=0: write TX_CTRL = 0 (disable text)
2. scan=300: write TX_CTRL = 1 (enable text)
3. scan=100: write palette entry 1 to RGB565 0xF800 (red) using WRITE16
4. END

**Write addresses while banked**: you write to `VPU_WINDOW + offset`. If your banked window base is the same `0xC000`, then `0xC000 + entryOffset`.

```asm
; Entry0 @ ofs 0x0000: SCAN=0, REG=0x0006 (TX_CTRL), VAL_LO=0x00
; SCAN=0
i  0xC000  u  0x00  sb     ; scan lo
i  0xC001  u  0x00  sb     ; scan hi
; REG=0x0006
i  0xC002  u  0x06  sb     ; reg lo
i  0xC003  u  0x00  sb     ; reg hi
; VAL
i  0xC004  u  0x00  sb     ; vLo
i  0xC005  u  0x00  sb     ; vHi
; FLAGS
i  0xC006  u  0x00  sb
i  0xC007  u  0x00  sb

; Entry1 @ ofs 0x0008: SCAN=300 (0x012C), REG=0x0006, VAL_LO=0x01
i  0xC008  u  0x2C  sb     ; scan lo
i  0xC009  u  0x01  sb     ; scan hi
i  0xC00A  u  0x06  sb     ; reg lo
i  0xC00B  u  0x00  sb     ; reg hi
i  0xC00C  u  0x01  sb     ; vLo
i  0xC00D  u  0x00  sb     ; vHi
i  0xC00E  u  0x00  sb     ; flags
i  0xC00F  u  0x00  sb

; Entry2 @ ofs 0x0010: SCAN=100 (0x0064), REG=0x0102 (palette idx1 low), WRITE16, VAL=0xF800
; SCAN=100
i  0xC010  u  0x64  sb
i  0xC011  u  0x00  sb
; REG=0x0102
i  0xC012  u  0x02  sb
i  0xC013  u  0x01  sb
; VAL_LO=0x00, VAL_HI=0xF8
i  0xC014  u  0x00  sb
i  0xC015  u  0xF8  sb
; FLAGS=WRITE16
i  0xC016  u  0x01  sb
i  0xC017  u  0x00  sb

; Entry3 @ ofs 0x0018: END
i  0xC018  u  0x00  sb
i  0xC019  u  0x00  sb
i  0xC01A  u  0x00  sb
i  0xC01B  u  0x00  sb
i  0xC01C  u  0x00  sb
i  0xC01D  u  0x00  sb
i  0xC01E  u  0x80  sb     ; END flag
i  0xC01F  u  0x00  sb
```

### 4.3 Switch back to MMIO and enable copper

```asm
; WIN_MMIO=1
i  VIDWIN
u  0x04
sb

; COP_OFS = 0x0000
i  REG_COP_OFSL  u  0x00  sb
i  REG_COP_OFSH  u  0x00  sb

; COP_LEN = 0x0020 (4 entries * 8 bytes = 32)
i  REG_COP_LENL  u  0x20  sb
i  REG_COP_LENH  u  0x00  sb

; COP_CTRL = 1 (enable)
i  REG_COP_CTRL
u  0x01
sb
```

---

# 5) RECTFILL blit (fill a pixel rectangle using color index)

Example: fill a rectangle at `(x=80, y=40)` size `(w=200, h=60)` with color index `0x12`
Assumes:

* framebuffer base in planes starts at byte 0 (`BLT_DST=0`)
* **pitch bytes per row = 80** (640 / 8)

```asm
; --- setup RECTFILL ---
; BLT_DST = 0x0000
i  BLT_DST_L  u  0x00  sb
i  BLT_DST_H  u  0x00  sb

; BLT_DST_PITCH = 80 (0x0050) bytes/row
i  BLT_DSTPL_L u  0x50 sb
i  BLT_DSTPL_H u  0x00 sb

; plane mask = all
i  BLT_PMASK  u  0xFF sb

; fill color index (8-bit)
i  BLT_FILL   u  0x12 sb

; ROP = RECTFILL (4)
i  BLT_ROP    u  0x04 sb

; X=80 (0x0050)
i  BLT_X_L    u  0x50 sb
i  BLT_X_H    u  0x00 sb

; Y=40 (0x0028)
i  BLT_Y_L    u  0x28 sb
i  BLT_Y_H    u  0x00 sb

; WPX=200 (0x00C8)
i  BLT_WPX_L  u  0xC8 sb
i  BLT_WPX_H  u  0x00 sb

; HPX=60 (0x3C)
i  BLT_HPX    u  0x3C sb

; START (bit0)
i  BLT_CTRL
u  0x01
sb
```

**Waiting for completion (two options):**

* Option A: poll `BLT_CTRL` busy bit7 (0x80)
* Option B: poll `STATUS` bit4 (0x10)

I’m writing the loop as a **pattern** because your exact “load byte + branch-if-zero” mnemonics may differ:

```asm
; PSEUDO:
; loop:
;   a = [BLT_CTRL] & 0x80
;   if a != 0 -> branch loop

; then optionally clear STATUS_BLT (W1C):
;   [STATUS] = 0x10
```

---

# 6) RECTBLIT blit (packed 4bpp sprite bank → planar VRAM)

Example: copy a 16×16 packed sprite from sprite bank 9 into the framebuffer at `(x=200, y=80)`.

Assume your packed sprite is stored at:

* `SRCBANK=9`
* `SRCOFS=0`
* `SX=0`, `SY=0`
* `PSRC_PITCH = 8` bytes/row (16 pixels → 8 bytes)

```asm
; --- setup RECTBLIT ---
; dest framebuffer base offset
i  BLT_DST_L   u  0x00 sb
i  BLT_DST_H   u  0x00 sb

; dest pitch bytes/row = 80
i  BLT_DSTPL_L u  0x50 sb
i  BLT_DSTPL_H u  0x00 sb

; dest rect: X=200 (0x00C8), Y=80 (0x0050), W=16, H=16
i  BLT_X_L     u  0xC8 sb
i  BLT_X_H     u  0x00 sb

i  BLT_Y_L     u  0x50 sb
i  BLT_Y_H     u  0x00 sb

i  BLT_WPX_L   u  0x10 sb
i  BLT_WPX_H   u  0x00 sb
i  BLT_HPX     u  0x10 sb

; source packed params
i  BLT_SRCBANK u  0x09 sb

i  BLT_SRCOFS_L u 0x00 sb
i  BLT_SRCOFS_H u 0x00 sb

i  BLT_SX_L    u  0x00 sb
i  BLT_SX_H    u  0x00 sb
i  BLT_SY_L    u  0x00 sb
i  BLT_SY_H    u  0x00 sb

; packed source pitch = 8 bytes/row
i  BLT_PSRC_L  u  0x08 sb
i  BLT_PSRC_H  u  0x00 sb

; src steps = 1.0 (0x0100)
i  BLT_SXSTEP_L u 0x00 sb
i  BLT_SXSTEP_H u 0x01 sb
i  BLT_SYSTEP_L u 0x00 sb
i  BLT_SYSTEP_H u 0x01 sb

; dst steps must be 1.0 in v4.1 (forced)
i  BLT_DXSTEP_L u 0x00 sb
i  BLT_DXSTEP_H u 0x01 sb
i  BLT_DYSTEP_L u 0x00 sb
i  BLT_DYSTEP_H u 0x01 sb

; plane mask = all
i  BLT_PMASK   u  0xFF sb

; ROP = RECTBLIT (5)
i  BLT_ROP     u  0x05 sb

; START with TRANS (bit4) to treat pix==0 as transparent:
; CTRL = TRANS(0x10) | START(0x01) = 0x11
i  BLT_CTRL
u  0x11
sb
```

---

## (Optional) Clearing collision flags

```asm
i  COL_CLR
u  0x01
sb
```

---
