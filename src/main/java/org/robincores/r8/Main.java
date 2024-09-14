package org.robincores.r8;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.robincores.r8.system.R824System;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main extends Application {
  private ExecutorService executorService;

  @Override
  public void start(Stage primaryStage) {
    try {
      // Create Canvas for graphics
      Canvas canvas = new Canvas(720, 480);

      // Initialize the system configuration (CPU, memory, etc.)
      R824System skylineSystem = new R824System(canvas);

      // Load the binary program into RAM at address 0x0000
      skylineSystem.loadProgram("system.bin", 0x0000);

      // Main JavaFX layout
      StackPane root = new StackPane(canvas);
      Scene scene = new Scene(root, canvas.getWidth(), canvas.getHeight());

      primaryStage.setTitle("R824 System");
      primaryStage.setScene(scene);
      primaryStage.show();

      // Handle application close event to stop the system and executor service
      primaryStage.setOnCloseRequest(event -> {
        skylineSystem.stop();  // Stop the emulator
        executorService.shutdownNow();  // Shutdown the ExecutorService
      });

      // ---
      // Create an ExecutorService to handle the CPU execution on a separate thread
      executorService = Executors.newSingleThreadExecutor();
      executorService.submit(skylineSystem::run);  // Run the emulator in a separate thread

    } catch (IOException e) {
      e.printStackTrace();  // Handle exception if the program file cannot be loaded
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
