package io.github.robincores.r8.cpu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class R824 extends R8Core {

    public R824(Memory memory) {
        super(memory, 0xFF_FFFF, 3, 0xFF_FFFF, 0x80_0000);
    }

    // ---

    private static final int EXIT = 0x00;
    private static final int REGISTER_DUMP = 0x01;
    private static final int MEMORY_DUMP = 0x02;
    private static final int PRINT_INT = 0x03;
    private static final int PRINT_CHAR = 0x04;
    private static final int READ_CHAR = 0x05;
    private static final int PRINT_STRING = 0x06;
    private static final int READ_STRING = 0x07;

    /**
     * Handles ebreak instruction.
     */
    @Override
    protected void handleEBreak() {
        // TODO
    }

    /**
     * Handles ecall instruction.
     */
    @Override
    protected void handleECall() {
        //System.out.println(format("ECALL 0x%02x", AReg));

        switch (AReg) {
            case EXIT -> {
                System.out.println("Exiting program...");
                System.exit(0);
            }
            case REGISTER_DUMP -> {
                System.out.println("------------");
                System.out.printf("AReg: %06x%n", AReg & 0xFF_FFFF);
                System.out.printf("BReg: %06x%n", BReg & 0xFF_FFFF);
                System.out.printf("CReg: %06x%n", CReg & 0xFF_FFFF);
                System.out.println("------------");
                for (int i = 0; i < 16; i++) {
                    System.out.printf(" @%x : %06x%n", i, wksp[i] & 0xFF_FFFF);
                }
                System.out.println("------------\n");

                // Stack shift: discard AReg
                AReg = BReg;
                BReg = CReg;
            }
            case MEMORY_DUMP -> {
                int m = (BReg - BReg % 16) & 0xFF_FFFF;
                System.out.println();
                for (int i = 0; i < 16; i++) {
                    System.out.printf("%06x", m);
                    for (int j = 0; j < 16; j++) {
                        System.out.printf(" | %02x", memory.read(m));
                        m = (m + 1) & 0xFF_FFFF;
                    }
                    System.out.println();

                    // Stack shift: discard AReg
                    AReg = BReg;
                    BReg = CReg;
                }
            }
            case PRINT_INT -> {
                System.out.print(BReg);

                // Stack shift: discard AReg
                AReg = BReg;
                BReg = CReg;
            }
            case PRINT_CHAR -> {
                System.out.print((char) (BReg & 0xFF)); // ASCII

                // Stack shift: discard AReg
                AReg = BReg;
                BReg = CReg;
            }
            case READ_CHAR -> {
                try {
                    AReg = System.in.read() & 0xFF;
                } catch (IOException e) {
                    AReg = -1; // Error reading input
                }
            }
            case PRINT_STRING -> {
                int s = BReg & 0xFF_FFFF, c;
                while ((c = memory.read(s++)) != 0) {
                    System.out.print((char) (c & 0xFF)); // ASCII
                }
                System.out.println();

                // Stack shift: discard AReg
                AReg = BReg;
                BReg = CReg;
            }
            case READ_STRING -> {
                int buffer = CReg & 0xFF_FFFF;  // Starting memory address
                int maxlen = BReg & 0xFF;  // Maximum length of string (1 byte)

                try {
                    String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
                    int length = Math.min(line.length(), maxlen - 1);  // Ensure string fits in maxlen

                    for (int i = 0; i < length; i++) {
                        memory.write(buffer + i, (byte) line.charAt(i));  // Write each character to memory
                    }
                    memory.write(buffer + length, (byte) 0);  // Null-terminate string

                    AReg = length;  // Success
                } catch (IOException e) {
                    AReg = -1;  // Error handling
                }
            }
        }
    }
}
