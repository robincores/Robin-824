package io.github.robincores.r8.system;

import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * A system that can bind to JavaFX UI events (keyboard, etc.).
 */
public interface FxSystem extends R8System {

    /**
     * Bind system inputs to a Scene (recommended).
     */
    void attach(Scene scene);

    /**
     * Convenience: bind using Stage (waits for scene if needed).
     */
    default void attach(Stage stage) {
        Scene s = stage.getScene();
        if (s != null) {
            attach(s);
            return;
        }
        stage.sceneProperty().addListener((obs, oldS, newS) -> {
            if (newS != null) attach(newS);
        });
    }

    /**
     * Called on the JavaFX Application Thread once per JavaFX pulse (typically ~60Hz).
     * Systems with video output should present the latest completed frame here.
     *
     * <p>Default is no-op.</p>
     */
    default void fxPulse() { }
}