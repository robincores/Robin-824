## ✅ BIOS ABI (best option)

### **AReg/BReg/CReg register ABI** + optional “arg block pointer”

It’s the simplest for early bring-up, fast in hardware/software, and doesn’t couple BIOS to whatever stack convention your assembler/compiler ends up using.

**Contract (v1):**

* `AReg` = **syscall number**
* `BReg` = **arg0**
* `CReg` = **arg1**
* Return:

    * `AReg` = **result** (or `0xFFFF` on error)
    * `BReg` = **errno** if `AReg==0xFFFF` (else undefined)
    * `CReg` may be clobbered

For calls needing more args, we use a cheap extension:

* `BReg` = pointer to an **argument block in RAM** (format per syscall), and `CReg` can hold a small “flags/len” if needed.

This keeps ECALL usage stable forever and doesn’t depend on stack layout.

---

# R8 “C64+VDP” Minimal System Spec (Baseline v1)

## 1) Buses / shell

* **Address bus:** 16-bit (`0x0000–0xFFFF`)
* **Data bus:** 8-bit
* **Von-Neumann shell** for instruction+data
* Shared memory bus arbitration between:

    * Master0 = CPU shell
    * Master1 = DMA engine

**Handshake:** `valid/we/addr/wdata` ↔ `ready/rvalid/rdata`
MMIO is allowed to stall (`ready=0`) — important for VDP streaming.

---

## 2) Memory and address map (CPU-visible 64K)

### Physical:

* **64K RAM** always present
* **8K Boot ROM**
* **64K VRAM** inside VDP (separate from CPU RAM)

### CPU map (after boot completes)

We reserve a BIOS RAM window (8K) just below the I/O page so MMIO can stay at `0xFE00`.

| Range           | Function                                                |
| --------------- | ------------------------------------------------------- |
| `0x0000–0xDDFF` | **User RAM**                                            |
| `0xDE00–0xFDFF` | **BIOS in RAM (8K)**                                    |
| `0xFE00–0xFEFF` | **MMIO page**                                           |
| `0xFF00–0xFFFF` | **System RAM page** (vectors/scratch/stack if you want) |

### Boot overlay (reset behavior)

On reset, we overlay ROM at `0x0000–0x1FFF` **for reads**:

* **Reset PC = `0x0000`**
* `BOOTROM_EN=1` makes reads from `0x0000–0x1FFF` come from ROM
* Writes may still go to underlying RAM (optional but recommended “shadow writes”)

Then BIOS disables the overlay and RAM becomes fully visible at low memory.

---

## 3) Mapping control (cheap ROM unmap hardware)

**`MAPCTRL` @ `0xFEF0` (8-bit)**

* `bit0 IOEN` : 1=MMIO at `0xFE00`, 0=RAM underlay (debug only)
* `bit1 BOOTROM_DIS` : write 1 → clears boot ROM overlay **sticky until reset**

Reset defaults:

* `IOEN=1`, `BOOTROM_EN=1`

---

## 4) Boot flow (exact)

1. Reset: `PC=0x0000`, `mtvec=0x0002`, `BOOTROM_EN=1`
2. ROM stub:

    * init minimal state (disable interrupts)
    * copy ROM image (8K) → **RAM `0xDE00–0xFDFF`**
3. set `mtvec = 0xDE02`
4. write `MAPCTRL.BOOTROM_DIS=1` (unmap ROM)
5. jump to BIOS cold start entry in RAM (e.g. `0xDE10`)

This gives you full 64K RAM *except* the BIOS reserve window (like “KERNAL”, but placed below IO page).

---

## 5) Interrupts + traps (RISC-V inspired)

Core lines:

* `irq_sw`
* `irq_timer`
* `irq_ext`

Trap entry:

* `mtvec` = trap vector base
* `mcause` encodes cause
* **ECALL uses `mcause=0x000B`** (matches your current core)

BIOS trap handler at `mtvec`:

* If `mcause==0x000B` → syscall dispatch
* Else handle timer/external/software IRQ causes as you define

---

## 6) MMIO Page (`0xFE00–0xFEFF`) layout

|           Range | Block                                                   |
| --------------: | ------------------------------------------------------- |
| `0xFE00–0xFE1F` | **VDP**                                                 |
| `0xFE20–0xFE2F` | **DMA**                                                 |
| `0xFE40–0xFE4F` | **Timers** (`mtime`, `mtimecmp`, `msip`)                |
| `0xFE50–0xFE5F` | **IRQ controller** (pending/enable/ack)                 |
| `0xFE60–0xFE6F` | **UART (TX-only) + optional RX** *(cheap + huge value)* |
|        `0xFEF0` | `MAPCTRL`                                               |

---

## 7) VDP programming model (64K VRAM, classic “data port”)

Minimal, cheap, DMA-friendly:

Registers:

* `0xFE00 VDP_VADDR_L`
* `0xFE01 VDP_VADDR_H`
* `0xFE02 VDP_VDATA` (streaming read/write; auto-increment)
* `0xFE03 VDP_CTRL`
* `0xFE04 VDP_STATUS`

Rules:

* CPU sets `VADDR` then streams bytes via `VDATA`
* VDP auto-increments VRAM pointer on each `VDATA` access
* `VDP_STATUS` can expose `VDATA_READY` so DMA/CPU can stall safely

(Rendering modes can be layered later — this port model is the key “hardware contract”.)

---

## 8) DMA (one engine, covers mem↔mem and mem↔port)

Registers:

* `DMA_SRC_L/H` @ `0xFE20–21`
* `DMA_DST_L/H` @ `0xFE22–23`
* `DMA_LEN_L/H` @ `0xFE24–25`
* `DMA_CTRL` @ `0xFE26`
* `DMA_STATUS` @ `0xFE27`

`DMA_CTRL` bits:

* `START` (self-clears)
* `SRC_INC`
* `DST_INC`
* `IRQ_EN`

**Key trick:** “port DMA” needs no new mode:

* RAM→PORT: `DST_INC=0`, `DST=MMIO addr` (e.g. `VDP_VDATA`)
* PORT→RAM: `SRC_INC=0`, `SRC=MMIO addr`
* RAM→RAM: both inc

MMIO must be allowed to stall DMA via `ready=0`.

---

## 9) Timers (RISC-V inspired, byte lanes)

* `mtime` 32-bit @ `0xFE40–0xFE43`
* `mtimecmp` 32-bit @ `0xFE44–0xFE47`
* `msip` @ `0xFE48` bit0

Behavior:

* `irq_timer` when `mtime >= mtimecmp`
* `msip.bit0` asserts `irq_sw`

---

## 10) BIOS syscalls (initial set)

Using the ABI above (`AReg`=id, `BReg/CReg` args):

Example v0 table:

* `0x0000` `PUTC` : `BReg=byte`
* `0x0001` `GETC` : returns byte in `AReg` (or `0xFFFF` if none)
* `0x0010` `VDP_SETADDR` : `BReg=addr16`
* `0x0011` `VDP_WRITE` : `BReg=byte`
* `0x0020` `DMA_START` : `BReg=src16`, `CReg=dst16`, (LEN read from fixed RAM location or use arg-block extension)
* `0x0030` `TIMER_READ_LO16` : returns low 16 in `AReg`
* `0x00F0` `MAPCTRL_WRITE` (optional wrapper)

We can refine the exact list once you decide what you want available to userland on day 1.

---

# Two “cheap but priceless” add-ons

If you want maximum debugging power for near-zero RTL cost:

1. **UART TX-only** register (one byte write)
2. **GPIO 8-bit** input/output register pair (keyboard matrix later)
