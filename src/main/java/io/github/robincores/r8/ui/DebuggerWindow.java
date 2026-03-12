package io.github.robincores.r8.ui;

import io.github.robincores.r8.debug.Debugger;
import io.github.robincores.r8.system.AbstractSystem;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.net.URL;

/**
 * Lazy-loaded JavaFX window for the R8 debugger.
 *
 * Expects:
 *   - FXML at: /io/github/robincores/r8/ui/DebuggerDialog.fxml
 *   - Controller: io.github.robincores.r8.ui.DebuggerController with init(AbstractSystem, Debugger)
 */
public final class DebuggerWindow {

    private static final String FXML_PATH = "/io/github/robincores/r8/ui/DebuggerDialog.fxml";

    private final AbstractSystem system;
    private final Debugger debugger;

    private Stage stage;

    public DebuggerWindow(AbstractSystem system, Debugger debugger) {
        this.system = system;
        this.debugger = debugger;
    }

    /** Show (or focus) the debugger window. Safe to call multiple times. */
    public void show() {
        if (Platform.isFxApplicationThread()) {
            ensureStage();
            if (!stage.isShowing()) stage.show();
            stage.toFront();
            stage.requestFocus();
        } else {
            Platform.runLater(this::show);
        }
    }

    /** Hide the debugger window (does not dispose). */
    public void hide() {
        if (stage == null) return;
        if (Platform.isFxApplicationThread()) stage.hide();
        else Platform.runLater(() -> { if (stage != null) stage.hide(); });
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** Optional: dispose resources. After close(), show() will recreate the stage. */
    public void close() {
        if (stage == null) return;
        if (Platform.isFxApplicationThread()) {
            stage.close();
            stage = null;
        } else {
            Platform.runLater(this::close);
        }
    }

    private void ensureStage() {
        if (stage != null) return;

        URL fxml = DebuggerWindow.class.getResource(FXML_PATH);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource: " + FXML_PATH
                    + " (ensure it's under src/main/resources)");
        }

        try {
            FXMLLoader loader = new FXMLLoader(fxml);
            Parent root = loader.load();

            DebuggerController ctl = loader.getController();
            if (ctl == null) {
                throw new IllegalStateException("DebuggerDialog.fxml has no controller. "
                        + "Expected fx:controller=\"io.github.robincores.r8.ui.DebuggerController\"");
            }
            ctl.init(system, debugger);

            Stage s = new Stage();
            s.setTitle("R8 Debugger");
            s.setScene(new Scene(root, 980, 680));
            s.setMinWidth(720);
            s.setMinHeight(480);

            // UX: clicking the window X just hides it (so menu can reopen fast)
            s.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, e -> {
                e.consume();
                s.hide();
            });

            this.stage = s;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + FXML_PATH, e);
        }
    }
}