package io.github.robincores.r8.ui;

import io.github.robincores.r8.cpu.R8Core;
import io.github.robincores.r8.debug.ArchDisassembler;
import io.github.robincores.r8.debug.Debugger;
import io.github.robincores.r8.system.AbstractSystem;
import javafx.animation.AnimationTimer;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class DebuggerController {

    // --- Registers ---
    @FXML private Label regIP;

    @FXML private Label regA;
    @FXML private Label regB;
    @FXML private Label regC;
    @FXML private Label lblWordInfo;

    // --- Workspace ---
    @FXML private ListView<String> lstWksp;
    @FXML private CheckBox chkHideZeroWksp;

    // --- Disassembly ---
    @FXML private ListView<String> lstDisasm;

    // --- Breakpoints ---
    @FXML private TextField txtBpAddr;
    @FXML private ListView<String> lstBreakpoints;
    @FXML private Label lblBpCount;

    // --- Memory ---
    @FXML private TextField txtMemAddr;
    @FXML private CheckBox chkFollowPC;
    @FXML private CheckBox chkFollowSP;
    @FXML private Label lblMemHint;

    @FXML private TableView<MemRow> tblMem;
    @FXML private TableColumn<MemRow, String> colMemAddr;
    @FXML private TableColumn<MemRow, String> colMemHex;
    @FXML private TableColumn<MemRow, String> colMemAscii;

    private AbstractSystem system;
    private Debugger debugger;

    private ArchDisassembler dis;

    private int memBase = 0x0000;

    // Disassembly view state (to avoid fighting user scrolling)
    private int lastStopPcRendered = Integer.MIN_VALUE;
    private int lastBpHashRendered = 0;
    private boolean forceDisasmRefresh = true;

    // Last mode observed (for detecting RUN<->PAUSE transitions)
    private volatile Debugger.Mode lastMode = null;

    // highlight address within memory view (pc or sp)
    private volatile int memHighlightAddr = -1;

    private final ObservableList<MemRow> memItems = FXCollections.observableArrayList();

    // Refresh timer (FX thread)
    private final AnimationTimer refresher = new AnimationTimer() {
        private long last = 0;
        @Override public void handle(long now) {
            if (now - last < 100_000_000L) return; // ~10Hz
            last = now;
            refreshUI();
        }
    };

    /** Called by DebuggerWindow after FXMLLoader load(). */
    public void init(AbstractSystem system, Debugger debugger) {
        this.system = system;
        this.debugger = debugger;

        if (system == null || system.cpuRef() == null) {
            throw new IllegalStateException("DebuggerController.init(): system/cpu is null");
        }
        this.dis = ArchDisassembler.forCpu(system.cpuRef());

        installCellFactories();
        installMemoryTable();
        installToggles();

        refresher.start();
        refreshUI();
    }

    private void installToggles() {
        // Follow PC/SP mutually exclusive
        if (chkFollowPC != null && chkFollowSP != null) {
            chkFollowPC.selectedProperty().addListener((o, a, b) -> { if (b) chkFollowSP.setSelected(false); });
            chkFollowSP.selectedProperty().addListener((o, a, b) -> { if (b) chkFollowPC.setSelected(false); });
        }

        // Hide-zero workspace triggers refresh
        if (chkHideZeroWksp != null) {
            chkHideZeroWksp.selectedProperty().addListener((o, a, b) -> refreshUI());
        }
    }

    private void installCellFactories() {
        // Disassembly monospace + emphasize PC line + dim .db
        if (lstDisasm != null) {
            lstDisasm.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                        return;
                    }
                    setText(item);

                    boolean isPc = item.startsWith("▶");
                    boolean isDb = item.contains(" .db ");
                    String style = "-fx-font-family: 'monospace'; -fx-font-size: 12px;";
                    if (isDb) style += "-fx-opacity: 0.75;";
                    if (isPc) style += "-fx-font-weight: bold;";
                    setStyle(style);
                }
            });

            // Double-click disasm line => toggle breakpoint
            lstDisasm.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && this.debugger != null) {
                    String sel = lstDisasm.getSelectionModel().getSelectedItem();
                    Integer addr = (sel == null) ? null : parseLeadingHexAddress(sel);
                    if (addr != null) {
                        debugger.toggleBreakpoint(addr);
                        refreshBpList();
                        forceDisasmRefresh = true;
                        int mask = system.cpuRef().addrMask();
                        refreshDisasm(system.cpuRef().debuggerStopPC() & mask, mask);
                    }
                }
            });
        }

        // Workspace: highlight SP line
        if (lstWksp != null) {
            lstWksp.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                        return;
                    }
                    setText(item);
                    boolean isSp = item.startsWith("->");
                    String style = "-fx-font-family: 'monospace'; -fx-font-size: 12px;";
                    if (isSp) style += "-fx-font-weight: bold;";
                    setStyle(style);
                }
            });
        }

        // Breakpoints: monospace + double click remove
        if (lstBreakpoints != null) {
            lstBreakpoints.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                        return;
                    }
                    setText(item);
                    setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px;");
                }
            });

            lstBreakpoints.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && debugger != null) {
                    String sel = lstBreakpoints.getSelectionModel().getSelectedItem();
                    Integer a = parseBreakpointLineAddress(sel);
                    if (a != null) {
                        debugger.removeBreakpoint(a);
                        refreshBpList();
                        forceDisasmRefresh = true; // refresh ● markers
                    }
                }
            });
        }
    }

    private void installMemoryTable() {
        if (tblMem == null) return;

        tblMem.setItems(memItems);
        tblMem.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        if (colMemAddr != null) colMemAddr.setCellValueFactory(c -> c.getValue().addr);
        if (colMemHex != null) colMemHex.setCellValueFactory(c -> c.getValue().hex);
        if (colMemAscii != null) colMemAscii.setCellValueFactory(c -> c.getValue().ascii);

        // Row highlight for PC/SP location
        tblMem.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(MemRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null || system == null || system.cpuRef() == null) {
                    setStyle("");
                    return;
                }
                int base = row.baseAddr;
                int hi = memHighlightAddr;
                boolean hit = (hi >= 0) && (hi >= base) && (hi < (base + 16));
                if (hit) {
                    setStyle("-fx-background-color: rgba(120, 180, 255, 0.25);");
                } else {
                    setStyle("");
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Toolbar actions
    // ------------------------------------------------------------------

    @FXML private void onRun()   { if (debugger != null) debugger.resume(); }
    @FXML private void onPause() { if (debugger != null) debugger.pause("pause"); }
    @FXML private void onStep()  { if (debugger != null) debugger.step(); }

    /** Run until the currently selected disassembly address (temporary breakpoint). */
    @FXML private void onRunToCursor() {
        if (debugger == null || system == null || system.cpuRef() == null || lstDisasm == null) return;
        String sel = lstDisasm.getSelectionModel().getSelectedItem();
        Integer addr = (sel == null) ? null : parseLeadingHexAddress(sel);
        if (addr == null) return;
        int mask = system.cpuRef().addrMask();
        debugger.runTo(addr & mask);
        forceDisasmRefresh = false;
    }

    /** Jump disassembly back to current stop PC. */
    @FXML private void onDisasmToPC() {
        if (system == null || system.cpuRef() == null) return;
        int mask = system.cpuRef().addrMask();
        int stopPc = system.cpuRef().debuggerStopPC() & mask;
        forceDisasmRefresh = true;
        refreshDisasm(stopPc, mask);
    }

    @FXML private void onAddBreakpoint() {
        if (debugger == null || system == null || system.cpuRef() == null) return;
        int a = parseHexOrNeg(txtBpAddr.getText());
        if (a >= 0) debugger.addBreakpoint(a & system.cpuRef().addrMask());
        refreshBpList();
        forceDisasmRefresh = true;
        refreshDisasm(system.cpuRef().debuggerStopPC() & system.cpuRef().addrMask(), system.cpuRef().addrMask());
    }

    @FXML private void onRemoveBreakpoint() {
        if (debugger == null) return;
        String sel = lstBreakpoints.getSelectionModel().getSelectedItem();
        Integer a = parseBreakpointLineAddress(sel);
        if (a != null) debugger.removeBreakpoint(a);
        refreshBpList();
        forceDisasmRefresh = true;
    }

    @FXML private void onClearBreakpoints() {
        if (debugger == null) return;
        debugger.clearBreakpoints();
        refreshBpList();
        forceDisasmRefresh = true;
    }

    /** Jump memory/disasm to selected breakpoint address. */
    @FXML private void onBpGo() {
        if (system == null || system.cpuRef() == null) return;
        String sel = lstBreakpoints.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        Integer a0 = parseBreakpointLineAddress(sel);
        if (a0 == null) return;
        int a = a0;

        int mask = system.cpuRef().addrMask();
        memBase = a & mask;
        if (txtMemAddr != null) txtMemAddr.setText(hexAddr(memBase, mask));
        if (chkFollowPC != null) chkFollowPC.setSelected(false);
        if (chkFollowSP != null) chkFollowSP.setSelected(false);

        refreshMem();
        forceDisasmRefresh = true;
        refreshDisasm(a & mask, mask);
    }

    @FXML private void onMemFromIP() {
        if (system == null || system.cpuRef() == null) return;
        if (chkFollowPC != null) chkFollowPC.setSelected(true);
        if (chkFollowSP != null) chkFollowSP.setSelected(false);

        int mask = system.cpuRef().addrMask();
        memBase = system.cpuRef().debuggerStopPC() & mask;
        if (txtMemAddr != null) txtMemAddr.setText(hexAddr(memBase, mask));
        refreshMem();
    }

    @FXML private void onMemFromSP() {
        if (system == null || system.cpuRef() == null) return;
        if (chkFollowSP != null) chkFollowSP.setSelected(true);
        if (chkFollowPC != null) chkFollowPC.setSelected(false);

        int mask = system.cpuRef().addrMask();
        memBase = system.cpuRef().sp() & mask;
        if (txtMemAddr != null) txtMemAddr.setText(hexAddr(memBase, mask));
        refreshMem();
    }

    @FXML private void onMemGo() {
        if (system == null || system.cpuRef() == null) return;

        // Manual go breaks follow mode
        if (chkFollowPC != null) chkFollowPC.setSelected(false);
        if (chkFollowSP != null) chkFollowSP.setSelected(false);

        int mask = system.cpuRef().addrMask();
        int a = parseHexOrNeg(txtMemAddr.getText());
        if (a >= 0) {
            memBase = a & mask;
            refreshMem();
        }
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    private void refreshUI() {
    if (system == null || debugger == null) return;

    var cpu = system.cpuRef();
    if (cpu == null) return;

    var snap = cpu.snapshot();
    int addrMask = snap.addrMask();

    int ip = snap.ip() & addrMask;
    int sp = snap.sp() & addrMask;

    // Freeze heavy UI updates while running (prevents scroll/selection fighting).
    Debugger.Mode mode = debugger.mode();
    Debugger.Mode prev = lastMode;
    if (prev != mode) {
        // On transition into PAUSE/STEP, force a full refresh so views re-sync.
        if (mode != Debugger.Mode.RUN) {
            forceDisasmRefresh = true;
            lastStopPcRendered = Integer.MIN_VALUE;
        }
        lastMode = mode;
    }

    if (mode == Debugger.Mode.RUN) {
        // Keep a minimal live indicator only.
        if (regIP != null) regIP.setText(hexAddr(ip, addrMask));
        if (lblMemHint != null) lblMemHint.setText("");
        memHighlightAddr = -1;
        return;
    }

    int stopPc = cpu.debuggerStopPC() & addrMask;

    if (regIP != null) regIP.setText(hexAddr(ip, addrMask));

    // Display word info and regs as hex + signed
    if (lblWordInfo != null) {
        int wordBits = 32 - Integer.numberOfLeadingZeros(snap.wordMask());
        lblWordInfo.setText(wordBits + "-bit");
    }

    if (regA != null) regA.setText(fmtWordWithSigned(snap.a(), snap.wordMask()));
    if (regB != null) regB.setText(fmtWordWithSigned(snap.b(), snap.wordMask()));
    if (regC != null) regC.setText(fmtWordWithSigned(snap.c(), snap.wordMask()));

    // Workspace regs
    refreshWorkspace(cpu, addrMask);

    refreshDisasm(stopPc, addrMask);
    refreshBpList();

    // Memory updates while paused/stepping
    if (chkFollowSP != null && chkFollowSP.isSelected()) {
        memBase = sp & addrMask;
        memHighlightAddr = sp & addrMask;
        if (txtMemAddr != null) txtMemAddr.setText(hexAddr(memBase, addrMask));
        if (lblMemHint != null) lblMemHint.setText("SP");
    } else if (chkFollowPC != null && chkFollowPC.isSelected()) {
        memBase = stopPc & addrMask;
        memHighlightAddr = stopPc & addrMask;
        if (txtMemAddr != null) txtMemAddr.setText(hexAddr(memBase, addrMask));
        if (lblMemHint != null) lblMemHint.setText("PC");
    } else {
        memHighlightAddr = -1;
        if (lblMemHint != null) lblMemHint.setText("");
    }
    refreshMem();
    }

    private void refreshWorkspace(R8Core cpu, int addrMask) {
        if (lstWksp == null) return;

        boolean hideZero = chkHideZeroWksp != null && chkHideZeroWksp.isSelected();

        List<String> w = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> {
                    String name = (i == 15) ? "sp" : ("w" + i);
                    int v = cpu.wksp(i) & addrMask;
                    if (hideZero && v == 0 && i != 15) return null; // keep sp always
                    String hv = hexAddr(v, addrMask);
                    return (i == 15)
                            ? String.format("-> %-3s = 0x%s", name, hv)
                            : String.format("   %-3s = 0x%s", name, hv);
                })
                .filter(s -> s != null)
                .collect(Collectors.toList());

        lstWksp.getItems().setAll(w);
    }

    private void refreshDisasm(int startPc, int addrMask) {
        var bus = system.busRef();
        if (bus == null || dis == null || lstDisasm == null) return;

        int stopPc = startPc & addrMask;

        // Only rebuild the list when PC moved (while paused/stepping) or breakpoints changed.
        // This prevents the refresher from snapping the scroll position back.
        int bpHash = (debugger != null) ? debugger.breakpointsView().hashCode() : 0;
        if (!forceDisasmRefresh && stopPc == lastStopPcRendered && bpHash == lastBpHashRendered) {
            return;
        }

        // Preserve selection (by address) if user clicked somewhere else.
        Integer selectedAddr = null;
        String sel = lstDisasm.getSelectionModel().getSelectedItem();
        if (sel != null) selectedAddr = parseLeadingHexAddress(sel);

        Set<Integer> bps = (debugger != null) ? debugger.breakpointsView() : Set.of();

        List<ArchDisassembler.Decoded> lines = dis.decodeMany(bus, stopPc, 60, addrMask);
        List<String> txt = lines.stream()
                .map(d -> {
                    int a = d.addr() & addrMask;
                    boolean isPc = a == stopPc;
                    boolean isBp = bps.contains(a & addrMask);

                    String pcMark = isPc ? "▶" : " ";
                    String bpMark = isBp ? "●" : " ";
                    String prefix = pcMark + bpMark + " ";

                    return String.format("%s%s: %-11s %s",
                            prefix,
                            hexAddr(a, addrMask),
                            bytesToHex(d.bytes()),
                            d.text());
                })
                .collect(Collectors.toList());

        lstDisasm.getItems().setAll(txt);

        // Restore selection if possible
        if (selectedAddr != null) {
            int target = selectedAddr & addrMask;
            int idx = -1;
            for (int i = 0; i < txt.size(); i++) {
                Integer a = parseLeadingHexAddress(txt.get(i));
                if (a != null && (a & addrMask) == target) { idx = i; break; }
            }
            if (idx >= 0) {
                lstDisasm.getSelectionModel().select(idx);
            } else {
                lstDisasm.getSelectionModel().select(0);
            }
        } else {
            lstDisasm.getSelectionModel().select(0);
        }

        lastStopPcRendered = stopPc;
        lastBpHashRendered = bpHash;
        forceDisasmRefresh = false;
    }

    private void refreshBpList() {
        if (debugger == null || system == null || system.cpuRef() == null || lstBreakpoints == null) return;

        int mask = system.cpuRef().addrMask();
        Set<Integer> raw = debugger.breakpointsView();

        var bus = system.busRef();
        boolean canDecode = (bus != null && dis != null);
        int stopPc = system.cpuRef().debuggerStopPC() & mask;

        List<String> bps = raw.stream()
                .map(a -> a & mask)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .map(a -> {
                    String pcMark = (a == stopPc) ? "▶ " : "  ";
                    if (!canDecode) {
                        return pcMark + "0x" + hexAddr(a, mask);
                    }
                    ArchDisassembler.Decoded d = dis.decodeAt(bus, a, mask);
                    String bytes = bytesToHex(d.bytes());
                    return String.format("%s0x%s  %-11s %s",
                            pcMark,
                            hexAddr(a, mask),
                            bytes,
                            d.text());
                })
                .collect(Collectors.toList());

        lstBreakpoints.getItems().setAll(bps);

        if (lblBpCount != null) {
            lblBpCount.setText(bps.isEmpty() ? "" : (bps.size() + " bp"));
        }
    }

    private void refreshMem() {
        if (system == null || system.cpuRef() == null || tblMem == null) return;
        var bus = system.busRef();
        if (bus == null) return;

        int mask = system.cpuRef().addrMask();
        int base = memBase & mask;

        // Align to 16-byte row
        base = base & ~0x0F;
        memBase = base;

        memItems.setAll(buildMemRows(bus, base, 256, mask));

        tblMem.refresh();
    }

    // ------------------------------------------------------------------
    // Memory model
    // ------------------------------------------------------------------

    public static final class MemRow {
        final int baseAddr;
        final SimpleStringProperty addr;
        final SimpleStringProperty hex;
        final SimpleStringProperty ascii;

        MemRow(int baseAddr, String addr, String hex, String ascii) {
            this.baseAddr = baseAddr;
            this.addr = new SimpleStringProperty(addr);
            this.hex = new SimpleStringProperty(hex);
            this.ascii = new SimpleStringProperty(ascii);
        }
    }

    private static List<MemRow> buildMemRows(io.github.robincores.r8.bus.Bus bus, int base, int len, int mask) {
        int rows = Math.max(1, len / 16);
        java.util.ArrayList<MemRow> out = new java.util.ArrayList<>(rows);

        for (int i = 0; i < len; i += 16) {
            int rowAddr = (base + i) & mask;

            StringBuilder hex = new StringBuilder(16 * 3 + 4);
            StringBuilder ascii = new StringBuilder(16);

            for (int j = 0; j < 16; j++) {
                int a = (rowAddr + j) & mask;
                int v = bus.read8(a) & 0xFF;
                hex.append(String.format("%02X ", v));
                ascii.append((v >= 32 && v < 127) ? (char) v : '.');
            }

            out.add(new MemRow(rowAddr,
                    hexAddr(rowAddr, mask),
                    hex.toString().trim(),
                    "|" + ascii + "|"));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private static String hexAddr(int v, int addrMask) {
        int bits = 32 - Integer.numberOfLeadingZeros(addrMask);
        int digits = Math.max(1, Math.min(8, (bits + 3) / 4));
        return String.format("%0" + digits + "X", v & addrMask);
    }

    private static String hexWord(int v, int wordMask) {
        int bits = 32 - Integer.numberOfLeadingZeros(wordMask);
        int digits = Math.max(1, Math.min(8, (bits + 3) / 4));
        return String.format("%0" + digits + "X", v & wordMask);
    }

    private static int signExtendWord(int v, int wordMask) {
        v &= wordMask;
        int signBit = (wordMask + 1) >>> 1;
        if ((v & signBit) != 0) {
            return v | ~wordMask;
        }
        return v;
    }

    private static String fmtWordWithSigned(int v, int wordMask) {
        int signed = signExtendWord(v, wordMask);
        return String.format("%s  (%d)", hexWord(v, wordMask), signed);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x & 0xFF));
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static int parseHexOrNeg(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase();
        if (t.isEmpty()) return -1;

        try {
            if (t.startsWith("0x")) return Integer.parseUnsignedInt(t.substring(2), 16);
            if (t.startsWith("$"))  return Integer.parseUnsignedInt(t.substring(1), 16);
            return Integer.parseUnsignedInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Extracts the last contiguous hex token right before the first ':' (supports "▶● 0003: ..."). */
    private static Integer parseLeadingHexAddress(String line) {
        if (line == null) return null;
        int colon = line.indexOf(':');
        if (colon <= 0) return null;

        int i = colon - 1;
        while (i >= 0 && isHex(line.charAt(i))) i--;
        String hex = line.substring(i + 1, colon).trim();
        if (hex.isEmpty()) return null;

        try {
            return Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a breakpoint list row.
     * Supports formats like:
     *  - "0x0228"
     *  - "0x0228  83       i0"
     *  - "▶ 0x0228  83       i0"
     */
    private static Integer parseBreakpointLineAddress(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty()) return null;

        int ox = indexOfIgnoreCase(t, "0x");
        int start = (ox >= 0) ? (ox + 2) : 0;

        if (ox < 0) {
            while (start < t.length() && !isHex(t.charAt(start))) start++;
        }

        int i = start;
        while (i < t.length() && isHex(t.charAt(i))) i++;
        if (i == start) return null;

        String hex = t.substring(start, i);
        try {
            return Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        int n = s.length();
        int m = needle.length();
        for (int i = 0; i <= n - m; i++) {
            if (s.regionMatches(true, i, needle, 0, m)) return i;
        }
        return -1;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }
}
