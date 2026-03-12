package io.github.robincores.r8;

import io.github.robincores.r8.dev.AsmCompiler;
import io.github.robincores.r8.debug.Debugger;
import io.github.robincores.r8.device.DisplayConfig;
import io.github.robincores.r8.system.AbstractSystem;
import io.github.robincores.r8.system.FxSystem;
import io.github.robincores.r8.system.R816System;
import io.github.robincores.r8.ui.DebuggerWindow;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Main extends Application {

    private static final String DEFAULT_ARCH = "r816";
    private static final String DEFAULT_BUILTIN = "basic.bin";

    private ExecutorService executor;
    private FxSystem system;
    private AnimationTimer animator;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @Override
    public void start(Stage stage) throws IOException {
        // ---- Parse args ----
        List<String> raw = getParameters().getRaw();

        String prog = null;
        String builtinPath = DEFAULT_BUILTIN;
        int loadAddr = 0x0000;
        String arch = DEFAULT_ARCH;
        int cpuMul = R816System.CPU_MUL_DEFAULT;

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
                arch = raw.get(++i).trim().toLowerCase();
                continue;
            }

            if (!endOfOpts && "--cpu-mul".equals(a) && i + 1 < raw.size()) {
                cpuMul = clampCpuMul(parseIntStrict(raw.get(++i), "--cpu-mul"));
                continue;
            }

            if (!endOfOpts && "--builtin".equals(a) && i + 1 < raw.size()) {
                builtinPath = raw.get(++i).trim();
                continue;
            }

            if (!endOfOpts && a.startsWith("-")) {
                System.err.println("Unknown option: " + a);
                continue;
            }

            if (prog == null) prog = a;
        }

        // ---- Prepare program bytes ----
        final byte[] image;
        final int imageLoadAddr;

        if (prog != null) {
            Path p = Path.of(prog);
            if (!Files.exists(p)) {
                System.err.println("Program not found: " + p.toAbsolutePath());
                Platform.exit();
                return;
            }

            if (prog.toLowerCase().endsWith(".asm")) {
                try {
                    image = AsmCompiler.assembleToBytes(p, arch);
                } catch (RuntimeException ex) {
                    System.err.println(ex.getMessage());
                    Platform.exit();
                    return;
                }
            } else {
                image = Files.readAllBytes(p);
            }
            imageLoadAddr = loadAddr;
        } else {
            Path p = Path.of(builtinPath);
            if (!Files.exists(p)) {
                System.err.println("Built-in program not found: " + p.toAbsolutePath());
                System.err.println("Provide one: --builtin /path/to/basic.bin");
                Platform.exit();
                return;
            }
            image = Files.readAllBytes(p);
            imageLoadAddr = 0x0000;
        }

        // ---- Create system + load program ----
        DisplayConfig config = R816System.displayConfig();
        Canvas canvas = new Canvas(config.canvasWidth(), config.canvasHeight());
        canvas.setFocusTraversable(true);

        canvas.getGraphicsContext2D().setFill(Color.BLACK);
        canvas.getGraphicsContext2D().fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        system = new R816System(canvas, cpuMul);
        system.loadProgramBytes(image, imageLoadAddr);

        // ---- Debugger wiring (requires R816System extends AbstractSystem) ----
        AbstractSystem as = (AbstractSystem) system;
        Debugger dbg = new Debugger();
        as.setDebugger(dbg);

        AtomicReference<DebuggerWindow> dbgWinRef = new AtomicReference<>();

        // ---- Scene graph ----
        // Group wrapper makes StackPane respect the canvas's Scale transform for layout/centering.
        Group canvasWrapper = new Group(canvas);

        StackPane center = new StackPane(canvasWrapper);
        center.setStyle("-fx-background-color: black;");
        center.setMinSize(0, 0);

        MenuBar menuBar = buildMenuBar(dbg, as, dbgWinRef);

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
        root.setStyle("-fx-background-color: black;");
        root.setMinSize(0, 0);

        Scene scene = new Scene(root, config.canvasWidth(), config.canvasHeight());

        stage.setTitle(String.format(
                "R816 BUS %.4f MHz | CPU ×%d | %dx%d @ %.3f Hz",
                R816System.BUS_HZ / 1_000_000.0,
                cpuMul,
                config.width(), config.height(),
                R816System.refreshHz()
        ));
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMinWidth(160);
        stage.setMinHeight(120);

        // Attach UI bindings (keyboard, etc.) before CPU starts
        system.attach(scene);

        // ---- Canvas scaling: uniform fit within CENTER area (below menu) ----
        installCanvasScaling(center, canvas);

        // ---- FX pulse: present latest VPU frame ----
        animator = new AnimationTimer() {
            @Override public void handle(long now) {
                FxSystem s = system;
                if (s != null) s.fxPulse();
            }
        };
        animator.start();

        // ---- CPU thread ----
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "r8-cpu");
            t.setDaemon(true);
            return t;
        });

        stage.setOnCloseRequest(e -> shutdown());

        stage.setOnShown(e -> {
            canvas.requestFocus();
            FxSystem s = system;
            if (s != null) executor.submit(s::run);
        });

        stage.show();
    }

    private static MenuBar buildMenuBar(
            Debugger dbg,
            AbstractSystem system,
            AtomicReference<DebuggerWindow> dbgWinRef
    ) {
        MenuBar mb = new MenuBar();

        // ---- File ----
        Menu file = new Menu("File");
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        file.getItems().add(exit);

        // ---- Debug ----
        Menu debug = new Menu("Debug");

        MenuItem openDbg = new MenuItem("Debugger...");
        openDbg.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN));
        openDbg.setOnAction(e -> {
            DebuggerWindow w = dbgWinRef.get();
            if (w == null) {
                w = new DebuggerWindow(system, dbg);
                dbgWinRef.set(w);
            }
            w.show();
        });

        MenuItem runPause = new MenuItem("Run / Pause");
        runPause.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        runPause.setOnAction(e -> {
            if (dbg.mode() == Debugger.Mode.PAUSE) dbg.resume();
            else dbg.pause("pause");
        });

        MenuItem step = new MenuItem("Step");
        step.setAccelerator(new KeyCodeCombination(KeyCode.F10));
        step.setOnAction(e -> dbg.step());

        MenuItem toggleBp = new MenuItem("Toggle Breakpoint @ PC");
        toggleBp.setAccelerator(new KeyCodeCombination(KeyCode.F9));
        toggleBp.setOnAction(e -> {
            int pc = system.cpuRef().debuggerStopPC(); // uses MEPC when sitting in MTVEC handler
            boolean on = dbg.toggleBreakpoint(pc);
            System.out.println((on ? "BP set" : "BP cleared") + " @0x" + hexAddr(pc, system.cpuRef().addrMask()));
        });

        MenuItem clearBps = new MenuItem("Clear Breakpoints");
        clearBps.setOnAction(e -> dbg.clearBreakpoints());

        debug.getItems().addAll(
                openDbg,
                new SeparatorMenuItem(),
                runPause,
                step,
                toggleBp,
                new SeparatorMenuItem(),
                clearBps
        );

        mb.getMenus().addAll(file, debug);
        return mb;
    }

    private static String hexAddr(int v, int addrMask) {
        int bits = 32 - Integer.numberOfLeadingZeros(addrMask);
        int digits = Math.max(1, Math.min(8, (bits + 3) / 4));
        return String.format("%0" + digits + "X", v & addrMask);
    }

    /**
     * Pixel-perfect integer canvas scaling (letterbox / pillarbox).
     *
     * <p>The window resizes freely — no fighting the window manager.
     * The canvas scales uniformly to the largest size that fits within the
     * given viewport bounds (center area), and StackPane centering keeps it centered.
     * Any excess space shows the viewport's black background.</p>
     */
    private static void installCanvasScaling(Region viewport, Canvas canvas) {
        final double baseW = canvas.getWidth();
        final double baseH = canvas.getHeight();

        // Pivot at (0,0) — the Group wrapper + StackPane handle centering.
        final Scale scale = new Scale(1, 1, 0, 0);
        canvas.getTransforms().setAll(scale);

        final Runnable updateScale = () -> {
            double sw = viewport.getWidth();
            double sh = viewport.getHeight();
            if (sw <= 0 || sh <= 0) return;

            double s = Math.min(sw / baseW, sh / baseH);

            // Clamp to avoid degenerate scale at very small window sizes.
            s = Math.max(s, 0.1);

            scale.setX(s);
            scale.setY(s);
        };

        viewport.widthProperty().addListener((o, a, b) -> updateScale.run());
        viewport.heightProperty().addListener((o, a, b) -> updateScale.run());
        updateScale.run();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (!stopped.compareAndSet(false, true)) return;

        FxSystem s = system;
        system = null;
        if (s != null) s.stop();

        AnimationTimer a = animator;
        animator = null;
        if (a != null) a.stop();

        ExecutorService ex = executor;
        executor = null;
        if (ex != null) ex.shutdownNow();
    }

    private static int clampCpuMul(int mul) {
        if (mul < 1) return 1;
        if (mul > 64) return 64;
        return mul;
    }

    private static int parseIntStrict(String s, String optName) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + optName + ": " + s);
        }
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