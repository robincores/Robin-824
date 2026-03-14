### R8 Family CPU Specification

The R8 family is a stack-based CPU architecture designed for performance and flexibility across a range of systems, from embedded devices to more complex computing platforms. It draws inspiration from the Transputer's stack-based architecture, the JVM's operand stack and local variables, and the RISC-V instruction set.

### 1. **Core Components**

#### 1.1 **Registers and Operand Stack**

- **Operand Stack:**
    - The R8 CPU family uses a three-level operand stack with **AReg**, **BReg**, and **CReg** as the top three elements of the stack, respectively.
    - **AReg** holds the top value of the stack.
    - **BReg** holds the second value.
    - **CReg** holds the third value.
    - All computations operate on **AReg**, **BReg**, and **CReg**.
    - When the stack grows beyond these three registers, the overflow is lost.
    - The operand stack is independent of memory; data is transferred between memory and the top of the stack using load/store instructions.

- **Workspace Registers (W0-W15):**
    - These registers are used as local variables and parameters, similar to the JVM.
    - **W15 (SPtr)** serves as the stack pointer for managing control flow, subroutine calls, and returns, similar to the RISC-V x2 register.

- **Instruction Pointer (IPtr):**
    - **IPtr** points to the address of the next instruction to be executed.

### 2. **Operand Stack Behavior**

In the R8 architecture, almost all instructions interact with the operand stack. The top values of the stack are stored in **AReg**, **BReg**, and **CReg**. The operand stack is primarily managed through instructions like **DUP**, **SWAP**, **POP1**, and **POP2**, allowing flexible manipulation of the top of the stack.

#### Stack Manipulation Instructions:

- **DUP (0x08):** Duplicates the top value of the stack.
    - Result: **AReg = AReg**, **BReg = AReg**, **CReg = BReg**.

- **SWAP (0x0C):** Swaps the top two values on the stack.
    - Result: **AReg = BReg**, **BReg = AReg**, **CReg remains unchanged**.

- **POP1 (0x78):** Removes the top value from the stack and shifts the next two values up.
    - Result: **AReg = BReg**, **BReg = CReg**, **CReg is undefined or loaded from memory**.

- **POP2 (0x7C):** Pops the top two values from the stack.
    - Result: **AReg = CReg**, **BReg and CReg are discarded or reloaded from memory**.

These instructions manage the top elements of the operand stack, allowing flexible manipulation of values without complex memory operations.

### 3. **Arithmetic and Logical Instructions**

Arithmetic and logical operations are performed on the operand stack, specifically using **AReg** and **BReg**. After the operation, the result is placed in **AReg**, and **BReg** is shifted to **CReg**.

#### Arithmetic Operations:
- **ADD (0x10):** Adds **BReg** to **AReg**.
    - Result: **AReg = AReg + BReg**, **BReg = CReg**, **CReg unchanged**.

- **SUB (0x14):** Subtracts **AReg** from **BReg**.
    - Result: **AReg = BReg - AReg**, **BReg = CReg**.

- **MUL (0x18):** Multiplies **BReg** by **AReg**.
    - Result: **AReg = BReg * AReg**, **BReg = CReg**.

- **DIV (0x1C):** Divides **BReg** by **AReg**, placing the result in **AReg**.
    - Result: **AReg = BReg / AReg**, **BReg = CReg**, **Division by zero is undefined**.

#### Logical Operations:
- **AND (0x24):** Bitwise AND between **AReg** and **BReg**.
    - Result: **AReg = BReg & AReg**, **BReg = CReg**.

- **OR (0x28):** Bitwise OR between **AReg** and **BReg**.
    - Result: **AReg = BReg | AReg**, **BReg = CReg**.

- **XOR (0x2C):** Bitwise XOR between **AReg** and **BReg**.
    - Result: **AReg = BReg ^ AReg**, **BReg = CReg**.

### 4. **Comparison and Branching Instructions**

The comparison instructions evaluate values in **AReg** and **BReg**, pushing the result (1 or 0) onto the operand stack. Conditional branch instructions modify the **IPtr** based on the comparison results.

#### Comparison Instructions:
- **SLT (0x30):** Set Less Than.
    - Result: **AReg = (BReg < AReg) ? 1 : 0**, **BReg = CReg**.

- **SLTU (0x34):** Set Less Than Unsigned.
    - Result: **AReg = (unsigned)(BReg < AReg) ? 1 : 0**, **BReg = CReg**.

#### Branching Instructions:
- **BEQ (0x42):** Branch if Equal.
    - Compares **AReg** and **BReg**, and if they are equal, it updates **IPtr**.
    - Result: **IPtr = IPtr + k** (if **AReg == BReg**).

- **BNE (0x46):** Branch if Not Equal.
    - Updates **IPtr** if **AReg != BReg**.

- **BLT (0x52):** Branch if Less Than.
    - Jumps if **BReg** is less than **AReg**.

- **BGE (0x5A):** Branch if Greater or Equal.
    - Jumps if **BReg** is greater than or equal to **AReg**.

### 5. **Control Flow and Function Calls**

Subroutine calls and jumps are managed similarly to RISC-V, using **SPtr** to track function calls and returns.

- **J (0x62):** Unconditional jump to a relative address.
    - **IPtr = IPtr + k**.

- **JAL (0x66):** Jump and Link, used for function calls. It saves the return address in **AReg** and jumps to the target.
    - **AReg = IPtr + 1**, **IPtr = IPtr + k**.

- **JR (0x6A):** Jump to the address in **AReg**.
    - **IPtr = AReg**, **AReg = BReg**, **BReg = CReg**.

- **JALR (0x6E):** Jump and Link Register. It jumps to **AReg**, storing the return address in **AReg**.
    - **AReg = IPtr + 1**, **IPtr = AReg**.

#### Function Call Flow:
1. The **JAL** instruction is used to make a subroutine call.
2. The return address (next instruction) is saved in **AReg**.
3. The **IPtr** is updated to the subroutine address.
4. To return, the **JALR** instruction restores the return address from **AReg**, allowing the program to resume execution from the original location.

### 6. **Load/Store Operations**

Memory access is handled by load/store instructions that use the operand stack and workspace registers.

- **LD (0x88):** Load a value from memory.
    - **AReg = [AReg]** (loads the value at the address in **AReg**).

- **ST (0xF8):** Store a value in memory.
    - **[BReg] = AReg**, **AReg = CReg** (stores the value in **AReg** at the address in **BReg**).

### 7. **Workspace Operations**

The **LDL** and **STL** instructions load from and store to workspace registers, respectively.

- **LDL rN (0x03-0x3F):** Load from a workspace register.
    - **AReg = WN**, **BReg = AReg**, **CReg = BReg**.

- **STL rN (0x43-0x7F):** Store to a workspace register.
    - **WN = AReg**, **AReg = BReg**, **BReg = CReg**.

### 8. **Memory and Addressing**

Each model in the R8 family supports different memory ranges based on the address bus size:
- **R8 Model:** 16-bit address bus, up to 64KB of memory.
- **R824 Model:** 24-bit address bus, up to 16MB of memory.
- **R832 Model:** 32-bit address bus, up to 4GB of memory.

---

### 9. **Instruction Encoding**

Most instructions operate on the operand stack and fit within an 8-bit opcode format. The R8 family uses an 8-bit fixed encoding for simplicity in decoding and execution. Some instructions require immediate parameters, which vary in size depending on the model:

- **8-bit immediate values** for all models.
- **16-bit

immediate values** for R8 (due to the 16-bit address bus and internal registers).
- **24-bit immediate values** for R824 (due to the 24-bit address bus and internal registers).
- **32-bit immediate values** for R832 (due to the 32-bit address bus and internal registers).

There are always two types of immediate values across all models:
1. **8-bit immediate values** (used by most instructions).
2. Larger immediate values (16-bit, 24-bit, 32-bit, etc.), depending on the architecture’s address bus width.

Instruction lengths are typically one byte for the opcode, with additional bytes added for immediate parameters based on the architecture's memory addressing.

---

### 10. **Conclusion**

The R8 family offers a powerful stack-based architecture that simplifies computation with the operand stack while providing flexibility with workspace registers and efficient memory management. Its design allows for scalability across different system requirements, making it suitable for both low-power and high-performance applications.