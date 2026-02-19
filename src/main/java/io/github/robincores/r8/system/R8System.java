package io.github.robincores.r8.system;

import java.io.IOException;

public interface R8System {

    void loadProgram(String filePath, int startAddress) throws IOException;

    /** Convenience: load a raw program image directly (no filesystem). */
    void loadProgramBytes(byte[] data, int startAddress);

    void run();

    void stop();
}
