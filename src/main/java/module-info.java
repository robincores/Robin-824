module org.bytecraft.skyline {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires org.antlr.antlr4.runtime;
    requires java.desktop;

    opens io.github.robincores.r8 to javafx.fxml;
    exports io.github.robincores.r8;
    exports io.github.robincores.r8.device;
}