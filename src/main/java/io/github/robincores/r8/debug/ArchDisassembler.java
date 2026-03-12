package io.github.robincores.r8.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.robincores.r8.bus.Bus;
import io.github.robincores.r8.cpu.R8Core;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON-driven disassembler for the R8 ISA family.
 *
 * <p>Loads arch specs from classpath (or dev filesystem fallbacks):
 * <ul>
 *   <li>toolchain/r8arch/r816.json</li>
 *   <li>toolchain/r8arch/r824.json</li>
 *   <li>toolchain/r8arch/r832.json</li>
 * </ul>
 *
 * <p>Builds an opcode decode table (256 entries) from "rules".
 * The decoder chooses the most-specific matching rule (highest number of fixed bits),
 * ties broken by earliest rule order.
 */
public final class ArchDisassembler {

    public record Decoded(int addr, byte[] bytes, String text, int nextAddr) {}

    private static final Gson GSON = new Gson();
    private static final Map<String, ArchDisassembler> CACHE = new ConcurrentHashMap<>();

    /** Candidate resource directories to search for {@code <arch>.json}. */
    private static final String[] ARCH_RESOURCE_DIRS = {
            "toolchain/r8arch/",
            "toolchain/r8as/",
            "io/github/robincores/toolchain/r8as/",
            "io/github/robincores/r8/debug/",
            ""
    };

    private final ArchSpec spec;
    private final Entry[] table = new Entry[256];

    private ArchDisassembler(ArchSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
        buildDecodeTable();
    }

    /** Pick ISA json from CPU address mask (0xFFFF -> r816, 0xFF_FFFF -> r824, 0xFFFF_FFFF -> r832). */
    public static ArchDisassembler forCpu(R8Core cpu) {
        int mask = cpu.addrMask();
        int addrBits = 32 - Integer.numberOfLeadingZeros(mask); // works for 2^n-1 masks
        String arch = "r8" + addrBits; // r816/r824/r832
        return forArch(arch);
    }

    public static ArchDisassembler forArch(String archName) {
        String key = archName.toLowerCase(Locale.ROOT);
        return CACHE.computeIfAbsent(key, ArchDisassembler::load);
    }

    // ---------------------------------------------------------------------
    // Spec loading (classpath + filesystem fallbacks)
    // ---------------------------------------------------------------------

    private static InputStream openArchSpecStream(String archName) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ArchDisassembler.class.getClassLoader();

        // 1) Try classpath candidates (relative + absolute)
        for (String dir : ARCH_RESOURCE_DIRS) {
            String rel = dir + archName + ".json";
            String abs = "/" + rel;

            InputStream in = cl.getResourceAsStream(rel);
            if (in != null) return in;

            in = ArchDisassembler.class.getResourceAsStream(abs);
            if (in != null) return in;
        }

        // 2) Try filesystem fallbacks (useful during development)
        try {
            java.nio.file.Path p1 = java.nio.file.Path.of("toolchain", "r8arch", archName + ".json");
            if (java.nio.file.Files.exists(p1)) return java.nio.file.Files.newInputStream(p1);

            java.nio.file.Path p2 = java.nio.file.Path.of(archName + ".json");
            if (java.nio.file.Files.exists(p2)) return java.nio.file.Files.newInputStream(p2);

            java.nio.file.Path p3 = java.nio.file.Path.of("spec", archName + ".json");
            if (java.nio.file.Files.exists(p3)) return java.nio.file.Files.newInputStream(p3);
        } catch (Exception ignored) {
            // fallthrough
        }

        return null;
    }

    private static ArchDisassembler load(String archName) {
        InputStream in = openArchSpecStream(archName);
        if (in == null) {
            throw new IllegalStateException("ISA JSON not found for: " + archName + " (tried classpath + fallbacks)");
        }

        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            ArchSpec spec = ArchSpec.fromJson(root);
            return new ArchDisassembler(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ISA JSON for: " + archName, e);
        }
    }

    // ---------------------------------------------------------------------
    // Public decode API
    // ---------------------------------------------------------------------

    public Decoded decodeAt(Bus bus, int addr, int addrMask) {
        int pc = addr & addrMask;
        int op = bus.read8(pc) & 0xFF;

        Entry e = table[op];
        if (e == null) {
            return new Decoded(pc, new byte[]{(byte) op}, ".db 0x" + hex2(op), (pc + 1) & addrMask);
        }

        Map<String, Integer> values = new HashMap<>();

        // Extract inline fields from opcode
        for (InlineField f : e.inlineFields) {
            int v = extractBits(op, f.lsb, f.width);
            values.put(f.varName, v);
        }

        // Read appended operands
        int p = (pc + 1) & addrMask;
        ArrayList<Byte> bytes = new ArrayList<>();
        bytes.add((byte) op);

        for (Operand o : e.operands) {
            if (!o.appended) continue;

            int byteLen = (o.bits + 7) / 8;
            int v = 0;

            if ("little".equalsIgnoreCase(o.endian)) {
                for (int i = 0; i < byteLen; i++) {
                    int b = bus.read8(p) & 0xFF;
                    bytes.add((byte) b);
                    v |= (b << (8 * i));
                    p = (p + 1) & addrMask;
                }
            } else {
                for (int i = 0; i < byteLen; i++) {
                    int b = bus.read8(p) & 0xFF;
                    bytes.add((byte) b);
                    v = (v << 8) | b;
                    p = (p + 1) & addrMask;
                }
            }

            if (o.bits < 32) v &= ((1 << o.bits) - 1);
            values.put(o.varName, v);
        }

        String text = formatInstruction(e, values, pc, addrMask);

        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) out[i] = bytes.get(i);

        return new Decoded(pc, out, text, p);
    }

    public List<Decoded> decodeMany(Bus bus, int startAddr, int count, int addrMask) {
        List<Decoded> out = new ArrayList<>(count);
        int a = startAddr & addrMask;
        for (int i = 0; i < count; i++) {
            Decoded d = decodeAt(bus, a, addrMask);
            out.add(d);
            a = d.nextAddr & addrMask;
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Build opcode table
    // ---------------------------------------------------------------------

    private void buildDecodeTable() {
        int order = 0;
        for (RuleSpec r : spec.rules) {
            Entry entry = buildEntry(r, order++);
            if (entry == null) continue;

            for (int op = 0; op <= 0xFF; op++) {
                if ((op & entry.mask) == entry.value) {
                    Entry prev = table[op];
                    if (prev == null || entry.fixedBits > prev.fixedBits
                            || (entry.fixedBits == prev.fixedBits && entry.order < prev.order)) {
                        table[op] = entry;
                    }
                }
            }
        }
    }

    private Entry buildEntry(RuleSpec rule, int order) {
        List<FmtOperand> fmtOps = parseFmtOperands(rule.fmt);
        List<String> varOrder = fmtOps.stream().map(FmtOperand::varName).toList();
        int varCursor = 0;

        List<BitPart> opcodeParts = new ArrayList<>();
        List<InlineField> inlineFields = new ArrayList<>();
        List<Operand> operands = new ArrayList<>();

        // Create operand metadata from fmt variables
        for (FmtOperand fo : fmtOps) {
            VarSpec vs = spec.vars.get(fo.varName);
            if (vs == null) continue;
            operands.add(new Operand(
                    fo.varName, fo.atPrefix,
                    vs.bits,
                    vs.toks,
                    vs.endian == null ? "little" : vs.endian,
                    vs.iprel != null && vs.iprel,
                    vs.ipofs,
                    false, false
            ));
        }

        int opcodeBitLen = 0;

        for (Object part : rule.bits) {
            if (part instanceof String s) {
                String bits = s.replace("_", "");
                for (int i = 0; i < bits.length(); i++) {
                    char c = bits.charAt(i);
                    if (c != '0' && c != '1') return null;
                    if (opcodeBitLen < 8) {
                        opcodeParts.add(new BitPart.Fixed(c == '1'));
                        opcodeBitLen++;
                    } else {
                        // ignore extra bits (should not happen)
                        return null;
                    }
                }
            } else if (part instanceof Integer) {
                if (varCursor >= varOrder.size()) return null;
                String varName = varOrder.get(varCursor++);
                VarSpec vs = spec.vars.get(varName);
                if (vs == null) return null;

                int w = vs.bits;
                if (w <= 0 || w > 32) return null;

                if (opcodeBitLen < 8) {
                    if (opcodeBitLen + w > 8) return null;

                    // MSB->LSB packing: current bit index = 7 - opcodeBitLen
                    int startBit = 7 - opcodeBitLen;
                    int endBit = startBit - (w - 1);
                    int lsb = endBit;

                    opcodeParts.add(new BitPart.Var(varName, w));
                    opcodeBitLen += w;

                    inlineFields.add(new InlineField(varName, lsb, w));
                    markOperandInline(operands, varName);
                } else {
                    markOperandAppended(operands, varName);
                }
            } else {
                return null;
            }
        }

        if (opcodeBitLen != 8) return null;

        // Compute mask/value
        int mask = 0;
        int value = 0;
        int bitIndex = 7;

        for (BitPart bp : opcodeParts) {
            if (bp instanceof BitPart.Fixed f) {
                mask |= (1 << bitIndex);
                if (f.one) value |= (1 << bitIndex);
                bitIndex--;
            } else if (bp instanceof BitPart.Var v) {
                bitIndex -= v.width;
            }
        }

        int fixedBits = Integer.bitCount(mask);

        // Keep only operands used by this rule (inline or appended)
        List<Operand> finalOps = new ArrayList<>();
        for (Operand o : operands) {
            if (o.inline || o.appended) finalOps.add(o);
        }

        return new Entry(rule.fmt, mask, value, fixedBits, order, inlineFields, finalOps);
    }

    private static void markOperandInline(List<Operand> ops, String varName) {
        for (int i = 0; i < ops.size(); i++) {
            Operand o = ops.get(i);
            if (o.varName.equals(varName)) {
                ops.set(i, o.withInline(true));
                return;
            }
        }
    }

    private static void markOperandAppended(List<Operand> ops, String varName) {
        for (int i = 0; i < ops.size(); i++) {
            Operand o = ops.get(i);
            if (o.varName.equals(varName)) {
                ops.set(i, o.withAppended(true));
                return;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------------

    private String formatInstruction(Entry e, Map<String, Integer> values, int pc, int addrMask) {
        String[] parts = e.fmt.trim().split("\\s+");
        String mnem = parts.length > 0 ? parts[0] : e.fmt;

        if (e.operands.isEmpty()) return mnem;

        List<String> rendered = new ArrayList<>();
        for (Operand o : e.operands) {
            Integer raw = values.get(o.varName);
            if (raw == null) continue;

            String s;
            if (o.iprel) {
                int signed = signExtend(raw, o.bits);
                int ipofs = (o.ipofs != null) ? o.ipofs : (1 + (o.bits + 7) / 8);
                int target = (pc + ipofs + signed) & addrMask;
                s = "0x" + hexAddr(target, addrMask);
            } else if (o.toks != null && raw >= 0 && raw < o.toks.size()) {
                s = o.toks.get(raw);
            } else {
                int digits = Math.max(1, Math.min(8, (o.bits + 3) / 4));
                s = "0x" + String.format("%0" + digits + "X", raw);
            }

            if (o.atPrefix) s = "@" + s;
            rendered.add(s);
        }

        return rendered.isEmpty() ? mnem : (mnem + " " + String.join(" ", rendered));
    }

    private static int signExtend(int v, int bits) {
        if (bits <= 0 || bits >= 32) return v;
        int sign = 1 << (bits - 1);
        int mask = (1 << bits) - 1;
        v &= mask;
        return ((v ^ sign) - sign);
    }

    private static int extractBits(int op, int lsb, int width) {
        int mask = (1 << width) - 1;
        return (op >>> lsb) & mask;
    }

    private static String hex2(int v) { return String.format("%02X", v & 0xFF); }

    private static String hexAddr(int v, int addrMask) {
        int bits = 32 - Integer.numberOfLeadingZeros(addrMask);
        int digits = Math.max(1, Math.min(8, (bits + 3) / 4));
        return String.format("%0" + digits + "X", v & addrMask);
    }

    // ---------------------------------------------------------------------
    // fmt parsing
    // ---------------------------------------------------------------------

    private static final Pattern FMT_VAR = Pattern.compile("(@)?~([A-Za-z0-9_]+)");

    private static List<FmtOperand> parseFmtOperands(String fmt) {
        List<FmtOperand> out = new ArrayList<>();
        Matcher m = FMT_VAR.matcher(fmt);
        while (m.find()) {
            boolean at = m.group(1) != null;
            String var = m.group(2);
            out.add(new FmtOperand(var, at));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Models
    // ---------------------------------------------------------------------

    private record FmtOperand(String varName, boolean atPrefix) {}

    private record InlineField(String varName, int lsb, int width) {}

    private static final class Entry {
        final String fmt;
        final int mask;
        final int value;
        final int fixedBits;
        final int order;
        final List<InlineField> inlineFields;
        final List<Operand> operands;

        Entry(String fmt, int mask, int value, int fixedBits, int order,
              List<InlineField> inlineFields, List<Operand> operands) {
            this.fmt = fmt;
            this.mask = mask;
            this.value = value;
            this.fixedBits = fixedBits;
            this.order = order;
            this.inlineFields = inlineFields;
            this.operands = operands;
        }
    }

    private record Operand(
            String varName,
            boolean atPrefix,
            int bits,
            List<String> toks,
            String endian,
            boolean iprel,
            Integer ipofs,
            boolean inline,
            boolean appended
    ) {
        Operand withInline(boolean v) { return new Operand(varName, atPrefix, bits, toks, endian, iprel, ipofs, v, appended); }
        Operand withAppended(boolean v) { return new Operand(varName, atPrefix, bits, toks, endian, iprel, ipofs, inline, v); }
    }

    private sealed interface BitPart {
        final class Fixed implements BitPart {
            final boolean one;
            Fixed(boolean one) { this.one = one; }
        }
        final class Var implements BitPart {
            final String varName;
            final int width;
            Var(String varName, int width) { this.varName = varName; this.width = width; }
        }
    }

    // ---------------------------------------------------------------------
    // JSON spec parsing (Gson -> internal POJOs)
    // ---------------------------------------------------------------------

    private static final class ArchSpec {
        final String name;
        final int width;
        final Map<String, VarSpec> vars;
        final List<RuleSpec> rules;

        private ArchSpec(String name, int width, Map<String, VarSpec> vars, List<RuleSpec> rules) {
            this.name = name;
            this.width = width;
            this.vars = vars;
            this.rules = rules;
        }

        static ArchSpec fromJson(JsonObject o) {
            String name = o.get("name").getAsString();
            int width = o.get("width").getAsInt();

            Map<String, VarSpec> vars = new HashMap<>();
            JsonObject jvars = o.getAsJsonObject("vars");
            for (String key : jvars.keySet()) {
                vars.put(key, VarSpec.fromJson(jvars.getAsJsonObject(key)));
            }

            List<RuleSpec> rules = new ArrayList<>();
            JsonArray jrules = o.getAsJsonArray("rules");
            for (JsonElement el : jrules) {
                rules.add(RuleSpec.fromJson(el.getAsJsonObject()));
            }

            return new ArchSpec(name, width, vars, rules);
        }
    }

    private static final class VarSpec {
        final int bits;
        final List<String> toks;
        final String endian;
        final Boolean iprel;
        final Integer ipofs;

        private VarSpec(int bits, List<String> toks, String endian, Boolean iprel, Integer ipofs) {
            this.bits = bits;
            this.toks = toks;
            this.endian = endian;
            this.iprel = iprel;
            this.ipofs = ipofs;
        }

        static VarSpec fromJson(JsonObject o) {
            int bits = o.get("bits").getAsInt();

            List<String> toks = null;
            if (o.has("toks") && o.get("toks").isJsonArray()) {
                toks = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("toks")) toks.add(e.getAsString());
            }

            String endian = o.has("endian") ? o.get("endian").getAsString() : "little";
            Boolean iprel = o.has("iprel") ? o.get("iprel").getAsBoolean() : Boolean.FALSE;
            Integer ipofs = o.has("ipofs") ? o.get("ipofs").getAsInt() : null;

            return new VarSpec(bits, toks, endian, iprel, ipofs);
        }
    }

    private static final class RuleSpec {
        final String fmt;
        final List<Object> bits;

        private RuleSpec(String fmt, List<Object> bits) {
            this.fmt = fmt;
            this.bits = bits;
        }

        static RuleSpec fromJson(JsonObject o) {
            String fmt = o.get("fmt").getAsString();
            List<Object> bits = new ArrayList<>();

            JsonArray arr = o.getAsJsonArray("bits");
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    bits.add(el.getAsString());
                } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                    bits.add(el.getAsInt()); // placeholder marker
                } else {
                    throw new IllegalArgumentException("Unsupported bits element: " + el);
                }
            }
            return new RuleSpec(fmt, bits);
        }
    }
}
