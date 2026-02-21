## R8 Assembler (r8as) User Manual — v2

> This document describes the **current behavior** of the `r8as` assembler as implemented in the codebase (sections + macros + includes + expression evaluator).
> It is written for **R8/R816** (8-bit words), but notes where behavior differs when `width != 8`.

### Changelog (v1 → v2)
- Clarified that **`.data` is a section selector**; numeric emission is **`.byte`** (one *word* per item).
- Expanded the **expression language** to match the current evaluator: unary `+ - ~`, binary `+ - * / % << >> & ^ |`, parentheses, numeric locals `1f/1b`, and `.` (location counter).
- Documented **`.arch` JSON search order**, validation behavior, and how it sets default word width.
- Documented **include/module search order**, recursion/depth guards, and the caveat that relative includes require `assemblePath(...)` (or include paths) in the embedding code.
- Added a short, practical **R816 quick-start** with real directives and gotchas.

---

## 1) Quick start

### CLI (current `Main`)
The current CLI entry point takes **two positional arguments**:

```text
Usage: AssemblerMain <file.asm> <output.bin>
```

Typical usage (depending on how you package your jar):

```bash
java -cp <your-jar>.jar io.github.robincores.toolchain.r8as.Main program.asm program.bin
# or, if the jar has a Main-Class:
java -jar <your-jar>.jar program.asm program.bin
```

**Important:** the sample `Main` writes **one byte per emitted word** (lowest 8 bits).  
That is correct for **R816 (`width=8`)**, but if you assemble with `width=16/24/32` you must write multi-byte words yourself (via the Java API).

---

## 2) What the assembler produces

- Input: one or more `.asm` sources (with `.include` / `.module` support).
- Output: **one flat binary** created by concatenating sections in deterministic order:

  1. `.text`
  2. `.data`
  3. any other **progbits** sections (in insertion order)

- `.bss` is **NOBITS**: it **does not emit bytes** (it reserves space and advances addresses).

At `finish()`, the assembler also computes **section metadata** (origins, sizes, flat offsets).

---

## 3) Source line model and syntax

The assembler parses **one source line at a time**.

A line can contain:
- optional label definition: `label:`
- optional directive: `.org ...`, `.byte ...`, etc.
- optional instruction: `add`, `j target`, etc.

### Comments
- `;` starts a comment **unless it is inside a quoted string**.

```asm
add ; comment
.string "a;b" ; semicolon inside quotes is preserved
```

### Case-sensitivity
- Labels, directives, and mnemonics are treated **case-insensitively**.

### Identifiers
- `A-Z a-z _` start, followed by `A-Z a-z 0-9 _`
- Dot-local labels are supported only in the form **`.Lname`** (see Labels).

---

## 4) Numbers and expressions

### Integer literals
- Decimal: `123`
- Hex: `0x7B` or `$7B`
- Underscores are allowed: `0x12_34`, `1_000_000`

### Expression operators
Supported anywhere an expression is accepted (operands, `.org`, `.byte`, etc.):

- Unary: `+  -  ~`
- Binary: `+  -  *  /  %  <<  >>  &  ^  |`
- Parentheses: `( ... )`
- Special symbol:
  - `.` = current location counter (**in words**, i.e., the current section’s `ip`)

Examples:

```asm
.org 0x100
.byte 1+2, (3*4), 0x10 | 3, ~0
.byte .+1           ; next word
```

### Forward references in expressions
Forward references are supported **where fixups exist**, notably:
- instruction immediates (e.g., `j target`, `beq target`)
- `.byte` data emission

For directives like `.equ` / `.set`, the expression must be resolvable immediately (no forward refs).

---

## 5) Labels

### Global labels
```asm
start:
  nop
```

### Dot-local labels (`.Lfoo`)
These are accepted:

```asm
.Lloop:
  nop
  j .Lloop
```

**How it works today:** `.Lfoo` is rewritten to `_Lfoo` during preprocessing.  
This is a convenience for parsing, not a true “scoped local label” system—so:
- treat them as **global within the assembled unit**
- for uniqueness in macros, prefer `\@` (see Macros)

### Numeric local labels (`1:`, `1f`, `1b`)
Supported:

```asm
1:
  nop
  j 1b     ; back to nearest previous “1:”
  j 1f     ; forward to next “1:”
1:
  nop
```

Numeric refs work inside expressions too (e.g., `.byte 1f - .`), and are resolved relative to the current `.`.

---

## 6) Sections and location counters (Step 15)

### Section selectors
- `.text` — switch to text section (progbits, emits)
- `.data` — switch to data section (progbits, emits)
- `.bss` — switch to BSS section (**reserve only**, emits nothing)
- `.section name` — switch to an arbitrary section
  - `name` is normalized to start with `.` (so `foo` becomes `.foo`)
  - `.section .bss` selects the BSS section

Example:
```asm
.text
start: nop

.data
msg: .string "HI"

.bss
buf: .byte 0,0,0,0   ; reserves 4 words, emits nothing
```

### Separate location counters per section
Each section tracks independently:
- `ip` (word address location counter)
- `origin` (word address base)
- `outwords` (emitted words, progbits only)

Switching sections restores that section’s `ip`.

### `.org` (byte address, per-section)
`.org` affects the **current section only** and takes a **byte address**.

- If used **before any emission** in that section: sets both `origin` and `ip`.
- If used **after emission**: moves `ip` only.
  - moving forward creates gaps (filled with zeros when emitting)
  - moving backward will overwrite earlier emitted words (because you re-emit)

Alignment rule:
- `.org` value must be aligned to the current word size (`width/8` bytes).

### `.align N` (bytes, per-section)
Aligns `ip` to the next multiple of `N` **bytes**.

Alignment rule:
- `N` must also be a multiple of the current word size.

### `.len N` (bytes, per-section minimum size)
Sets a minimum **byte length** for the current section.
At `finish()`, the assembler pads the section with zeros until it reaches `.len`.

---

## 7) Data emission directives

### `.byte <expr>[, <expr> ...]`
Emits **one word per item** (for R816: one byte per item).

- Expressions are allowed.
- Forward references are allowed (emitted as fixups).
- Values are range-checked against the current word width and then masked.

In `.bss`, `.byte` does not emit; it reserves space by advancing `ip`.

Examples:
```asm
.data
table:
  .byte 1,2,3,4
  .byte label+1   ; forward ref ok

.text
label:
  nop
```

> Note: The directive name is historical; it really means “emit one machine word”.

### `.string "text"`
Emits one word per character, **without** an automatic NUL terminator.

Escapes supported:
- `\\`, `\\"`, `\\n`, `\\r`, `\\t`

In `.bss`, `.string` reserves space (advances `ip`) without emitting bytes.

Example:
```asm
.data
msg:    .string "Hello\\n"
        .byte 0          ; add your own terminator
```

**Gotcha (Unicode):** characters are emitted as Java `char` code units; for `width=8`, values above 255 will be truncated when writing a byte-oriented output.

---

## 8) Symbols and constants

### `.equ NAME expr`
Define a constant symbol. Errors if `NAME` already exists.

### `.set NAME expr`
Define or replace a constant symbol.

### `.define NAME value`
Defines a constant using the “constant parser”. In practice it is best used for **pure numeric** values.
(If the value contains unknown symbols, it will not create a fixup.)

Examples:
```asm
.equ  SCREEN 0x8000
.set  X 1
.set  X X+1
```

---

## 9) Architecture selection (`.arch`) and word width (`.width`)

### `.arch name`
Loads an architecture JSON spec (adds `.json` automatically if omitted), validates it, then compiles its rules.

Search order for `name.json`:
1) filesystem path (exact)
2) classpath: `/io/github/robincores/toolchain/r8as/<name>.json`
3) classpath: `/io/github/robincores/toolchain/r8as/arch/<name>.json`
4) classpath: `/arch/<name>.json`
5) classpath: `/<name>.json`

The arch spec’s `width` becomes the assembler’s default word width.

Example:
```asm
.arch r816
```

### `.width 8|16|24|32`
Overrides the assembler word width (bits per word). This impacts:
- how many bytes each emitted word represents (`width/8`)
- alignment rules for `.org`, `.align`, `.len`
- masking/range checks for `.byte` and immediates

---

## 10) Macros

### Define a macro
```asm
.macro loadimm val
  i \val
.endm
```

### Invoke a macro
Macro calls look like instructions:
```asm
loadimm 0x1234
```

### Parameters and escapes inside the body
- `\name` substitutes a named parameter
- `\1`, `\2`, … substitutes by position
- `\@` is a unique number per expansion (useful for unique labels)

Example:
```asm
.macro spin n
  \@loop:
    .byte \n
    j \@loop
.endm
```

Rules/limits:
- Nested `.macro` definitions are not supported.
- Macro recursion is detected and rejected.
- Call sites must pass **exactly** as many args as the macro defines.
- Macro arguments are comma-separated; whitespace-only is also accepted in many cases.

---

## 11) Includes and modules

### `.include "path"`
Loads another assembly source and assembles it immediately.

### `.module "path"`
Currently behaves like `.include`, but classpath probing also checks `modules/`.

Search order (filesystem):
1) relative to the **current source file directory** (when the embedder uses `assemblePath(...)`)
2) each configured include search path (`assembler.addIncludePath(...)`)
3) as given (absolute or relative to current working directory)

Classpath fallback:
- `.include`: tries `path` and `include/path`
- `.module`: tries `path`, `modules/path`, and `include/path`

Guards:
- recursive include detection
- max include depth (default 32; configurable via `setMaxIncludeDepth`)

**Important CLI caveat:** if your CLI uses `assembleFile(String)` (text only), the assembler does not know the source directory, so relative includes won’t resolve “relative to the file”. Prefer `assemblePath(Path)` in a CLI wrapper.

---

## 12) Output, listings, and metadata (Java API)

The assembler returns an `AssemblerState` with:
- `output`: flat list of emitted **words**
- `errors`: diagnostics
- `lines`: per-source-line listing info (line number, word offset, nbits, and post-resolve `insns` hex text)
- `intermediate`: section layout info

### Section metadata shape
`state.getIntermediate()` contains a map with:
- `section_order`: ordered section names
- `sections`: per-section objects with fields like `origin_bytes`, `size_bytes`, `flat_start_bytes`, and `bss`

---

## 13) Worked example (R816)

```asm
.arch r816

.text
.org 0x0200
start:
  nop
  j start

.data
.org 0x1000
msg:
  .string "HI"
  .byte 0

.bss
.org 0x2000
buf:
  .byte 0,0,0,0,0,0,0,0   ; reserves 8 bytes, emits nothing
```

---

## 14) Current limitations / gotchas

- **`.data` does not emit numeric data.** Use `.byte`.
- **Any diagnostic is recorded in `errors`.** In the sample CLI, any diagnostic prevents output from being written.
- Relative `.include` works best when the embedding code calls `assemblePath(...)`.
- `Main` writes only one output byte per word; that is correct for `width=8` only.
- `.Lfoo` is a rewrite convenience, not a true scoped-local label system—avoid collisions across multiple included files or macro expansions.
