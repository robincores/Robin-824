package io.github.robincores.r8.cpu;

import io.github.robincores.r8.bus.Bus;
import java.util.Arrays;

/**
 * Minimal self-test for the R8Core interpreter.
 *
 * <p>Run with assertions enabled: {@code java -ea ...R8CoreSelfTest}</p>
 */
public final class R8CoreSelfTest {

    private static final class RamBus implements Bus {
        private final byte[] mem;

        RamBus(int size) {
            this.mem = new byte[size];
        }

        @Override public byte read8(int address) {
            return mem[address & (mem.length - 1)];
        }

        @Override public void write8(int address, byte value) {
            mem[address & (mem.length - 1)] = value;
        }

        void load(int addr, int... bytes) {
            for (int i = 0; i < bytes.length; i++) mem[(addr + i) & (mem.length - 1)] = (byte) bytes[i];
        }

        byte[] slice(int addr, int len) {
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) out[i] = mem[(addr + i) & (mem.length - 1)];
            return out;
        }
    }

    /** Tiny concrete core for R816 (16-bit word, 16-bit addr). */
    private static final class Core816 extends R8Core {
        Core816(Bus bus, boolean hasBlk) {
            super(bus, 0xFFFF, 2, 0xFFFF, 0x8000, hasBlk, MicroFusionMode.OFF);
        }
    }

    public static void main(String[] args) {
        testAddAndImmediates();
        testLoadStoreWord();
        testNintendoAddc();
        testCsrMext();
        testBlkMovb();
        System.out.println("R8CoreSelfTest: OK");
    }

    private static void testAddAndImmediates() {
        RamBus bus = new RamBus(1 << 16);
        Core816 cpu = new Core816(bus, true);

        // Program: B 1; B 2; ADD; HLT
        bus.load(0x0000,
                0x02, 0x01,   // B 1
                0x02, 0x02,   // B 2
                0x20,         // ADD
                0xFF          // HLT
        );

        cpu.executeInstruction();
        assert cpu.a() == 1;
        cpu.executeInstruction();
        assert cpu.a() == 2 && cpu.b() == 1;
        cpu.executeInstruction();
        assert cpu.a() == 3;
    }

    private static void testLoadStoreWord() {
        RamBus bus = new RamBus(1 << 16);
        Core816 cpu = new Core816(bus, true);

        // Program:
        //   B 0x10; B 0x34; ST    (store 0x34 at addr 0x10 as WORD -> 0x0034)
        //   B 0x10; LD
        //   HLT
        bus.load(0x0000,
                0x02, 0x10,   // B 0x10  (addr)
                0x02, 0x34,   // B 0x34  (val)
                0x21,         // ST
                0x02, 0x10,   // B 0x10
                0x01,         // LD
                0xFF
        );

        cpu.executeInstruction(); // addr
        cpu.executeInstruction(); // val
        cpu.executeInstruction(); // ST
        // little-endian word at 0x10 should be 0x0034 => bytes [0x34,0x00]
        byte[] stored = bus.slice(0x0010, 2);
        assert stored[0] == 0x34 && stored[1] == 0x00 : Arrays.toString(stored);

        cpu.executeInstruction(); // addr
        cpu.executeInstruction(); // LD
        assert (cpu.a() & 0xFFFF) == 0x0034;
    }

    private static void testNintendoAddc() {
        RamBus bus = new RamBus(1 << 16);
        Core816 cpu = new Core816(bus, true);

        // Program: B 0xFF; B 0x01; ADDC; HLT
        // 0x00FF + 0x0001 => sum=0x0100 carry=0
        bus.load(0x0000,
                0x02, 0xFF,
                0x02, 0x01,
                0x80,
                0xFF
        );
        cpu.executeInstruction(); // push 0xFF
        cpu.executeInstruction(); // push 0x01
        cpu.executeInstruction(); // ADDC
        assert (cpu.a() & 0xFFFF) == 0x0100;
        assert cpu.b() == 0;
    }

    private static void testCsrMext() {
        RamBus bus = new RamBus(1 << 16);
        Core816 cpu = new Core816(bus, true);

        // Program: CSRR 0x07; HLT
        bus.load(0x0000,
                0x4B, 0x07,
                0xFF
        );
        cpu.executeInstruction();
        assert (cpu.a() & 0xFFFF) == 0x0001 : "MEXT bit0 should be set when hasR8Blk=true";
    }

    private static void testBlkMovb() {
        RamBus bus = new RamBus(1 << 16);
        Core816 cpu = new Core816(bus, true);

        // Put bytes at src=0x0200
        bus.load(0x0200, 0x11, 0x22, 0x33);
        // Destination at 0x0300 should become same after MOVB.
        cpu.wksp[0] = 0x0300; // dst
        cpu.wksp[1] = 0x0200; // src
        cpu.wksp[2] = 3;      // nbytes

        // Program: ESC 0x01 (MOVB); HLT
        bus.load(0x0000,
                0x7F, 0x01,
                0xFF
        );

        // Execute until PC reaches HLT (MOVB will rewind until count hits 0)
        for (int i = 0; i < 10 && cpu.ip() != 0x0002; i++) {
            cpu.executeInstruction();
        }
        assert cpu.ip() == 0x0002 : "CPU should fall through to HLT after MOVB completes";
        byte[] out = bus.slice(0x0300, 3);
        assert Arrays.equals(out, new byte[]{0x11,0x22,0x33}) : Arrays.toString(out);
        assert cpu.wksp[2] == 0;
        assert cpu.wksp[0] == 0x0303 && cpu.wksp[1] == 0x0203;
    }
}
