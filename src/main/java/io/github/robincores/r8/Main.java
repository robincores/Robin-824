package io.github.robincores.r8;

import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.system.R816System;
import io.github.robincores.r8.system.R8System;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application entry point.
 * <p>
 * Launches the selected system configuration, loads a demo binary,
 * and runs the CPU on a background thread while the JavaFX canvas
 * displays the VPU output.
 * </p>
 */
public class Main extends Application {

    private ExecutorService executor;
    private R8System system;

    @Override
    public void start(Stage stage) throws IOException {
        // ---- Select system ----
        Canvas canvas = new Canvas();  // VPU will set the size
        DisplayConfig config = R816System.displayConfig();

        system = new R816System(canvas);

        // Load built-in Mode X gradient demo (avoids stale system.bin files).
        //system.loadProgram("modex_gradient.bin", 0x0000);
        //system.loadProgram("modex_stripes.bin", 0x0000);
        //system.loadProgram("modex_ramp.bin", 0x0000);
        system.loadProgram("modex_quadrant_blue.bin", 0x0000);

        // ---- Build scene ----
        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, config.canvasWidth(), config.canvasHeight());

        stage.setTitle("R816 System");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        // ---- Shutdown hook ----
        stage.setOnCloseRequest(e -> {
            system.stop();
            executor.shutdownNow();
        });

        // ---- Run CPU on background thread ----
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "r8-cpu");
            t.setDaemon(true);
            return t;
        });
        executor.submit(system::run);
    }

    @Override
    public void stop() {
        if (system != null) system.stop();
        if (executor != null) executor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
