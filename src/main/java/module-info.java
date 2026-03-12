module org.bytecraft.skyline {
    requires javafx.controls;
    requires javafx.fxml;

    requires com.google.gson;
    requires org.antlr.antlr4.runtime;
    requires java.desktop;

    exports io.github.robincores.r8;
    exports io.github.robincores.r8.device;

    // demos (Application classes live here)
    exports io.github.robincores.r8.demo;
    opens   io.github.robincores.r8.demo to javafx.graphics;

    // Main.fxml (if any) in io.github.robincores.r8
    opens io.github.robincores.r8 to javafx.fxml;

    // DebuggerDialog.fxml controller lives here:
    exports io.github.robincores.r8.ui;
    opens io.github.robincores.r8.ui to javafx.fxml;
}