# R8AS Architecture Spec (JSON)

This is the JSON format consumed by the `r8as` assembler when you write:

```asm
.arch riscv
```

The assembler searches for `<name>.json` in:

- filesystem (relative to current working directory)
- classpath resources:
  - `/arch/<name>.json`
  - `/<name>.json`
  - `/spec/<name>.json`
  - `/spec/arch/<name>.json`

## Top-level

```json
{
  "name": "riscv",
  "width": 32,
  "vars": { ... },
  "rules": [ ... ]
}
```

- `name` (string): informational.
- `width` (int): **word size in bits** of the output stream (8/16/32/64).
- `vars` (object): variable definitions (operands).
- `rules` (array): instruction encoding rules.

## `vars`

Each var has:

- `bits` (int): width of the operand.
- `toks` (array of strings, optional): token table, where the **token index** becomes the operand value.
- `aliases` (object, optional): alias -> canonical token in `toks`.
- `iprel` (bool, optional): if `true`, this operand is a **PC-relative fixup** (label reference).
- `ipofs` (int, optional): add/subtract this constant during fixup.
- `ipmul` (int, optional): multiply the relative distance by this (e.g., 4 for byte addressing).
- `endian` (string, optional): for multi-byte immediates; `"little"` supported.

Example:

```json
"reg": {
  "bits": 5,
  "toks": ["x0", "x1", "x2"],
  "aliases": {"sp": "x2"}
}
```

## `rules`

Each rule has:

- `fmt` (string): assembly pattern, e.g. `"addi ~reg,~reg,~imm12"`
- `bits` (array): bit-slices / constants describing the encoding.

### `fmt`

- Plain text becomes literal match.
- `~name` inserts an operand, binding to a variable in `vars`.

### `bits` items

`bits` is concatenated (left to right) to form a full instruction word.

Allowed entries:

1) **Binary string**: e.g. `"0010011"`

2) **Operand index**: integer `0`, `1`, ... refers to the operand position in the `fmt`.

3) **Slice object**:

```json
{"a": 1, "b": 5, "n": 7}
```

Meaning: take operand `a`, take `n` bits starting at bit `b` (LSB=0).

Example (RISC-V `sw rs2, imm(rs1)`):

```json
"bits": [
  {"a": 1, "b": 5, "n": 7},
  0,
  2,
  "010",
  {"a": 1, "b": 0, "n": 5},
  "0100011"
]
```

## Included reference specs

This step ships example specs under:

- `src/main/resources/arch/riscv.json`
- `src/main/resources/arch/r816.json`

They are intentionally minimal but valid, and you can expand them.
