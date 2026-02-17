package io.github.robincores.r8.system;

import java.io.IOException;

public interface R8CoreSystem {

    void loadProgram(String filePath, int startAddress) throws IOException;

    void run();

    void stop();
}
