module org.bytecraft.skyline {
  requires javafx.controls;
  requires javafx.fxml;
  requires com.google.gson;


  opens org.robincores.r8 to javafx.fxml;
  exports org.robincores.r8;
}