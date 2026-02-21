package io.github.robincores.r8.device;

import io.github.robincores.r8.bus.BusDevice;
import io.github.robincores.r8.system.InterruptSink;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keyboard MMIO (ASCII) with a small FIFO.
 *
 * Suggested map: 0xBF00–0xBF0F (16 bytes)
 *
 * 0x00 DATA   (R): pop next byte (0 if none)
 * 0x01 STATUS (R): bit0 HAS_DATA, bit1 OVERRUN
 * 0x02 CTRL   (RW): bit0 IRQ_EN (optional)
 * 0x03 CLEAR  (W): any write clears FIFO + OVERRUN
 */
public final class Keyboard implements BusDevice {

    public static final int SIZE = 0x10;

    public static final int REG_DATA   = 0x00;
    public static final int REG_STATUS = 0x01;
    public static final int REG_CTRL   = 0x02;
    public static final int REG_CLEAR  = 0x03;

    private static final int ST_HAS_DATA = 0x01;
    private static final int ST_OVERRUN  = 0x02;

    private static final int CT_IRQ_EN   = 0x01;

    private final InterruptSink sink;
    private final int irqBit;

    private final ArrayBlockingQueue<Integer> fifo = new ArrayBlockingQueue<>(64);

    private final AtomicInteger ctrl = new AtomicInteger(0);
    private final AtomicInteger status = new AtomicInteger(0);

    // FX binding state
    private final AtomicBoolean attached = new AtomicBoolean(false);
    private volatile Scene attachedScene;

    private final EventHandler<KeyEvent> onTyped = this::handleTyped;
    private final EventHandler<KeyEvent> onPressed = this::handlePressed;

    public Keyboard(InterruptSink sink, int irqBit) {
        this.sink = sink;
        this.irqBit = irqBit;
    }

    /** Push an ASCII byte (0..255). Drops if FIFO full and latches OVERRUN. */
    public void push(int ascii) {
        int v = ascii & 0xFF;
        if (!fifo.offer(v)) {
            status.getAndUpdate(s -> s | ST_OVERRUN);
            return;
        }
        if ((ctrl.get() & CT_IRQ_EN) != 0) {
            sink.raise(irqBit);
        }
    }

    // ------------------------------------------------------------
    // BusDevice
    // ------------------------------------------------------------

    @Override
    public byte read(int offset) {
        int o = offset & 0x0F;
        return (byte) switch (o) {
            case REG_DATA -> {
                Integer v = fifo.poll();
                yield (v == null) ? 0 : (v & 0xFF);
            }
            case REG_STATUS -> {
                int st = status.get();
                if (!fifo.isEmpty()) st |= ST_HAS_DATA;
                yield st & 0xFF;
            }
            case REG_CTRL -> ctrl.get() & 0xFF;
            default -> 0;
        };
    }

    @Override
    public void write(int offset, byte value) {
        int o = offset & 0x0F;
        int v = Byte.toUnsignedInt(value);

        switch (o) {
            case REG_CTRL -> ctrl.set(v & 0xFF);
            case REG_CLEAR -> {
                fifo.clear();
                status.set(0);
            }
            default -> { /* ignored */ }
        }
    }

    @Override
    public int size() {
        return SIZE;
    }

    // ------------------------------------------------------------
    // JavaFX binding
    // ------------------------------------------------------------

    /** Attach via Stage (waits for a Scene if needed). */
    public void attach(Stage stage) {
        Scene scene = stage.getScene();
        if (scene != null) attach(scene);
        else stage.sceneProperty().addListener((obs, oldS, newS) -> {
            if (newS != null) attach(newS);
        });
    }

    /** Attach key handlers to a Scene (recommended). Safe on scene changes. */
    public void attach(Scene scene) {
        // If we were attached to a different scene, detach first
        Scene prev = attachedScene;
        if (prev != null && prev != scene) {
            detach(prev);
            attached.set(false);
        }

        if (!attached.compareAndSet(false, true)) return;

        attachedScene = scene;
        scene.addEventFilter(KeyEvent.KEY_TYPED, onTyped);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, onPressed);
    }

    /** Detach from current scene (if attached). */
    public void detach() {
        Scene s = attachedScene;
        if (s != null) detach(s);
        attachedScene = null;
        attached.set(false);
    }

    private void detach(Scene scene) {
        scene.removeEventFilter(KeyEvent.KEY_TYPED, onTyped);
        scene.removeEventFilter(KeyEvent.KEY_PRESSED, onPressed);
    }

    // ------------------------------------------------------------
    // FX handlers
    // ------------------------------------------------------------

    private void handleTyped(KeyEvent e) {
        String s = e.getCharacter();
        if (s == null || s.isEmpty()) return;

        // Avoid double-inserting Enter (handled in KEY_PRESSED)
        if ("\r".equals(s) || "\n".equals(s)) return;

        // Push each char (8-bit). Non-ASCII becomes '?'
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // Basic ASCII printable (skip DEL). Control chars ignored here.
            if (c >= 0x20 && c != 0x7F) {
                push((c <= 0xFF) ? c : '?');
                e.consume();
            }
        }
    }

    private void handlePressed(KeyEvent e) {
        KeyCode code = e.getCode();

        // Ctrl+A..Z => 1..26
        if (e.isControlDown() && code.isLetterKey()) {
            String name = code.getName(); // usually "A".."Z"
            if (name != null && name.length() == 1) {
                char ch = name.charAt(0);
                if (ch >= 'A' && ch <= 'Z') {
                    push((ch - 'A') + 1);
                    e.consume();
                    return;
                }
            }
        }

        switch (code) {
            case ENTER -> { push('\r'); e.consume(); }
            case BACK_SPACE -> { push('\b'); e.consume(); }
            case TAB -> { push('\t'); e.consume(); }        // IMPORTANT: prevents focus traversal
            case ESCAPE -> { push(0x1B); e.consume(); }
            case DELETE -> { push(0x7F); e.consume(); }
            default -> { /* ignore for now */ }
        }
    }
}