## Baseline v1 — R8 Assembler (r8as) User Manual

### 1) What this assembler produces

* Input: one or more `.asm` source files (with `.include` / `.module` support).
* Output (for now): **one flat binary** created by concatenating sections in a deterministic order:

    1. `.text`
    2. `.data`
    3. any other **progbits** sections (in insertion order)

    * `.bss` **does not emit bytes** (it only reserves space and advances symbols/addresses).

Internally the assembler tracks **per-section addresses**, and it also computes section metadata (origins, sizes, flat offsets) during `finish()`.

---

### 2) Basic source format

#### Comments

* `;` starts a comment **unless it is inside a quoted string**.

  ```asm
  add ; comment
  .string "a;b" ; semicolon inside quotes is preserved
  ```

#### Case sensitivity

* **Labels and mnemonics are case-insensitive** (the assembler normalizes to lowercase internally).

#### Identifiers

* `A-Z a-z _` start, followed by `A-Z a-z 0-9 _`
* Dot-local labels like `.Ltmp` are supported via a rewrite pass (see Labels below).

---

### 3) Numbers & expressions

#### Integer literals

* Decimal: `123`
* Hex: `0x7B` or `$7B`
* Underscores are allowed: `0x12_34`, `1_000_000`

#### Expressions

Expressions are supported in many places (operands, `.org`, `.data`, etc.):

* Operators: `+ - * / %`
* Parentheses: `( … )`
* Special symbol:

    * `.` = current location counter (**in words**, i.e. the current section’s `ip`)

Example:

```asm
.org 0x100
.data 1+2, (3*4), label+8, .+1
```

Forward references inside expressions are supported via **fixups** (resolved at `finish()`), e.g.:

```asm
.data target+2
target: .data 0x55
```

---

### 4) Labels

#### Global labels

```asm
start:
  nop
```

#### Dot-local labels (`.Lfoo`)

These are accepted:

```asm
.Lloop:
  nop
```

They’re rewritten internally to a legal identifier (so they parse cleanly).

#### Numeric local labels (`1:`, `1f`, `1b`)

Supported:

```asm
1:
  nop
  j 1b     ; back to nearest previous “1:”
  j 1f     ; forward to next “1:”
1:
  nop
```

These are useful inside macros and short control-flow blocks.

---

### 5) Sections & location counters (Step 15)

#### Section directives

* `.text` — switch to text section (progbits, emits bytes)
* `.data` — switch to data section (progbits, emits bytes)
* `.bss` — switch to BSS section (**reserve only**, no bytes emitted)
* `.section name` — switch to an arbitrary progbits section (name normalized to start with a `.`)

Examples:

```asm
.text
start:  nop

.data
msg:    .string "HI"

.bss
buf:    .data 0,0,0,0   ; reserves 4 words, emits nothing
```

#### Separate location counters per section

Each section tracks:

* `ip` (location counter)
* `origin` (base)
  independently.

Switching sections restores that section’s `ip`.

#### `.org` is per-section

`.org` affects the **current section only**.

Important behavior:

* If used before any bytes in that section: it can set the section `origin`.
* If used after output exists:

    * moving forward creates gaps (filled with zeros when emitting)
    * moving backward overwrites previously-emitted words (as you re-emit)

Also note: `.org` takes a **byte address**, but internally it becomes a **word address**.
For R8 (`width=8`), bytes == words so it feels identical.

Example:

```asm
.text
.org 0x0200
start: nop

.data
.org 0x1000
table: .data 1,2,3
```

#### `.bss` reserves space (no emitted bytes)

In `.bss`, directives that “emit” words instead only advance `ip`:

* `.data …` advances `ip` by count
* `.string "..."` advances `ip` by string length
* `.align` advances as needed
  No bytes are placed in the output for `.bss`.

---

### 6) Data emission directives

#### `.data <expr>[, <expr> ...]`

Emits one **word** per item (for R8: one byte per item). Expressions and forward refs supported.

```asm
.data 1,2,0xFF,label+1
```

#### `.string "text"`

Emits one word per character **without** an automatic NUL terminator.

Escapes supported:

* `\\`, `\"`, `\n`, `\r`, `\t`

```asm
.string "Hello\n"
.data 0   ; add your own terminator if needed
```

#### `.align <bytes>`

Aligns the current section’s `ip` to `<bytes>` (must be a multiple of word size).
Pads with zeros in progbits sections; reserves in `.bss`.

```asm
.align 16
```

#### `.len <bytes>`

Sets a minimum length for the current section (pads with zeros at `finish()` if shorter).

---

### 7) Symbols / constants

#### `.define NAME VALUE`

Defines a constant (stored as a symbol). VALUE may be an expression resolvable now.

#### `.equ NAME VALUE`

Like `.define`, but **errors if NAME already exists**.

#### `.set NAME VALUE`

Defines or replaces NAME.

Example:

```asm
.equ  SCREEN 0x8000
.set  X 1
.set  X X+1
```

---

### 8) Macros

#### Define a macro

```asm
.macro loadimm reg,val
  ; use \reg and \val in the body
  i \val
.endm
```

#### Invoke a macro

Macro calls look like instruction calls:

```asm
loadimm a,0x1234
```

#### Macro parameters & escapes

Inside the macro body:

* `\name` substitutes a named parameter
* `\1`, `\2`, ... substitutes by position
* `\@` is a unique number per expansion (helps create unique labels)

Example:

```asm
.macro loop_n n
  1\@:          ; unique numeric-ish label
  ; ...
  j 1\@
.endm
```

Limits:

* No nested `.macro` definitions
* Macro recursion is detected and rejected
* Macro argument parsing is **comma-based** (recommended: always use commas for multi-arg macros)

---

### 9) Includes / modules

#### `.include "path"`

Loads another assembly text and assembles it immediately.

Search order (practical summary):

* relative to the current source file directory
* then include search paths (if configured programmatically)
* then current working directory
* then classpath fallbacks (e.g., `include/`)

#### `.module "path"`

Currently behaves like `.include`, but also checks classpath prefixes like `modules/`.

Guards:

* Recursive include detection
* Max include depth (default 32)

---

### 10) Architecture spec loading (instruction set)

The assembler’s instruction encoding is driven by a JSON **arch spec** (vars + rules).

You can load it in source with:

```asm
.arch R824
```

(`.json` is optional)

If the environment constructs the assembler with a preloaded spec, you might not need `.arch` in the source.

---

### 11) Output & section metadata (API-level)

The assembler computes a flat output plus metadata like:

* each section’s `origin_bytes`, `size_bytes`
* each section’s `flat_start_bytes`
* `section_order`

If you’re using the Java API, it’s available via:

* `AssemblerState.output` (words; for R8 each is a byte)
* `AssemblerState.intermediate` (contains section metadata)

---

### 12) Minimal worked example

```asm
.arch R824
.width 8

.text
.org 0x0200
start:
  nop
  j start

.data
.org 0x1000
msg:
  .string "HI"
  .data 0

.bss
.org 0x2000
buf:
  .data 0,0,0,0,0,0,0,0   ; reserves 8 bytes, emits nothing
```

Flat output will contain `.text` bytes first, then `.data`. `.bss` is only recorded in metadata.

## Detailed TODO list

### A) Documentation + UX (do this next, before more features)

1. **Write `docs/r8as.md` (User Manual)**

    * Syntax basics: comments, labels (global/dot/numeric), literals, expressions
    * Directives: `.org`, `.align`, `.len`, `.data`, `.string`, `.equ/.set/.define`
    * Sections: `.text/.data/.bss/.section`, concat order, `.bss` reserve-only
    * Macros: `.macro/.endm`, params, `\@`, recursion limits
    * Includes: `.include/.module`, search order, recursion guard
    * Error model: how errors are shown (file/line/col), fatal vs non-fatal
    * Examples: “hello world”, data tables, local labels + fixups, multi-section
2. **CLI help output improvement**

    * Ensure `--help` shows all flags clearly
    * Add examples in help (`r8as file.s -o out.bin`, `--arch`, `--width`)
3. **Versioning**

    * Print version in CLI (`--version`)
    * Embed build/version into jar manifest

---

### B) Step 16 — Listing + Map file (high value, low risk)

4. **`.lst` listing file**

    * One line per source line (only when it emits words)
    * Columns: `SECTION  ADDR  BYTES  WORDS  SOURCE`
    * Include macro-expanded lines with a marker (e.g. `; [macro]`)
    * Stable/deterministic formatting
5. **`.map` symbol map**

    * Sections summary: origin, size, flat start, emitted bytes
    * Symbols grouped by section + sorted by address
    * Include `.equ` constants separately (no section / absolute)
6. **Expose section layout info cleanly**

    * Add `SectionInfo` DTO (origin/ip/size/flatStart)
    * Store in `AssemblerState.intermediate` under stable keys

---

### C) Section model hardening (correctness + future linker)

7. **Section flags**

    * Track type: `PROGBITS` (emits) vs `NOBITS` (`.bss`)
    * Disallow instructions in `NOBITS` (already done) + better error message
8. **Alignment per section**

    * Allow `.section name, "ax"` style later (optional)
    * For now: add `.sectionalign name, N` or support `.align` at section start
9. **Deterministic “other section” order**

    * Document and enforce insertion order for custom sections
    * Optionally allow `--section-order=.text,.rodata,.data`
10. **Gap fill policy**

* Define behavior when `.org` jumps forward inside PROGBITS:

    * Fill with zeros (current behavior) — document it
* Decide behavior for going backwards (overwrite) — document it

---

### D) Expressions + fixups (make it bulletproof)

11. **Fixup range checking**

* When fixup resolves, validate width/range (8-bit/16-bit etc.)
* Clear error: “relocation out of range”

12. **More expression operators (optional)**

* Bitwise: `& | ^ ~ << >>`
* Unary `+` and `-` (if not already)

13. **Relocation expressions**

* `hi(symbol)`, `lo(symbol)` helpers (ONLY if your ISA needs them)
* Keep them as functions rather than fake fields (avoids prior bug class)

---

### E) Data directives expansion (quality of life)

14. **`.byte/.word/.dword`**

* Map to widths (8/16/32) regardless of CPU width
* Useful for tables and host tooling

15. **`.ascii` / `.asciz`**

* `.ascii` = raw string
* `.asciz` = NUL-terminated

16. **`.space N`**

* In PROGBITS: emit N zero bytes/words
* In BSS: reserve N

17. **`.fill count, value`**

* Repeated constant fill (tables, padding)

---

### F) Conditional assembly (commonly needed)

18. **`.if/.elseif/.else/.endif`**

* Expression-based conditionals

19. **`.ifdef/.ifndef`**

* Based on defined symbols/macros

---

### G) Tests (must grow with features)

20. **Add section-specific unit tests**

* Per-section `.org` independence
* `.bss` reserves without output
* Concatenation order and flat offsets
* Symbols in each section resolve correctly

21. **Golden tests**

* Given input `.asm`, assert exact binary bytes
* Assert `.map` and `.lst` exact output once implemented

22. **Negative tests**

* Instruction in `.bss` fails
* `.org` misalignment fails
* fixup out-of-range fails (after you add checks)

---

### H) Packaging for “real programs”

23. **A tiny standard library / headers**

* `r8.inc` constants, IO registers, common macros

24. **Sample programs**

* “blink” / “hello video” style examples in `examples/`

25. **CI**

* Run `mvn test` + maybe a golden assembly sample check

---

### I) Longer-term (bigger steps; only after Step 16)

26. **Relocatable object output**

* Emit a simple `.o` format (JSON or custom) with:

    * per-section bytes
    * symbol table
    * relocations

27. **Linker**

* Place sections at configured addresses
* Resolve relocations
* Produce final flat binary

