package io.github.robincores.r8;

import io.github.robincores.r8.dev.AsmCompiler;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.system.FxSystem;
import io.github.robincores.r8.system.R816System;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main extends Application {

    private ExecutorService executor;
    private FxSystem system;

    @Override
    public void start(Stage stage) throws IOException {
        // ---- Parse args FIRST (do not create the system yet) ----
        List<String> raw = getParameters().getRaw();

        String prog = null;
        int loadAddr = 0x0000;
        String defaultArch = "r816";

        boolean endOfOpts = false;
        for (int i = 0; i < raw.size(); i++) {
            String a = raw.get(i);

            if (!endOfOpts && "--".equals(a)) {
                endOfOpts = true;
                continue;
            }

            if (!endOfOpts && "--addr".equals(a) && i + 1 < raw.size()) {
                loadAddr = parseIntAutoBase(raw.get(++i));
                continue;
            }

            if (!endOfOpts && "--arch".equals(a) && i + 1 < raw.size()) {
                defaultArch = raw.get(++i).trim().toLowerCase();
                continue;
            }

            if (!endOfOpts && a.startsWith("-")) {
                // Unknown option: ignore for now (keeps CLI forwards-compatible)
                continue;
            }

            if (prog == null) prog = a;
        }

        // ---- Prepare program bytes (and assemble) BEFORE system creation ----
        byte[] image = null;
        String builtIn = "basic.bin";

        if (prog != null) {
            Path p = Path.of(prog);
            if (!Files.exists(p)) {
                System.err.println("Program not found: " + p.toAbsolutePath());
                Platform.exit();
                return;
            }

            if (prog.toLowerCase().endsWith(".asm")) {
                try {
                    image = AsmCompiler.assembleToBytes(p, defaultArch);
                } catch (RuntimeException ex) {
                    System.err.println(ex.getMessage());
                    Platform.exit();
                    return;
                }
            } else {
                image = Files.readAllBytes(p);
            }
        }

        // ---- Select system (default is always R816 for now) ----
        Canvas canvas = new Canvas(); // VPU will set size
        canvas.setFocusTraversable(true);

        DisplayConfig config = R816System.displayConfig();
        system = new R816System(canvas);

        if (prog == null) {
            system.loadProgram(builtIn, 0x0000);
        } else {
            system.loadProgramBytes(image, loadAddr);
        }

        // ---- Build scene ----
        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, config.canvasWidth(), config.canvasHeight());

        stage.setTitle("R816 System");
        stage.setScene(scene);
        stage.setResizable(false);

        // Attach UI bindings (keyboard, etc.) BEFORE CPU starts
        system.attach(scene);

        // Ensure focus so KEY_TYPED/KEY_PRESSED events actually fire
        stage.setOnShown(e -> canvas.requestFocus());

        // ---- Run CPU on background thread ----
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "r8-cpu");
            t.setDaemon(true);
            return t;
        });

        stage.setOnCloseRequest(e -> {
            if (system != null) system.stop();
            if (executor != null) executor.shutdownNow();
        });

        stage.show();
        executor.submit(system::run);
    }

    @Override
    public void stop() {
        if (system != null) system.stop();
        if (executor != null) executor.shutdownNow();
    }

    private static int parseIntAutoBase(String s) {
        String t = s.trim().toLowerCase();
        if (t.startsWith("0x")) return Integer.parseUnsignedInt(t.substring(2), 16);
        if (t.startsWith("$"))  return Integer.parseUnsignedInt(t.substring(1), 16);
        return Integer.parseInt(t, 10);
    }

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}