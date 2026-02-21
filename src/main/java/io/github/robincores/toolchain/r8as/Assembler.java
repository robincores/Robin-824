package io.github.robincores.toolchain.r8as;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.antlr.v4.runtime.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

public class Assembler {

    static String hex(long v, int nd) {
        try {
            if (nd == 0) nd = 2;
            String s = Long.toHexString(v).toUpperCase(Locale.ROOT);
            while (s.length() < nd) s = "0" + s;
            // If caller asks for fewer digits, keep least-significant digits (useful for masks)
            if (s.length() > nd) s = s.substring(s.length() - nd);
            return s;
        } catch (Exception e) {
            return Long.toString(v);
        }
    }

    static int[] stringToData(String s) {
        int[] data = new int[s.length()];
        for (int i = 0; i < s.length(); i++) {
            data[i] = s.charAt(i);
        }
        return data;
    }

    static int mask32(int bits) {
        if (bits <= 0) return 0;
        if (bits >= 32) return 0xFFFF_FFFF; // == -1
        return (int) ((1L << bits) - 1L);
    }

    static long mask64(int bits) {
        if (bits <= 0) return 0L;
        if (bits >= 64) return 0xFFFF_FFFF_FFFF_FFFFL; // == -1L
        return (1L << bits) - 1L;
    }

    // ---
    AssemblerSpec spec;

    // ----- Sections (Step 15) -----
    static final class SectionState {
        final String name;      // e.g. ".text", ".data", ".bss", ".section foo"
        final boolean bss;      // true => reserves space, does not emit bytes
        int ip = 0;             // current location counter (word address)
        int origin = 0;         // section base/origin (word address)
        int codelen = 0;        // minimum emitted length (words) for this section
        boolean originLocked = false; // once we have emitted words, origin becomes fixed

        final List<Integer> outwords = new ArrayList<>();
        final List<AssemblerFixup> fixups = new ArrayList<>();

        SectionState(String name, boolean bss) {
            this.name = name;
            this.bss = bss;
        }
    }

    // Deterministic section ordering: insertion order (we seed with .text)
    private final LinkedHashMap<String, SectionState> sections = new LinkedHashMap<>();
    private SectionState curSec;

    int linenum = 0;
    Map<String, Symbol> symbols = new HashMap<>();

    // Step 13: Local labels (numeric 1f/1b and .Lfoo style)
    private final Map<Integer, List<Integer>> numericLabels = new HashMap<>();

    private static final Pattern DOT_LOCAL_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])\\.L([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern NUM_LABEL_DEF_PATTERN = Pattern.compile("^\\s*([0-9]+):");
    private static final Pattern NUM_REF_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])([0-9]+)([fb])(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM_REF_REWRITTEN_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])__L([0-9]+)([fb])(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
    List<AssemblerError> errors = new ArrayList<>();

    // Source context for .include/.module (best-effort; supports nested includes)
    private final java.util.Deque<java.nio.file.Path> sourceDirStack = new java.util.ArrayDeque<>();
    private java.nio.file.Path currentSourceDir = null;

    // Include handling (.include/.module)
    private final java.util.List<java.nio.file.Path> includeSearchPaths = new java.util.ArrayList<>();
    private final java.util.Deque<String> includeStack = new java.util.ArrayDeque<>();
    private int maxIncludeDepth = 32;

    // Macro handling (.macro/.endm)
    private static final class MacroDef {
        final String name;
        final String key;
        final List<String> params;
        final List<String> bodyLines;

        MacroDef(String name, String key, List<String> params, List<String> bodyLines) {
            this.name = name;
            this.key = key;
            this.params = params;
            this.bodyLines = bodyLines;
        }
    }

    private final Map<String, MacroDef> macros = new HashMap<>();
    private boolean macroDefActive = false;
    private String macroDefName = null;
    private String macroDefKey = null;
    private List<String> macroDefParams = null;
    private List<String> macroDefBody = null;
    private long macroUniqueCounter = 0;
    private final Deque<String> macroExpansionStack = new ArrayDeque<>();
    private static final int MAX_MACRO_EXPANSION_DEPTH = 64;


    // Better diagnostics (source + column)
    private String currentSourceName = "<input>";
    private int currentCol = 1; // 1-based, best-effort

    // Global listing (source order across sections)
    List<AssemblerLine> asmlines = new ArrayList<>();
    int width = 8;
    boolean aborted = false;

    // Populated by finish() (flat concatenated output + section metadata)
    private List<Integer> finalOutwords = null;
    private Map<String, Object> finalIntermediate = null;

    public Assembler() {
        this(null);
    }

    public Assembler(AssemblerSpec spec) {
        this.spec = spec;
        if (spec != null) {
            normalizeSpec(spec);
            preprocessRules();
        }

        // Seed with default section for deterministic output ordering.
        // (If the user never mentions sections, behavior matches legacy single-section assembly.)
        switchSection(".text", false);
    }

    private static String normalizeSectionName(String raw) {
        if (raw == null) return ".text";
        String s = raw.trim();
        if (s.isEmpty()) return ".text";
        if (!s.startsWith(".")) s = "." + s;
        return s;
    }

    private void switchSection(String name, boolean bss) {
        String n = normalizeSectionName(name);
        SectionState existing = sections.get(n);
        if (existing == null) {
            SectionState sec = new SectionState(n, bss);
            sections.put(n, sec);
            curSec = sec;
        } else {
            if (existing.bss != bss) {
                fatal("Section type mismatch for " + n + " (existing " + (existing.bss ? ".bss" : "progbits")
                        + ", requested " + (bss ? ".bss" : "progbits") + ")");
                return;
            }
            curSec = existing;
        }
    }


    /**
     * Adds a filesystem include search path (used by .include/.module).
     */
    public Assembler addIncludePath(java.nio.file.Path dir) {
        if (dir != null) includeSearchPaths.add(dir);
        return this;
    }

    /**
     * Sets max nested include depth (default 32).
     */
    public Assembler setMaxIncludeDepth(int depth) {
        if (depth > 0) this.maxIncludeDepth = depth;
        return this;
    }


    // Converts a rule to a regular expression and stores it
    void rule2regex(AssemblerRule rule, Map<String, AssemblerVar> vars) {
        String s = rule.fmt;
        if (s == null) {
            throw new IllegalArgumentException("Each rule must have a 'fmt' string field");
        }
        if (rule.bits == null) {
            throw new IllegalArgumentException("Each rule must have a 'bits' array field");
        }

        List<String> varlist = new ArrayList<>();
        rule.prefix = s.split("\\s+")[0];

        // Escape special characters for regex
        s = s.replaceAll("\\+", "\\\\+")
                .replaceAll("\\*", "\\\\*")
                .replaceAll("\\s+", "\\\\s+")
                .replaceAll("\\[", "\\\\[")
                .replaceAll("\\]", "\\\\]")
                .replaceAll("\\(", "\\\\(")
                .replaceAll("\\)", "\\\\)")
                .replaceAll("\\.", "\\\\.");

        // Create pattern for matching ~variable
        Pattern pattern = Pattern.compile("~(\\w+)");
        Matcher matcher = pattern.matcher(s);
        StringBuffer result = new StringBuffer();

        // Iterate through matches and replace
        while (matcher.find()) {
            String varname = matcher.group(1); // Extract the variable name without ~
            AssemblerVar v = vars.get(varname);
            varlist.add(varname);
            if (v == null) {
                throw new IllegalArgumentException("Could not find variable definition for '~" + varname + "'");
            }

            // Replace with appropriate regex based on variable type
            String replacement;
            if (v.toks != null) {
                // Enum-like variable, expects a word match
                replacement = "(\\w+)";
            } else {
                // numbers/labels OR simple expressions (for forward fixups, e.g. label+2)
                String atom = "(?:0x[0-9a-f_]+|\\$[0-9a-f_]+|[0-9]+[fb]|[0-9][0-9_]*|[a-z_][a-z0-9_]*|\\.)";
                String op = "(?:<<|>>|\\+|\\-|\\*|/|%|&|\\^|\\|)";
                replacement = "([-+]?(?:~+)?" + atom + "(?:" + op + "(?:~+)?" + atom + ")*)";
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        // Compile the final regex
        try {
            rule.re = Pattern.compile("^" + result + "$", Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Bad regex for rule '" + rule.fmt + "': " + result + " -- " + e);
        }

        rule.varlist = varlist;
    }

    void normalizeSpec(AssemblerSpec spec) {
        if (spec == null || spec.rules == null) return;

        // Vars: defaults + case-insensitive token matching.
        if (spec.vars != null) {
            for (AssemblerVar v : spec.vars.values()) {
                if (v == null) continue;

                if (v.ipmul == 0) v.ipmul = 1;

                if (v.toks != null) {
                    for (int i = 0; i < v.toks.size(); i++) {
                        String t = v.toks.get(i);
                        if (t != null) v.toks.set(i, t.trim().toLowerCase(Locale.ROOT));
                    }
                }

                if (v.aliases != null && !v.aliases.isEmpty()) {
                    Map<String, String> normAliases = new java.util.LinkedHashMap<>();
                    for (var e : v.aliases.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        normAliases.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue().trim().toLowerCase(Locale.ROOT));
                    }
                    v.aliases = normAliases;
                }
            }
        }

        for (AssemblerRule rule : spec.rules) {
            if (rule == null || rule.bits == null) continue;

            List<Object> out = new ArrayList<>(rule.bits.size());
            for (Object b : rule.bits) {
                if (b instanceof Number) {
                    // Gson may give Double; normalize to Integer
                    out.add(((Number) b).intValue());
                } else if (b instanceof Map) {
                    // Gson parses slices as Map; convert to AssemblerRuleSlice
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) b;
                    if (m.containsKey("a") && m.containsKey("b") && m.containsKey("n")) {
                        int a = ((Number) m.get("a")).intValue();
                        int bb = ((Number) m.get("b")).intValue();
                        int n = ((Number) m.get("n")).intValue();
                        out.add(new AssemblerRuleSlice(a, bb, n));
                    } else {
                        out.add(b);
                    }
                } else if (b instanceof String) {
                    // Normalize strings (some specs use '_' separators or spaces in bit patterns)
                    out.add(((String) b).replaceAll("[\\s_]+", ""));
                } else {
                    out.add(b);
                }
            }
            rule.bits = out;
        }
    }

    // Preprocess the rules by generating regular expressions for matching
    void preprocessRules() {
        if (this.spec.width != 0) {
            this.width = this.spec.width;
        }
        for (AssemblerRule rule : this.spec.rules) {
            rule2regex(rule, this.spec.vars);
        }
    }

    void warning(String msg, Integer line) {
        int ln = (line != null) ? line : this.linenum;
        this.errors.add(new AssemblerError(msg, ln, this.currentCol, this.currentSourceName));
    }

    void warning(String msg) {
        warning(msg, null);
    }

    void fatal(String msg, Integer line) {
        this.warning(msg, line);
        this.aborted = true;
    }

    void fatal(String msg) {
        fatal(msg, null);
    }

    void fatalIf(String msg, Integer line) {
        if (msg != null) {
            this.fatal(msg, line);
        }
    }

    void fatalIf(String msg) {
        fatalIf(msg, null);
    }

    void addBytes(AssemblerInstruction result) {
        // MUST be word-aligned or nb truncates and we silently drop bits.
        if ((result.nbits % this.width) != 0) {
            this.fatal("Opcode was not word-aligned (" + result.nbits + " bits, width=" + this.width + ")");
            return;
        }

        if (curSec == null) {
            switchSection(".text", false);
        }
        if (curSec.bss) {
            this.fatal("Cannot emit instructions in " + curSec.name + " (BSS section)");
            return;
        }

        this.asmlines.add(new AssemblerLine(this.linenum, curSec.ip, result.nbits, curSec.name));
        long opcode = result.opcode;
        int nb = result.nbits / this.width;

        for (int i = 0; i < nb; i++) {
            int shift = (nb - 1 - i) * this.width;
            int word = (int) ((opcode >>> shift) & mask64(this.width));
            emitWord(word);
        }
    }

    private void emitWord(int word) {
        int idx = curSec.ip - curSec.origin;
        if (idx < 0) {
            this.fatal("Attempted to emit before section origin (ip=" + curSec.ip + ", origin=" + curSec.origin + ")");
            return;
        }

        while (curSec.outwords.size() < idx) {
            curSec.outwords.add(0);
        }

        int w = word & mask32(this.width);
        if (idx == curSec.outwords.size()) {
            curSec.outwords.add(w);
        } else {
            curSec.outwords.set(idx, w);
        }

        curSec.ip++;
        curSec.originLocked = true;
    }

    void addWords(int[] data) {
        if (curSec == null) {
            switchSection(".text", false);
        }

        this.asmlines.add(new AssemblerLine(this.linenum, curSec.ip, this.width * data.length, curSec.name));

        if (curSec.bss) {
            // Reserve space only (do not emit bytes)
            curSec.ip += data.length;
            return;
        }

        for (int datum : data) {
            emitWord(datum);
        }
    }

    int[] parseData(String[] toks) {
        int[] data = new int[toks.length];
        int startIp = curSec.ip;

        for (int i = 0; i < toks.length; i++) {
            String expr = toks[i];

            long v = this.parseConst64(expr, this.width);
            if (isNaN(v)) {
                Long ev;
                try {
                    ev = evalExprAllowLocals(expr, startIp + i);
                } catch (IllegalArgumentException ex) {
                    this.warning("Bad expression '" + expr + "': " + ex.getMessage(), this.linenum);
                    ev = 0L;
                }

                if (ev == null) {
                    // forward ref (symbol or expression)
                    curSec.fixups.add(new AssemblerFixup(
                            expr, startIp + i, this.width, 0, 0, this.width, this.linenum,
                            false, 0, 1, "big"
                    ));
                    v = 0;
                } else {
                    v = ev;
                }
            }

            long max = mask64(this.width);
            long min = signedMin(this.width);
            if (this.width < 64 && (v < min || v > max)) {
                this.warning("Value " + v + " does not fit in " + this.width + " bits", this.linenum);
            }
            v &= max;

            data[i] = (int) (v & mask32(this.width));
        }

        return data;
    }

    // Emit sized integers (e.g. 16-bit, 32-bit) as a sequence of assembler-words (width bits).
    // Supports forward refs by creating fixups per emitted word-chunk.
    private int[] parseSizedData(String[] exprs, int bits, boolean littleEndian) {
        if (curSec == null) switchSection(".text", false);

        if ((bits % this.width) != 0) {
            fatal("Cannot emit " + bits + "-bit values when width=" + this.width + " (bits must be multiple of width)");
            return new int[0];
        }

        int chunks = bits / this.width; // e.g. width=8, bits=16 => 2 bytes
        int startIp = curSec.ip;
        int[] out = new int[exprs.length * chunks];

        for (int i = 0; i < exprs.length; i++) {
            String expr = exprs[i];
            int baseIp = startIp + (i * chunks);

            long v = parseConst64(expr, bits);
            if (isNaN(v)) {
                Long ev;
                try {
                    ev = evalExprAllowLocals(expr, baseIp);
                } catch (IllegalArgumentException ex) {
                    warning("Bad expression '" + expr + "': " + ex.getMessage(), this.linenum);
                    ev = 0L;
                }

                if (ev == null) {
                    // forward ref: create one fixup per chunk
                    for (int k = 0; k < chunks; k++) {
                        int srcofs = littleEndian
                                ? (k * this.width)
                                : ((chunks - 1 - k) * this.width);

                        curSec.fixups.add(new AssemblerFixup(
                                expr,
                                baseIp + k,     // destination word address
                                bits,           // source size (whole value)
                                srcofs,         // shift-right before masking
                                0,              // dstofs (bit offset within destination word)
                                this.width,     // dstlen (whole word)
                                this.linenum,
                                false,          // iprel
                                0,              // ipofs
                                1,              // ipmul
                                "big"           // IMPORTANT: do NOT swap here; we control order via srcofs
                        ));
                        out[i * chunks + k] = 0;
                    }
                    continue;
                } else {
                    v = ev;
                }
            }

            long max = mask64(bits);
            long min = signedMin(bits);
            if (bits < 64 && (v < min || v > max)) {
                warning("Value " + v + " does not fit in " + bits + " bits", this.linenum);
            }
            v &= max;

            for (int k = 0; k < chunks; k++) {
                int shift = littleEndian
                        ? (k * this.width)
                        : ((chunks - 1 - k) * this.width);
                out[i * chunks + k] = (int) ((v >>> shift) & mask64(this.width));
            }
        }

        return out;
    }

    /**
     * Bytes per emitted word.
     * <p>
     * R8 width=8 => 1 byte
     * RV32 width=32 => 4 bytes
     */
    int bytesPerWord() {
        if ((this.width % 8) != 0) {
            this.fatal("Word width must be a multiple of 8 to use byte-address directives");
        }
        return this.width / 8;
    }

    int bytesToWords(int bytes, String directive) {
        int bpw = bytesPerWord();
        if ((bytes % bpw) != 0) {
            this.fatal("." + directive + " value " + bytes + " is not aligned to word size (" + bpw + " bytes)");
        }
        return bytes / bpw;
    }

    int wordsToBytes(int words) {
        return words * bytesPerWord();
    }


    void alignIPBytes(int alignBytes) {
        int bpw = bytesPerWord();
        if ((alignBytes % bpw) != 0) {
            this.fatal(".align value " + alignBytes + " must be a multiple of word size (" + bpw + " bytes)");
        }
        int alignWords = alignBytes / bpw;
        this.alignIP(alignWords);
    }

    void alignIP(int align) {
        if (align < 1) {
            this.fatal("Invalid alignment value");
        }
        long mod = curSec.ip % align;
        if (mod == 0) {
            return;
        }
        long pad = align - mod;
        if (pad > Integer.MAX_VALUE) {
            this.fatal("Alignment padding too large: " + pad);
        }
        int[] zeros = new int[(int) pad];
        this.addWords(zeros);
    }

    public boolean isNaN(long value) {
        return value == Long.MIN_VALUE;
    }

    private static final Pattern IDENT_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private boolean isIdentifier(String s) {
        return s != null && IDENT_PATTERN.matcher(s).matches();
    }

    private long evalExpr64(String expr) {
        final Long v = evalExprAllowLocals(expr, curSec.ip);
        if (v == null) {
            throw new IllegalArgumentException("Unknown symbol in expression: " + expr);
        }
        return v;
    }

    // Parse a constant into a 64-bit value (supports up to 64-bit fields/opcodes)
    long parseConst64(String s, Integer nbits) {
        if (s == null) return Long.MIN_VALUE;

        s = s.trim();

        long sign = 1;
        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            sign = -1;
            s = s.substring(1);
        }

        s = s.replace("_", "");
        String sl = s.toLowerCase(Locale.ROOT);

        try {
            if (sl.startsWith("0x")) return sign * Long.parseLong(sl.substring(2), 16);
            if (sl.startsWith("$")) return sign * Long.parseLong(sl.substring(1), 16);
            return sign * Long.parseLong(sl, 10);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    long parseConst64(String s) {
        return parseConst64(s, null);
    }

    int parseConst(String s) {
        return parseConst(s, null);
    }

    // Needed by callers that pass a width.
    int parseConst(String s, Integer nbits) {
        long v = parseConst64(s, nbits);
        if (v == Long.MIN_VALUE) {
            // Allow referencing previously-defined symbols in directive contexts.
            if (isIdentifier(s)) {
                final Symbol sym = this.symbols.get(s.toLowerCase(Locale.ROOT));
                if (sym != null) return sym.value;
                return Integer.MIN_VALUE;
            }
            try {
                v = evalExpr64(s);
            } catch (IllegalArgumentException ex) {
                return Integer.MIN_VALUE;
            }
        }
        return (int) v;
    }

    // Helper method to swap endian of a value
    int swapEndian(int value, int nbits) {
        int y = 0;
        while (nbits > 0) {
            int n = Math.min(nbits, width);
            int mask = mask32(n);
            y <<= n;
            y |= (value & mask);
            value >>>= n;
            nbits -= n;
        }
        return y;
    }

    // 64-bit variant (needed for 48-bit+ fields/opcodes)
    long swapEndian64(long value, int nbits) {
        long y = 0L;
        while (nbits > 0) {
            int n = Math.min(nbits, width);
            long m = mask64(n);
            y = (y << n) | (value & m);
            value >>>= n;
            nbits -= n;
        }
        return y;
    }

    private static long signedMin(int bits) {
        if (bits <= 0) return 0;
        if (bits >= 64) return Long.MIN_VALUE;
        return -(1L << (bits - 1));
    }

    // Normalize source so rules don't have to care about whitespace around punctuation.
    // Examples:
    //   "add x1, x2, x3"  -> "add x1,x2,x3"
    //   "sw x1, 0 ( x0 )" -> "sw x1,0(x0)"
    static String normalizeForMatch(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.isEmpty()) return s;

        s = s.replaceAll("\\s+", " ");
        s = s.replaceAll("\\s*,\\s*", ",");
        s = s.replaceAll("\\(\\s*", "(");
        s = s.replaceAll("\\s*\\)", ")");
        s = s.replaceAll("@\\s+", "@");

        // Ensure exactly one space between mnemonic and operand list (if any)
        int sp = s.indexOf(' ');
        if (sp >= 0) {
            String a = s.substring(0, sp).trim();
            String b = s.substring(sp + 1).trim();
            if (!b.isEmpty()) s = a + " " + b;
            else s = a;
        }
        return s;
    }


    /**
     * Fold constant expressions inside operands so JSON rule matching can stay simple.
     * <p>
     * Example: "i 0x1234+2" -> "i 4662" (decimal)
     * <p>
     * This is intentionally conservative: it only folds when the expression can be
     * evaluated with the current symbol table and dot (.). If it can\'t be evaluated
     * we leave it unchanged.
     */
    private String canonicalizeInstructionExpressions(String instruction) {
        if (instruction == null) return "";
        String s = instruction.trim();
        if (s.isEmpty()) return s;

        int sp = s.indexOf(' ');
        if (sp < 0) {
            return s;
        }

        String mnemonic = s.substring(0, sp).trim();
        String rest = s.substring(sp + 1).trim();
        if (rest.isEmpty()) {
            return mnemonic;
        }

        java.util.List<String> ops = splitTopLevelCommas(rest);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String op : ops) {
            String x = canonicalizeOperandExpression(op.trim());
            if (!x.isEmpty()) out.add(x);
        }
        return mnemonic + " " + String.join(",", out);
    }

    private static java.util.List<String> splitTopLevelCommas(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(last, i));
                last = i + 1;
            }
        }
        out.add(s.substring(last));
        return out;
    }

    private String canonicalizeOperandExpression(String operand) {
        if (operand == null) return "";
        String op = operand.trim();
        if (op.isEmpty()) return op;

        String prefix = "";
        String body = op;
        if (body.startsWith("@")) {
            prefix = "@";
            body = body.substring(1).trim();
        }

        // If it\'s not a plain identifier, try to fold it as a constant expression.
        if (!body.isEmpty() && !isIdentifier(body)) {
            try {
                long v = evalExpr64(body);
                return prefix + Long.toString(v);
            } catch (RuntimeException ignore) {
                // fall through
            }
        }

        // Try to fold base/index in a memory operand: base(index)
        if (!body.isEmpty() && body.endsWith(")")) {
            int lp = findMatchingLParenFromEnd(body);
            if (lp >= 0 && lp < body.length() - 1) {
                String base = body.substring(0, lp).trim();
                String idx = body.substring(lp + 1, body.length() - 1).trim();

                // Avoid treating grouping like "a+(b)" as a mem operand.
                if (!base.isEmpty() && !endsWithOperator(base)) {
                    String baseOut = base;
                    if (!base.isEmpty() && !isIdentifier(base)) {
                        try {
                            baseOut = Long.toString(evalExpr64(base));
                        } catch (RuntimeException ignore) {
                        }
                    }

                    String idxOut = idx;
                    if (!idx.isEmpty() && !isIdentifier(idx)) {
                        try {
                            idxOut = Long.toString(evalExpr64(idx));
                        } catch (RuntimeException ignore) {
                        }
                    }
                    return prefix + baseOut + "(" + idxOut + ")";
                }
            }
        }

        return prefix + body;
    }

    private static int findMatchingLParenFromEnd(String s) {
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static boolean endsWithOperator(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '(' || c == ',';
    }

    // Build an instruction based on the matched rule
    public AssemblerLineResult buildInstruction(AssemblerRule rule, Matcher m) {
        long opcode = 0L;
        int oplen = 0;

        for (Object b : rule.bits) {
            int n;

            if (b instanceof String) {
                // allow whitespace in JSON like "00 0000 00"
                String bs = ((String) b).replaceAll("[\\s_]+", "");
                n = bs.length();
                long xl = (n == 0) ? 0L : Long.parseLong(bs, 2);
                opcode = (opcode << n) | (xl & mask64(n));
                oplen += n;
                continue;
            }

            int index = (b instanceof Number) ? ((Number) b).intValue() : ((AssemblerRuleSlice) b).a;

            String id = m.group(index + 1);
            AssemblerVar v = this.spec.vars.get(rule.varlist.get(index));

            if (v == null) {
                return new AssemblerErrorResult("Could not find matching identifier for '" + m.group(0) + "' index " + index);
            }

            n = v.bits;
            int shift = 0;
            if (!(b instanceof Number)) {
                n = ((AssemblerRuleSlice) b).n;
                shift = ((AssemblerRuleSlice) b).b;
            }

            long xl;

            if (v.toks != null) {
                String tok = id.trim().toLowerCase(Locale.ROOT);

                int xi = v.toks.indexOf(tok);

                // Optional aliases (e.g., "sp" -> "x2")
                if (xi < 0 && v.aliases != null) {
                    String canon = v.aliases.get(tok);
                    if (canon != null) xi = v.toks.indexOf(canon);
                }

                // Convenience: accept forms like x17 / r17 / w15 even if not explicitly listed
                if (xi < 0) {
                    int pfx = 0;
                    while (pfx < tok.length() && Character.isLetter(tok.charAt(pfx))) pfx++;
                    if (pfx > 0 && pfx < tok.length()) {
                        String digits = tok.substring(pfx);
                        if (digits.matches("\\d+")) {
                            try {
                                int nval = Integer.parseInt(digits);
                                if (nval >= 0 && nval < (1 << v.bits)) xi = nval;
                            } catch (NumberFormatException ignore) {
                                // keep xi < 0
                            }
                        }
                    }
                }

                if (xi < 0) {
                    return new AssemblerErrorResult("Can't use '" + id + "' here, only one of: " + v.toks);
                }

                xl = (long) xi;
            } else {
                xl = parseConst64(id, v.bits);

                if (isNaN(xl)) {
                    // Try to evaluate as an expression. If it contains unknown symbols, defer as a fixup.
                    Long ev;
                    try {
                        ev = evalExprAllowLocals(id, curSec.ip);
                    } catch (IllegalArgumentException ex) {
                        return new AssemblerErrorResult("Bad expression '" + id + "': " + ex.getMessage());
                    }

                    if (ev == null) {
                        curSec.fixups.add(new AssemblerFixup(
                                id, curSec.ip, v.bits, shift, oplen, n, this.linenum,
                                v.iprel, v.ipofs, v.ipmul == 0 ? 1 : v.ipmul, v.endian
                        ));
                        xl = 0;
                    } else {
                        xl = ev;
                        if (v.iprel) {
                            long ipmul = (v.ipmul == 0 ? 1 : v.ipmul);
                            xl = (xl - curSec.ip) * ipmul - v.ipofs;
                        }
                        long max = mask64(v.bits);
                        long min = signedMin(v.bits);
                        if (v.bits < 64 && (xl < min || xl > max)) {
                            return new AssemblerErrorResult("Value " + xl + " does not fit in " + v.bits + " bits");
                        }
                        xl &= max;
                    }
                } else {
                    if (v.iprel) {
                        long ipmul = (v.ipmul == 0 ? 1 : v.ipmul);
                        xl = (xl - curSec.ip) * ipmul - v.ipofs;
                    }
                    long max = mask64(v.bits);
                    long min = signedMin(v.bits);
                    if (v.bits < 64 && (xl < min || xl > max)) {
                        return new AssemblerErrorResult("Value " + xl + " does not fit in " + v.bits + " bits");
                    }
                    xl &= max;
                }
            }

            // If little endian, swap the byte order (over the full variable width)
            if ("little".equals(v.endian)) {
                xl = swapEndian64(xl, v.bits);
            }

            if (!(b instanceof Number)) {
                xl = (xl >>> shift) & mask64(((AssemblerRuleSlice) b).n);
            }

            opcode = (opcode << n) | (xl & mask64(n));
            oplen += n;
        }

        if (oplen == 0) {
            warning("Opcode had zero length");
        } else if (oplen > 64) {
            return new AssemblerErrorResult("Opcodes > 64 bits not supported (got " + oplen + ")");
        } else if ((oplen % width) != 0) {
            warning("Opcode was not word-aligned (" + oplen + " bits)");
        }

        return new AssemblerInstruction(opcode, oplen);
    }

    // ---------- Arch loading (JSON) ----------

    public String loadArch(String arch) {
        String path = arch.endsWith(".json") ? arch : (arch + ".json");

        String json = readJSONText(path);
        if (json == null) {
            return "Could not load arch file '" + path + "'";
        }

        try {
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            List<ArchSpecValidator.ValidationError> verrs = ArchSpecValidator.validate(root);
            if (!verrs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Invalid arch spec '").append(path).append("':\n");
                int lim = Math.min(verrs.size(), 12);
                for (int i = 0; i < lim; i++) {
                    sb.append("  - ").append(verrs.get(i)).append("\n");
                }
                if (verrs.size() > lim) sb.append("  ... (+").append(verrs.size() - lim).append(" more)\n");
                return sb.toString().trim();
            }

            AssemblerSpec loaded = new Gson().fromJson(json, AssemblerSpec.class);
            if (loaded == null || loaded.vars == null || loaded.rules == null) {
                return "Invalid arch spec '" + path + "' (missing vars/rules)";
            }

            this.spec = loaded;
            normalizeSpec(this.spec);
            this.preprocessRules();
            return null;
        } catch (Exception e) {
            return "Could not parse arch file '" + path + "': " + e.getMessage();
        }
    }

    /**
     * Load an architecture JSON either from disk or from classpath resources.
     * <p>
     * Search order:
     * 1) exact filesystem path
     * 2) classpath: /io/github/robincores/toolchain/r8as/<name>.json
     * 3) classpath: /io/github/robincores/toolchain/r8as/arch/<name>.json
     * 4) classpath: /arch/<name>.json
     */
    public AssemblerSpec loadJSON(String path) {
        try {
            String json = readJSONText(path);
            if (json == null) return null;

            // Gson can construct AssemblerSpec, but we must normalize rule.bits slices
            Gson gson = new Gson();
            return gson.fromJson(json, AssemblerSpec.class);
        } catch (Exception e) {
            this.warning("Failed to load JSON '" + path + "': " + e);
            return null;
        }
    }

    private String readJSONText(String path) {
        try {
            Path p = Path.of(path);
            if (Files.exists(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }

            InputStream in = tryResource("/io/github/robincores/toolchain/r8as/" + path);
            if (in == null) in = tryResource("/io/github/robincores/toolchain/r8as/arch/" + path);
            if (in == null) in = tryResource("/arch/" + path);
            if (in == null) in = tryResource("/" + path);
            if (in == null) return null;

            // Java requires a *final/effectively-final* variable for try-with-resources when reusing
            // an existing variable (Java 9+ "try(resource)"). Use a new resource variable instead.
            try (InputStream in2 = in) {
                return new String(in2.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private InputStream tryResource(String resPath) {
        return Assembler.class.getResourceAsStream(resPath);
    }

    // ---------- Directives ----------

    void parseDirective(String[] tokens) {
        String cmd = tokens[0].toLowerCase(Locale.ROOT);

        // Section selectors
        if (cmd.equals(".text")) {
            switchSection(".text", false);
            return;
        }
        if (cmd.equals(".bss")) {
            switchSection(".bss", true);
            return;
        }
        if (cmd.equals(".data")) {
            if (tokens.length != 1) {
                fatal(".data is a section selector only. Use .byte to emit numeric values.");
                return;
            }
            switchSection(".data", false);
            return;
        }
        if (cmd.equals(".section")) {
            if (tokens.length < 2) {
                fatal(".section requires a name");
                return;
            }
            String secName = normalizeSectionName(tokens[1]);
            boolean isBss = secName.equals(".bss");
            switchSection(secName, isBss);
            return;
        }

        switch (cmd) {
            case ".define": {
                if (tokens.length < 3) {
                    fatal("Usage: .define NAME value");
                    break;
                }
                symbols.put(tokens[1].toLowerCase(Locale.ROOT), new Symbol(parseConst(tokens[2])));
                break;
            }

            case ".equ":
            case ".set": {
                boolean allowRedef = cmd.equals(".set");
                if (tokens.length < 3) {
                    warning("Usage: " + cmd + " NAME expr");
                    break;
                }
                String name = tokens[1].toLowerCase(Locale.ROOT);
                String expr = tokens[2];

                Long ev;
                try {
                    ev = evalExprAllowLocals(expr, curSec.ip);
                } catch (IllegalArgumentException ex) {
                    warning("Bad expression '" + expr + "': " + ex.getMessage());
                    ev = null;
                }

                if (ev == null) {
                    warning("Unresolved symbol/expression '" + expr + "'");
                    break;
                }

                if (!allowRedef && symbols.containsKey(name)) {
                    warning("Symbol '" + name + "' already defined");
                    break;
                }

                symbols.put(name, new Symbol((int) (long) ev));
                break;
            }

            case ".macro": {
                if (tokens.length < 2) {
                    fatal(".macro requires a name");
                    break;
                }
                beginMacroDef(tokens);
                break;
            }
            case ".endm":
            case ".endmacro": {
                if (macroDefActive) {
                    finishMacroDef();
                } else {
                    fatal(tokens[0] + " without active .macro");
                }
                break;
            }

            case ".org": {
                if (tokens.length < 2) {
                    fatal("Usage: .org addr");
                    break;
                }
                int newIp = bytesToWords(parseConst(tokens[1]), "org");

                // Per-section .org:
                // - Before any emission in this section, sets both origin and ip.
                // - After emission, only moves ip (and will pad with zeros on next emission if needed).
                if (!curSec.originLocked && curSec.outwords.isEmpty()) {
                    curSec.origin = newIp;
                    curSec.ip = newIp;
                } else {
                    curSec.ip = newIp;
                }
                break;
            }

            case ".len": {
                if (tokens.length < 2) {
                    fatal("Usage: .len bytes");
                    break;
                }
                curSec.codelen = bytesToWords(parseConst(tokens[1]), "len");
                break;
            }

            case ".width": {
                if (tokens.length < 2) {
                    fatal("Usage: .width 8|16|24|32");
                    break;
                }
                width = parseConst(tokens[1]);
                if (!(width == 8 || width == 16 || width == 24 || width == 32)) {
                    fatal("Unsupported .width " + width + " (use 8,16,24,32)");
                }
                break;
            }

            case ".arch": {
                if (tokens.length < 2) {
                    fatal("Usage: .arch name");
                    break;
                }
                fatalIf(loadArch(tokens[1]));
                break;
            }

            case ".include": {
                String raw = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
                String path = unquote(raw);
                fatalIf(loadInclude(path));
                break;
            }

            case ".module": {
                String raw = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
                String path = unquote(raw);
                fatalIf(loadModule(path));
                break;
            }

            case ".incbin": {
                if (tokens.length < 2) {
                    fatal("Usage: .incbin \"file\"[, offset[, length]]");
                    break;
                }

                String p0 = stripTrailingComma(tokens[1]);
                String binPath = unquote(p0);

                int offset = 0;
                int length = -1;

                if (tokens.length >= 3) {
                    String t = stripTrailingComma(tokens[2]);
                    try {
                        Long v = evalExprAllowLocals(t, curSec.ip);
                        offset = (v == null) ? 0 : (int) (long) v;
                    } catch (IllegalArgumentException ex) {
                        fatal("Bad offset expression '" + t + "': " + ex.getMessage());
                        break;
                    }
                }

                if (tokens.length >= 4) {
                    String t = stripTrailingComma(tokens[3]);
                    try {
                        Long v = evalExprAllowLocals(t, curSec.ip);
                        length = (v == null) ? -1 : (int) (long) v;
                    } catch (IllegalArgumentException ex) {
                        fatal("Bad length expression '" + t + "': " + ex.getMessage());
                        break;
                    }
                }

                if (offset < 0) {
                    fatal(".incbin offset must be >= 0");
                    break;
                }
                if (length < -1) {
                    fatal(".incbin length must be >= 0 (or omitted)");
                    break;
                }

                if (bytesPerWord() != 1) {
                    fatal(".incbin currently requires width=8 (bytesPerWord=1)");
                    break;
                }

                byte[] bytes = loadExternalBinary(binPath, java.util.List.of("", "include/", "assets/"));
                if (bytes == null) {
                    fatal("Cannot find/read binary: " + binPath);
                    break;
                }

                if (offset > bytes.length) {
                    fatal(".incbin offset beyond end of file (" + offset + " > " + bytes.length + ")");
                    break;
                }

                int available = bytes.length - offset;
                int take = (length == -1) ? available : Math.min(length, available);

                if (curSec.bss) {
                    // Reserve space only
                    addWords(new int[take]);
                } else {
                    int[] out = new int[take];
                    for (int i = 0; i < take; i++) out[i] = bytes[offset + i] & 0xFF;
                    addWords(out);
                }
                break;
            }

            case ".byte": {
                if (tokens.length < 2) {
                    fatal("Usage: .byte <expr>[, <expr> ...]");
                    break;
                }
                int n = tokens.length - 1;

                if (curSec.bss) {
                    // Reserve N words (values irrelevant in BSS)
                    addWords(new int[n]);
                } else {
                    addWords(parseData(Arrays.copyOfRange(tokens, 1, tokens.length)));
                }
                break;
            }

            case ".ascii":
            case ".string": {
                if (tokens.length < 2) {
                    fatal("Usage: .string/.ascii \"...\"");
                    break;
                }
                String raw = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
                raw = unquote(raw);
                int[] data = stringToData(raw);

                if (curSec.bss) {
                    addWords(new int[data.length]);
                } else {
                    addWords(data);
                }
                break;
            }

            case ".asciiz":
            case ".asciz": {
                if (tokens.length < 2) {
                    fatal("Usage: .asciiz/.asciz \"...\"");
                    break;
                }
                String raw = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
                raw = unquote(raw);
                int[] data = stringToData(raw);

                if (curSec.bss) {
                    addWords(new int[data.length + 1]); // reserve + terminator
                } else {
                    addWords(data);
                    addWords(new int[]{0});
                }
                break;
            }

            case ".word": {
                if (tokens.length < 2) {
                    fatal("Usage: .word <expr>[, <expr> ...]");
                    break;
                }
                String[] exprs = Arrays.copyOfRange(tokens, 1, tokens.length);

                if (curSec.bss) {
                    // reserve 2 bytes per item when width=8; generally: (16/width) words per item
                    int chunks = 16 / this.width;
                    addWords(new int[exprs.length * chunks]);
                } else {
                    addWords(parseSizedData(exprs, 16, true)); // little-endian
                }
                break;
            }

            case ".dword": {
                if (tokens.length < 2) {
                    fatal("Usage: .dword <expr>[, <expr> ...]");
                    break;
                }
                String[] exprs = Arrays.copyOfRange(tokens, 1, tokens.length);

                if (curSec.bss) {
                    int chunks = 32 / this.width;
                    addWords(new int[exprs.length * chunks]);
                } else {
                    addWords(parseSizedData(exprs, 32, true)); // little-endian
                }
                break;
            }

            case ".align": {
                if (tokens.length < 2) {
                    fatal("Usage: .align N");
                    break;
                }
                alignIPBytes(parseConst(tokens[1]));
                break;
            }

            default:
                warning("Unrecognized directive: " + String.join(" ", tokens));
                break;
        }
    }

    static String unquote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '\\' && i + 1 < t.length()) {
                    char n = t.charAt(++i);
                    switch (n) {
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case '\\':
                            out.append('\\');
                            break;
                        case '"':
                            out.append('"');
                            break;
                        default:
                            out.append(n);
                            break;
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        return s;
    }

    /**
     * Strip ';' comments from a raw source line, but keep semicolons inside a quoted string.
     * This preserves directives like: .string "a;b".
     */
    static String stripComments(String line) {
        if (line == null || line.isEmpty()) return line;
        boolean inQuote = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (c == ';' && !inQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    // ---------- Assembly ----------

    // Assemble a line of assembly code
    public AssemblerInstruction assemble(String line) {
        // Backwards compatible: when called line-by-line, we count lines internally.
        return assembleAt(line, this.currentSourceName, ++this.linenum);
    }


    private void beginMacroDef(String[] tokens) {
        String name = tokens[1];
        String key = name.toLowerCase(Locale.ROOT);

        if (macroDefActive) {
            fatal("Nested .macro is not supported");
            return;
        }
        if (macros.containsKey(key)) {
            fatal("Macro already defined: " + name);
            return;
        }

        List<String> params = parseMacroParams(tokens);
        macroDefActive = true;
        macroDefName = name;
        macroDefKey = key;
        macroDefParams = params;
        macroDefBody = new ArrayList<>();
    }

    private List<String> parseMacroParams(String[] tokens) {
        if (tokens.length <= 2) {
            return Collections.emptyList();
        }

        // Join everything after ".macro <name>" back into one string so we can parse:
        //   .macro foo a,b,c
        //   .macro foo a b c    (also allowed)
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < tokens.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(tokens[i]);
        }
        String rest = sb.toString().trim();
        if (rest.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> commaSplit = splitTopLevelCommas(rest);

        // If there are no commas, allow whitespace-separated params (GNU-ish style)
        if (commaSplit.size() == 1 && !rest.contains(",")) {
            String[] ws = rest.trim().isEmpty() ? new String[0] : rest.trim().split("\\s+");
            return Arrays.stream(ws).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        return commaSplit.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private void finishMacroDef() {
        if (!macroDefActive) {
            fatal(".endm without active .macro");
            return;
        }

        MacroDef def = new MacroDef(macroDefName, macroDefKey, macroDefParams, new ArrayList<>(macroDefBody));
        macros.put(def.key, def);

        macroDefActive = false;
        macroDefName = null;
        macroDefKey = null;
        macroDefParams = null;
        macroDefBody = null;
    }

    private void expandAndAssembleMacro(MacroDef macro, List<String> args, String callSource, int callLineNo) {
        if (macroExpansionStack.contains(macro.key)) {
            fatal("macro recursion detected: " + macro.name);
            return;
        }
        if (macroExpansionStack.size() >= MAX_MACRO_EXPANSION_DEPTH) {
            fatal("macro expansion depth exceeded for: " + macro.name);
            return;
        }
        if (args.size() != macro.params.size()) {
            fatal("macro \"" + macro.name + "\" expects " + macro.params.size() + " args, got " + args.size());
            return;
        }

        long uniq = macroUniqueCounter++;
        Map<String, String> argMap = new HashMap<>();
        for (int i = 0; i < macro.params.size(); i++) {
            argMap.put(macro.params.get(i), args.get(i).trim());
        }

        macroExpansionStack.push(macro.key);
        try {
            for (String bodyLine : macro.bodyLines) {
                if (aborted) return;
                String expanded = expandMacroLine(bodyLine, macro, argMap, uniq);
                assembleAt(expanded, callSource, callLineNo);
            }
        } finally {
            macroExpansionStack.pop();
        }
    }

    private String expandMacroLine(String line, MacroDef macro, Map<String, String> argMap, long uniq) {
        if (line.indexOf('\\') < 0) return line;

        StringBuilder out = new StringBuilder(line.length() + 16);
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (c != '\\' || i == n - 1) {
                out.append(c);
                continue;
            }

            char next = line.charAt(i + 1);

            if (next == '@') {
                out.append(uniq);
                i++;
                continue;
            }

            if (Character.isDigit(next)) {
                int j = i + 1;
                while (j < n && Character.isDigit(line.charAt(j))) j++;
                String num = line.substring(i + 1, j);
                try {
                    int idx = Integer.parseInt(num) - 1;
                    if (idx >= 0 && idx < macro.params.size()) {
                        String pname = macro.params.get(idx);
                        String repl = argMap.get(pname);
                        if (repl != null) {
                            out.append(repl);
                            i = j - 1;
                            continue;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
                out.append(c);
                continue;
            }

            if (Character.isLetter(next) || next == '_') {
                int j = i + 1;
                while (j < n) {
                    char ch = line.charAt(j);
                    if (!(Character.isLetterOrDigit(ch) || ch == '_')) break;
                    j++;
                }
                String ident = line.substring(i + 1, j);
                String repl = argMap.get(ident);
                if (repl != null) {
                    out.append(repl);
                    i = j - 1;
                    continue;
                }
            }

            // Unknown escape; keep the backslash as-is.
            out.append(c);
        }

        return out.toString();
    }

    private AssemblerInstruction assembleAt(String line, String sourceName, int lineNo) {
        this.currentSourceName = (sourceName == null || sourceName.isBlank()) ? "<input>" : sourceName;
        this.linenum = lineNo;
        this.currentCol = 1;

        if (line == null) return null;

        // Best-effort column: first non-space in raw line.
        int firstNonWs = 0;
        while (firstNonWs < line.length() && (line.charAt(firstNonWs) == ' ' || line.charAt(firstNonWs) == '\t'))
            firstNonWs++;
        this.currentCol = Math.max(1, firstNonWs + 1);

        // Step 13: normalize .Lfoo locals into legal identifiers (same length)
        String parseLine = rewriteDotLocals(line);

        // Fast path: ignore blanks and comments
        String noComment = stripComments(parseLine);
        String trimmed = noComment.trim();

        if (trimmed.isEmpty()) return null;
        // stripComments() already removed comment-only lines.

        // Step 14: while inside a .macro ... .endm definition, buffer lines verbatim
        // so backslash escapes (\a, \@) don't reach the ANTLR lexer.
        if (macroDefActive) {
            String ll = trimmed.toLowerCase(Locale.ROOT);
            if (ll.startsWith(".endm") || ll.startsWith(".endmacro")) {
                finishMacroDef();
                return null;
            }
            if (ll.startsWith(".macro")) {
                fatal("Nested .macro is not supported", lineNo);
                return null;
            }
            if (macroDefBody != null) {
                macroDefBody.add(noComment);
            }
            return null;
        }


        // Step 13: numeric local label def (e.g., 1:)
        var nm = NUM_LABEL_DEF_PATTERN.matcher(noComment);
        if (nm.find()) {
            int n = Integer.parseInt(nm.group(1));
            defineNumericLabel(n, curSec.ip);
            parseLine = parseLine.substring(nm.end()).stripLeading();
            String rest = stripComments(parseLine).trim();
            if (rest.isEmpty()) return null;
        }

        // Try ANTLR first (better diagnostics + consistent whitespace behavior)
        ParsedLine pl = ParsedLine.parse(parseLine);
        if (pl != null) {
            if (pl.syntaxError != null) {
                if (pl.syntaxErrorCol != null) this.currentCol = pl.syntaxErrorCol;
                this.warning("Syntax error: " + pl.syntaxError);
                return null;
            }

            if (pl.label != null) {
                this.symbols.put(pl.label.toLowerCase(Locale.ROOT), new Symbol(curSec.ip));
            }

            if (pl.directiveTokens != null) {
                this.parseDirective(pl.directiveTokens);
                return null;
            }

            if (pl.instruction != null) {
                // Macro invocation (macros take precedence over instructions)
                String instRaw = pl.instruction.trim();
                if (!instRaw.isEmpty()) {
                    String opRaw = instRaw.split("\\s+", 2)[0];
                    MacroDef macro = macros.get(opRaw.toLowerCase(Locale.ROOT));
                    if (macro != null) {
                        String argText = instRaw.substring(opRaw.length()).trim();
                        List<String> args = argText.isEmpty() ? Collections.<String>emptyList() : splitTopLevelCommas(argText);
                        expandAndAssembleMacro(macro, args, sourceName, lineNo);
                        return null;
                    }
                }

                String lowered = pl.instruction.toLowerCase(Locale.ROOT);
                String canonical = canonicalizeInstructionExpressions(lowered);
                String norm = normalizeForMatch(canonical);
                return matchAndEmit(norm);
            }

            return null;
        }

        // ANTLR is required (no legacy fallback)
        this.fatal("ANTLR parser required (R8AsmLexer/R8AsmParser not available or parse failed)");
        return null;
    }

    private AssemblerInstruction matchAndEmit(String normalizedLower) {
        // check if spec is loaded
        if (this.spec == null) {
            this.fatal("Need to load .arch first");
            return null;
        }

        String lastError = null;
        for (AssemblerRule rule : this.spec.rules) {
            Matcher m = rule.re.matcher(normalizedLower);
            if (m.matches()) {
                AssemblerLineResult result = this.buildInstruction(rule, m);
                if (!(result instanceof AssemblerErrorResult)) {
                    this.addBytes((AssemblerInstruction) result);
                    return (AssemblerInstruction) result;
                } else {
                    lastError = ((AssemblerErrorResult) result).error;
                }
            }
        }

        this.warning(lastError != null ? lastError : "Could not decode instruction: " + normalizedLower);
        return null;
    }


    // Apply fixups for unresolved symbols/expressions after instruction assembly
    // Apply fixups for unresolved symbols/expressions after instruction assembly
    private void applyFixup(SectionState sec, AssemblerFixup fix, long resolvedValue) {
        long value = resolvedValue;

        if (fix.iprel) {
            value = (value - fix.ofs) * fix.ipmul - fix.ipofs;
        }

        long max = mask64(fix.size);
        long min = signedMin(fix.size);
        if (fix.size < 64 && (value < min || value > max)) {
            warning("Value " + value + " does not fit in " + fix.size + " bits", fix.line);
        }
        value &= max;

        // Match buildInstruction semantics: swap full source width first
        if ("little".equals(fix.endian)) {
            value = swapEndian64(value, fix.size);
        }

        if (fix.srcofs > 0) {
            value >>>= fix.srcofs;
        }
        value &= mask64(fix.dstlen);

        for (int i = 0; i < fix.dstlen; i++) {
            int dstBit0 = fix.dstofs + i;
            int dstWord = fix.ofs + (dstBit0 / this.width);
            int dstBit = this.width - 1 - (dstBit0 % this.width);

            int outIndex = dstWord - sec.origin;
            if (outIndex < 0) {
                warning("Fixup for '" + fix.sym + "' writes before section origin (" + sec.name + ")", fix.line);
                return;
            }
            while (outIndex >= sec.outwords.size()) sec.outwords.add(0);

            int bitMask = (int) (1L << dstBit);

            int cur = sec.outwords.get(outIndex) & ~bitMask;

            long srcBit = (value >>> (fix.dstlen - 1 - i)) & 1L;
            if (srcBit != 0) cur |= bitMask;

            cur &= mask32(this.width);
            sec.outwords.set(outIndex, cur);
        }
    }

    void applyFixup(AssemblerFixup fix, long resolvedValue) {
        if (curSec == null) {
            switchSection(".text", false);
        }
        applyFixup(curSec, fix, resolvedValue);
    }

    public void applyFixup(AssemblerFixup fix, Symbol sym) {
        applyFixup(fix, sym.value);
    }

    public AssemblerState finish() {
        if (aborted) {
            return state();
        }

        // Resolve fixups per section
        for (SectionState sec : sections.values()) {
            if (sec.bss) {
                sec.fixups.clear();
                continue;
            }

            for (AssemblerFixup fix : sec.fixups) {
                Long ev;
                try {
                    ev = evalExprAllowLocals(fix.sym, fix.ofs);
                } catch (IllegalArgumentException ex) {
                    warning("Bad fixup expr '" + fix.sym + "': " + ex.getMessage());
                    ev = null;
                }

                if (ev == null) {
                    warning("Unresolved symbol/expression '" + fix.sym + "'");
                } else {
                    applyFixup(sec, fix, ev);
                }
            }
            sec.fixups.clear();

            // Per-section .len padding
            if (sec.codelen > 0) {
                while (sec.outwords.size() < sec.codelen) {
                    sec.outwords.add(0);
                }
            }
        }

        // Fill listing bytes (insns) for each source line, using that line's section
        int digits = (int) Math.ceil(this.width / 4.0);
        for (AssemblerLine al : this.asmlines) {
            SectionState sec = sections.get(al.section);
            if (sec == null || sec.bss) {
                al.insns = "";
                continue;
            }
            int nb = al.nbits / this.width;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < nb; j++) {
                int addr = al.offset + j;
                int idx = addr - sec.origin;
                int w = 0;
                if (idx >= 0 && idx < sec.outwords.size()) {
                    w = sec.outwords.get(idx);
                }
                sb.append(String.format("%0" + digits + "X", w));
                if (j != nb - 1) sb.append(' ');
            }
            al.insns = sb.toString();
        }

        // Build flat binary by concatenating sections in deterministic order:
        // .text, then .data, then any other progbits sections in insertion order. (.bss does not emit bytes.)
        List<String> order = new ArrayList<>();
        if (sections.containsKey(".text")) order.add(".text");
        if (sections.containsKey(".data")) order.add(".data");
        for (String k : sections.keySet()) {
            if (k.equals(".bss")) continue;
            if (order.contains(k)) continue;
            order.add(k);
        }

        final Map<String, Object> secMeta = new LinkedHashMap<>();
        final List<Integer> flat = new ArrayList<>();
        int flatOfsWords = 0;

        for (String secName : order) {
            SectionState sec = sections.get(secName);
            if (sec == null || sec.bss) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bss", false);
            m.put("origin_words", sec.origin);
            m.put("origin_bytes", wordsToBytes(sec.origin));
            m.put("size_words", sec.outwords.size());
            m.put("size_bytes", wordsToBytes(sec.outwords.size()));
            m.put("flat_start_words", flatOfsWords);
            m.put("flat_start_bytes", wordsToBytes(flatOfsWords));
            secMeta.put(secName, m);

            flat.addAll(sec.outwords);
            flatOfsWords += sec.outwords.size();
        }

        // Record BSS metadata too (no bytes emitted)
        if (sections.containsKey(".bss")) {
            SectionState b = sections.get(".bss");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bss", true);
            m.put("origin_words", b.origin);
            m.put("origin_bytes", wordsToBytes(b.origin));
            m.put("size_words", Math.max(0, b.ip - b.origin));
            m.put("size_bytes", wordsToBytes(Math.max(0, b.ip - b.origin)));
            secMeta.put(".bss", m);
        }

        final Map<String, Object> intermediate = new LinkedHashMap<>();
        intermediate.put("section_order", order);
        intermediate.put("sections", secMeta);

        this.finalOutwords = flat;
        this.finalIntermediate = intermediate;

        return state();
    }

    public AssemblerState assembleFile(String text) {
        assembleChunk(text);
        return this.finish();
    }

    public AssemblerState assemblePath(java.nio.file.Path file) {
        java.nio.file.Path prev = currentSourceDir;
        String prevName = currentSourceName;
        try {
            java.nio.file.Path abs = file.toAbsolutePath().normalize();
            currentSourceName = abs.toString();
            currentSourceDir = abs.getParent();
            if (currentSourceDir != null) sourceDirStack.push(currentSourceDir);
            String text = java.nio.file.Files.readString(abs);
            assembleChunk(text);
            return this.finish();
        } catch (IOException e) {
            this.fatal("Cannot read file: " + file + " (" + e.getMessage() + ")");
            return this.finish();
        } finally {
            if (!sourceDirStack.isEmpty()) sourceDirStack.pop();
            currentSourceDir = prev;
            currentSourceName = prevName;
        }
    }

    private void assembleChunk(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length && !this.aborted; i++) {
            try {
                this.assembleAt(lines[i], this.currentSourceName, i + 1);
            } catch (Exception e) {
                e.printStackTrace();
                this.fatal("Exception during assembly: " + e, i + 1);
            }
        }

        if (macroDefActive && !this.aborted) {
            fatal("Unterminated .macro \"" + macroDefName + "\" (missing .endm)");
            macroDefActive = false;
            macroDefName = null;
            macroDefKey = null;
            macroDefParams = null;
            macroDefBody = null;
        }
    }

    public AssemblerState state() {
        AssemblerState assemblerState = new AssemblerState();

        if (curSec == null) {
            switchSection(".text", false);
        }

        List<Integer> out = (finalOutwords != null)
                ? finalOutwords
                : curSec.outwords;

        assemblerState.ip = curSec.ip;
        assemblerState.line = this.linenum;
        assemblerState.origin = curSec.origin;
        assemblerState.codelen = out.size();

        assemblerState.intermediate = (finalIntermediate != null)
                ? finalIntermediate
                : new HashMap<>();

        assemblerState.output = new ArrayList<>(out);
        assemblerState.lines = this.asmlines;
        assemblerState.errors = this.errors;

        // Aggregate outstanding fixups (mostly useful before finish()).
        List<AssemblerFixup> fx = new ArrayList<>();
        for (SectionState sec : sections.values()) {
            fx.addAll(sec.fixups);
        }
        assemblerState.fixups = fx;

        return assemblerState;
    }

    public String loadInclude(String path) {
        path = unquote(path);
        return loadAndAssembleExternalText(path, java.util.List.of("", "include/"));
    }

    public String loadModule(String path) {
        path = unquote(path);
        // Currently "module" is treated like "include" (plain text).
        return loadAndAssembleExternalText(path, java.util.List.of("", "modules/", "include/"));
    }

    private String loadAndAssembleExternalText(String ref, java.util.List<String> resourcePrefixes) {
        if (ref == null || ref.isBlank()) return "Empty path";
        ref = ref.trim();

        // 1) Filesystem (relative to current source dir, include search paths, then CWD)
        java.nio.file.Path file = null;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(ref);

            java.util.List<java.nio.file.Path> probes = new java.util.ArrayList<>();
            if (!p.isAbsolute()) {
                if (currentSourceDir != null) probes.add(currentSourceDir.resolve(p));
                for (java.nio.file.Path inc : includeSearchPaths) {
                    if (inc == null) continue;
                    java.nio.file.Path base = inc;
                    if (!base.isAbsolute() && currentSourceDir != null) base = currentSourceDir.resolve(base);
                    probes.add(base.resolve(p));
                }
            }
            probes.add(p);

            for (java.nio.file.Path cand : probes) {
                try {
                    java.nio.file.Path c = cand.normalize();
                    if (java.nio.file.Files.exists(c)) {
                        file = c.toAbsolutePath().normalize();
                        break;
                    }
                } catch (Exception ignore) {
                    // try next
                }
            }
        } catch (Exception ignore) {
            // fall through
        }

        String resolvedKey = (file != null) ? file.toString() : ("classpath:" + ref);

        // recursion + depth guard
        if (includeStack.contains(resolvedKey)) return "Recursive include: " + resolvedKey;
        if (includeStack.size() >= maxIncludeDepth)
            return "Include depth exceeded (" + maxIncludeDepth + "): " + resolvedKey;

        includeStack.push(resolvedKey);

        String text = null;
        String err = null;

        java.nio.file.Path prev = currentSourceDir;
        String prevName = currentSourceName;

        try {
            if (file != null) {
                try {
                    text = java.nio.file.Files.readString(file);
                    currentSourceDir = file.getParent();
                    if (currentSourceDir != null) sourceDirStack.push(currentSourceDir);
                    currentSourceName = file.toString();
                } catch (IOException e) {
                    err = "Cannot read: " + file + " (" + e.getMessage() + ")";
                }
            } else {
                // 2) Classpath
                currentSourceName = ref;
                ClassLoader cl = getClass().getClassLoader();
                for (String prefix : resourcePrefixes) {
                    String res = (prefix == null ? "" : prefix) + ref;
                    String clPath = res.startsWith("/") ? res.substring(1) : res;
                    try (InputStream in = cl.getResourceAsStream(clPath)) {
                        if (in == null) continue;
                        text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        break;
                    } catch (IOException ignore) {
                        // try next
                    }
                }
                if (text == null) err = "Cannot find: " + ref;
            }

            if (err != null) return err;
            assembleChunk(text);
            return null;
        } finally {
            if (!sourceDirStack.isEmpty()) sourceDirStack.pop();
            currentSourceDir = prev;
            currentSourceName = prevName;
            if (!includeStack.isEmpty()) includeStack.pop();
        }
    }

    // ---------- External binary loading (.incbin) ----------

    static String stripTrailingComma(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.endsWith(",")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    private byte[] loadExternalBinary(String ref, java.util.List<String> resourcePrefixes) {
        if (ref == null || ref.isBlank()) return null;
        ref = ref.trim();

        // 1) Filesystem (relative to current source dir, include search paths, then CWD)
        java.nio.file.Path file = null;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(ref);

            java.util.List<java.nio.file.Path> probes = new java.util.ArrayList<>();
            if (!p.isAbsolute()) {
                if (currentSourceDir != null) probes.add(currentSourceDir.resolve(p));

                for (java.nio.file.Path inc : includeSearchPaths) {
                    if (inc == null) continue;
                    java.nio.file.Path base = inc;
                    if (!base.isAbsolute() && currentSourceDir != null) base = currentSourceDir.resolve(base);
                    probes.add(base.resolve(p));
                }
            }
            probes.add(p);

            for (java.nio.file.Path cand : probes) {
                try {
                    java.nio.file.Path c = cand.normalize();
                    if (java.nio.file.Files.exists(c)) {
                        file = c.toAbsolutePath().normalize();
                        break;
                    }
                } catch (Exception ignore) {
                    // try next
                }
            }
        } catch (Exception ignore) {
            // fall through
        }

        try {
            if (file != null) {
                return java.nio.file.Files.readAllBytes(file);
            }

            // 2) Classpath
            ClassLoader cl = getClass().getClassLoader();
            for (String prefix : resourcePrefixes) {
                String res = (prefix == null ? "" : prefix) + ref;
                String clPath = res.startsWith("/") ? res.substring(1) : res;
                try (InputStream in = cl.getResourceAsStream(clPath)) {
                    if (in == null) continue;
                    return in.readAllBytes();
                } catch (IOException ignore) {
                    // try next
                }
            }
        } catch (IOException ignore) {
            return null;
        }

        return null;
    }

    // ---------- ANTLR one-line parsing (non-fatal; falls back to legacy) ----------

    static final class ParsedLine {
        final String label;
        final String instruction;           // e.g. "add x1,x2,x3" or "sw x1,0(x0)"
        final String[] directiveTokens;     // e.g. [".org","4096"]
        final String syntaxError;
        final Integer syntaxErrorCol;       // 1-based, best-effort

        ParsedLine(String label, String instruction, String[] directiveTokens, String syntaxError, Integer syntaxErrorCol) {
            this.label = label;
            this.instruction = instruction;
            this.directiveTokens = directiveTokens;
            this.syntaxError = syntaxError;
            this.syntaxErrorCol = syntaxErrorCol;
        }

        static ParsedLine parse(String line) {
            try {
                // Generated by src/main/antlr4/.../R8Asm.g4 (optional; falls back if missing)
                CharStream cs = CharStreams.fromString(line);

                Class<?> lexerCls;
                try {
                    lexerCls = Class.forName(Assembler.class.getPackageName() + ".R8AsmLexer");
                } catch (ClassNotFoundException e) {
                    lexerCls = Class.forName("R8AsmLexer");
                }
                Lexer lexer = (Lexer) lexerCls.getConstructor(CharStream.class).newInstance(cs);

                CommonTokenStream ts = new CommonTokenStream(lexer);

                Class<?> parserCls;
                try {
                    parserCls = Class.forName(Assembler.class.getPackageName() + ".R8AsmParser");
                } catch (ClassNotFoundException e) {
                    parserCls = Class.forName("R8AsmParser");
                }
                Parser parser = (Parser) parserCls.getConstructor(TokenStream.class).newInstance(ts);

                CollectingErrorListener el = new CollectingErrorListener();
                lexer.removeErrorListeners();
                parser.removeErrorListeners();
                lexer.addErrorListener(el);
                parser.addErrorListener(el);

                Object ctx = parserCls.getMethod("oneLine").invoke(parser);

                if (!el.errors.isEmpty()) {
                    int col = el.errors.get(0).col + 1;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < el.errors.size(); i++) {
                        if (i > 0) sb.append("; ");
                        sb.append(el.errors.get(i).message);
                    }
                    return new ParsedLine(null, null, null, sb.toString(), col);
                }

                String label = null;
                Object labelDef = ctx.getClass().getMethod("labelDef").invoke(ctx);
                if (labelDef != null) {
                    Object identNode = labelDef.getClass().getMethod("IDENT").invoke(labelDef);
                    label = (String) identNode.getClass().getMethod("getText").invoke(identNode);
                }

                Object directive = ctx.getClass().getMethod("directive").invoke(ctx);
                if (directive != null) {
                    Object identNode = directive.getClass().getMethod("IDENT").invoke(directive);
                    String name = "." + ((String) identNode.getClass().getMethod("getText").invoke(identNode))
                            .toLowerCase(Locale.ROOT);

                    @SuppressWarnings("unchecked")
                    List<Object> args = (List<Object>) directive.getClass().getMethod("directiveArg").invoke(directive);

                    List<String> toks = new ArrayList<>();
                    toks.add(name);
                    for (Object a : args) {
                        String raw = (String) a.getClass().getMethod("getText").invoke(a);
                        toks.add(normalizeDirectiveArgText(raw));
                    }
                    return new ParsedLine(label, null, toks.toArray(new String[0]), null, null);
                }

                Object instruction = ctx.getClass().getMethod("instruction").invoke(ctx);
                if (instruction != null) {
                    Object identNode = instruction.getClass().getMethod("IDENT").invoke(instruction);
                    String mnemonic = ((String) identNode.getClass().getMethod("getText").invoke(identNode))
                            .toLowerCase(Locale.ROOT);

                    @SuppressWarnings("unchecked")
                    List<Object> ops = (List<Object>) instruction.getClass().getMethod("operand").invoke(instruction);

                    if (ops == null || ops.isEmpty())
                        return new ParsedLine(label, mnemonic, null, null, null);

                    List<String> opTxt = new ArrayList<>();
                    for (Object o : ops) {
                        opTxt.add((String) o.getClass().getMethod("getText").invoke(o));
                    }
                    String ins = mnemonic + " " + String.join(",", opTxt);
                    return new ParsedLine(label, ins, null, null, null);
                }

                return new ParsedLine(label, null, null, null, null);
            } catch (Throwable t) {
                // Missing generated classes, ANTLR not configured, etc.
                return null;
            }
        }
    }


    // Normalize directive arg text coming from ANTLR contexts:
    // - STRING tokens are returned with surrounding quotes; we strip and unescape.
    // - IDENT tokens are lower-cased for case-insensitive behavior.
    // - NUMBER tokens are preserved (underscores are handled by parseConst).
    private static String normalizeDirectiveArgText(String raw) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return unescapeString(raw.substring(1, raw.length() - 1));
        }
        // "Looks like" an identifier
        if (raw.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return raw.toLowerCase(Locale.ROOT);
        }
        return raw;
    }

    // Minimal unescape for STRING literals (supports \n, \r, \t, \", \\).
    private static String unescapeString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!esc) {
                if (c == '\\') {
                    esc = true;
                } else out.append(c);
                continue;
            }
            // esc == true
            esc = false;
            switch (c) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                default -> out.append(c); // unknown escapes: keep char
            }
        }
        if (esc) out.append('\\'); // trailing backslash
        return out.toString();
    }


// ----------------------------------------------------------------------
// Step 13 helpers: local labels (.Lfoo) and numeric locals (1f / 1b)
// ----------------------------------------------------------------------

    /**
     * Rewrites GAS-style dot-local labels (e.g. ".Lloop") to a grammar-legal form ("_Lloop").
     * Done as a same-length rewrite, and only outside of quoted strings.
     */
    private String rewriteDotLocals(String line) {
        if (line == null || line.isEmpty()) return line;

        StringBuilder out = new StringBuilder(line.length());
        boolean inQuote = false;
        boolean escaped = false;
        int segStart = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                if (!inQuote) {
                    // outside segment [segStart, i)
                    String seg = line.substring(segStart, i);
                    seg = DOT_LOCAL_PATTERN.matcher(seg).replaceAll("_L$1");
                    seg = NUM_REF_PATTERN.matcher(seg).replaceAll("__L$1$2");
                    out.append(seg);
                    // start quote segment at i
                    segStart = i;
                    inQuote = true;
                } else {
                    // quoted segment [segStart, i+1] untouched
                    out.append(line, segStart, i + 1);
                    segStart = i + 1;
                    inQuote = false;
                }
            }
        }

        if (segStart < line.length()) {
            String seg = line.substring(segStart);
            if (inQuote) {
                out.append(seg);
            } else {
                seg = DOT_LOCAL_PATTERN.matcher(seg).replaceAll("_L$1");
                seg = NUM_REF_PATTERN.matcher(seg).replaceAll("__L$1$2");
                out.append(seg);
            }
        }

        return out.toString();
    }

    /**
     * Record a numeric local definition like "1:" at the current IP.
     */
    private void defineNumericLabel(int n, int atIp) {
        numericLabels.computeIfAbsent(n, k -> new ArrayList<>()).add(atIp);
    }

    private Integer resolveNumericRef(int n, char dir, int dot) {
        List<Integer> addrs = numericLabels.get(n);
        if (addrs == null || addrs.isEmpty()) return null;

        if (dir == 'b') {
            for (int i = addrs.size() - 1; i >= 0; i--) {
                int a = addrs.get(i);
                if (a <= dot) return a;
            }
            return null;
        } else { // 'f'
            for (int a : addrs) {
                if (a > dot) return a;
            }
            return null;
        }
    }

    /**
     * Evaluate an expression while allowing numeric locals (1f/1b). Returns null if unresolved.
     * This is used both during assembly (to decide whether to create a fixup) and during finish()
     * (when forward refs should be resolvable).
     */
    private Long evalExprAllowLocals(String expr, int dot) {
        if (expr == null) return null;
        String e = expr.trim();
        if (e.isEmpty()) return 0L;

        // Normalize any ".Lfoo" that survived preprocessing (rare, but safe).
        e = DOT_LOCAL_PATTERN.matcher(e).replaceAll("_L$1");    // Replace numeric locals in-place with absolute addresses.
        // We rewrite 1f/1b to __L1f/__L1b before parsing so ANTLR doesn't split tokens.
        Matcher m2 = NUM_REF_REWRITTEN_PATTERN.matcher(e);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            int n = Integer.parseInt(m2.group(1));
            char dir = Character.toLowerCase(m2.group(2).charAt(0));
            Integer target = resolveNumericRef(n, dir, dot);
            if (target == null) return null; // defer (forward) or error later
            m2.appendReplacement(sb2, Matcher.quoteReplacement(Integer.toString(target)));
        }
        m2.appendTail(sb2);
        e = sb2.toString();

        Matcher m = NUM_REF_PATTERN.matcher(e);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            char dir = Character.toLowerCase(m.group(2).charAt(0));
            Integer target = resolveNumericRef(n, dir, dot);
            if (target == null) return null; // defer (forward) or error later
            m.appendReplacement(sb, Matcher.quoteReplacement(Integer.toString(target)));
        }
        m.appendTail(sb);
        e = sb.toString();

        return ExpressionEvaluator.eval(e, symbols, dot);
    }

    static final class CollectingErrorListener extends BaseErrorListener {
        static final class SyntaxIssue {
            final int line;
            final int col; // 0-based
            final String message;

            SyntaxIssue(int line, int col, String message) {
                this.line = line;
                this.col = col;
                this.message = message;
            }
        }

        final List<SyntaxIssue> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            errors.add(new SyntaxIssue(line, charPositionInLine, msg));
        }
    }
}
