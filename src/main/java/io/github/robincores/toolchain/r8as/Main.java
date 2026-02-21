package io.github.robincores.toolchain.r8as;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * r8as CLI entry point.
 *
 * <p>Usage:
 * <pre>
 *   r8as [options] &lt;file.asm&gt; &lt;output.bin&gt;
 * </pre>
 *
 * <p>Options:
 * <ul>
 *   <li>-I &lt;dir&gt;              Add filesystem include search path (for .include/.module)</li>
 *   <li>-I&lt;dir&gt;               Same as above</li>
 *   <li>--out-endian &lt;big|little&gt;  Byte order when writing width &gt; 8 output (default: big)</li>
 *   <li>--image &lt;bytes&gt;        Write a fixed-size memory image and place sections by their .org origins</li>
 *   <li>--fill &lt;byte&gt;          Fill byte for --image holes (default: 0x00)</li>
 *   <li>--help, -h             Show this help</li>
 * </ul>
 *
 * <p>Notes:
 * <ul>
 *   <li>This CLI uses {@link Assembler#assemblePath(Path)} so relative includes resolve
 *       relative to the current source file directory.</li>
 *   <li>For width=8 (R816), output is one byte per emitted word.</li>
 *   <li>For width&gt;8, output is expanded to bytes per word using --out-endian.</li>
 *   <li>--image requires that sections use .org so they have meaningful origins.</li>
 * </ul>
 */
public final class Main {

  public static void main(String[] args) {
    if (args.length == 0 || hasArg(args, "--help") || hasArg(args, "-h")) {
      usageAndExit(0);
      return;
    }

    String outEndian = "big";
    int imageBytes = -1;
    int fillByte = 0x00;
    List<Path> includePaths = new ArrayList<>();
    List<String> pos = new ArrayList<>();

    for (int i = 0; i < args.length; i++) {
      String a = args[i];

      if ("-I".equals(a)) {
        if (i + 1 >= args.length) die("Missing value after -I");
        includePaths.add(Path.of(args[++i]));
        continue;
      }
      if (a.startsWith("-I") && a.length() > 2) {
        includePaths.add(Path.of(a.substring(2)));
        continue;
      }
      if ("--out-endian".equals(a)) {
        if (i + 1 >= args.length) die("Missing value after --out-endian");
        outEndian = args[++i].trim().toLowerCase();
        if (!"big".equals(outEndian) && !"little".equals(outEndian)) {
          die("Invalid --out-endian value: " + outEndian + " (expected: big|little)");
        }
        continue;
      }
      if ("--image".equals(a)) {
        if (i + 1 >= args.length) die("Missing value after --image");
        imageBytes = parseIntAuto(args[++i]);
        if (imageBytes <= 0) die("Invalid --image size: " + imageBytes);
        continue;
      }
      if ("--fill".equals(a)) {
        if (i + 1 >= args.length) die("Missing value after --fill");
        fillByte = parseIntAuto(args[++i]) & 0xFF;
        continue;
      }

      if (a.startsWith("-")) {
        die("Unknown option: " + a);
      }
      pos.add(a);
    }

    if (pos.size() != 2) {
      usageAndExit(1);
      return;
    }

    Path asmFile = Path.of(pos.get(0));
    Path outFile = Path.of(pos.get(1));

    Assembler assembler = new Assembler();
    for (Path p : includePaths) {
      assembler.addIncludePath(p);
    }

    AssemblerState state = assembler.assemblePath(asmFile);
    if (state == null || state.getErrors() == null) {
      die("Internal error: assembler returned null state");
      return;
    }

    if (!state.getErrors().isEmpty()) {
      for (AssemblerError e : state.getErrors()) {
        System.err.println(e.format());
      }
      System.exit(2);
      return;
    }

    try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
      int bpw = assembler.bytesPerWord();
      if (bpw <= 0) die("Invalid word width (bytesPerWord=" + bpw + ")");

      if (imageBytes > 0) {
        byte[] img = new byte[imageBytes];
        Arrays.fill(img, (byte) (fillByte & 0xFF));
        placeSectionsIntoImage(state, assembler, img, outEndian);
        fos.write(img);
      } else {
        writeFlat(state, assembler, fos, outEndian);
      }
    } catch (IOException e) {
      die("Error writing output file: " + e.getMessage());
    }
  }

  // ------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------

  private static int parseIntAuto(String s) {
    if (s == null) return 0;
    s = s.trim().toLowerCase().replace("_", "");
    if (s.startsWith("0x")) return (int) Long.parseLong(s.substring(2), 16);
    if (s.startsWith("$")) return (int) Long.parseLong(s.substring(1), 16);
    return (int) Long.parseLong(s, 10);
  }

  private static void writeFlat(AssemblerState state, Assembler assembler, FileOutputStream fos, String outEndian)
          throws IOException {
    int bpw = assembler.bytesPerWord();
    boolean big = "big".equals(outEndian);

    if (bpw == 1) {
      for (Integer w : state.getOutput()) {
        int v = (w == null) ? 0 : (w & 0xFF);
        fos.write(v);
      }
      return;
    }

    for (Integer w : state.getOutput()) {
      long v = (w == null) ? 0L : (w.longValue() & maskBytes(bpw));
      if (big) {
        for (int b = bpw - 1; b >= 0; b--) {
          fos.write((int) ((v >>> (b * 8)) & 0xFF));
        }
      } else {
        for (int b = 0; b < bpw; b++) {
          fos.write((int) ((v >>> (b * 8)) & 0xFF));
        }
      }
    }
  }

  /**
   * Place each PROGBITS section into the image based on its origin (origin_bytes).
   * BSS is skipped (NOBITS).
   *
   * <p>Requires the section metadata created by Assembler.finish():
   * intermediate = { section_order: [...], sections: { name: {origin_bytes,size_words,size_bytes,flat_start_words,bss} } }
   */
  @SuppressWarnings("unchecked")
  private static void placeSectionsIntoImage(AssemblerState state, Assembler assembler, byte[] img, String outEndian) {
    Object interObj = state.getIntermediate();
    if (!(interObj instanceof Map)) {
      die("Internal error: missing section metadata for --image (state.intermediate)");
      return;
    }

    Map<String, Object> inter = (Map<String, Object>) interObj;
    Object orderObj = inter.get("section_order");
    Object secsObj = inter.get("sections");

    if (!(orderObj instanceof List) || !(secsObj instanceof Map)) {
      die("Internal error: malformed section metadata for --image");
      return;
    }

    List<String> order = (List<String>) orderObj;
    Map<String, Object> secs = (Map<String, Object>) secsObj;

    int bpw = assembler.bytesPerWord();
    boolean big = "big".equals(outEndian);
    boolean[] used = new boolean[img.length];

    for (String secName : order) {
      Object metaObj = secs.get(secName);
      if (!(metaObj instanceof Map)) continue;

      Map<String, Object> m = (Map<String, Object>) metaObj;
      boolean bss = Boolean.TRUE.equals(m.get("bss"));
      if (bss) continue;

      int originBytes = ((Number) m.get("origin_bytes")).intValue();
      int sizeWords = ((Number) m.get("size_words")).intValue();
      int sizeBytes = ((Number) m.get("size_bytes")).intValue();
      int flatStartWords = ((Number) m.get("flat_start_words")).intValue();

      if (originBytes < 0 || originBytes + sizeBytes > img.length) {
        die("--image overflow: section " + secName
                + " spans 0x" + Integer.toHexString(originBytes)
                + "..0x" + Integer.toHexString(originBytes + sizeBytes)
                + " but image size is " + img.length + " bytes");
      }

      for (int wi = 0; wi < sizeWords; wi++) {
        int wordIndex = flatStartWords + wi;
        int w = (wordIndex >= 0 && wordIndex < state.getOutput().size() && state.getOutput().get(wordIndex) != null)
                ? state.getOutput().get(wordIndex)
                : 0;

        long v = ((long) w) & maskBytes(bpw);
        int base = originBytes + (wi * bpw);

        if (big) {
          int k = 0;
          for (int b = bpw - 1; b >= 0; b--) {
            int dst = base + (k++);
            int by = (int) ((v >>> (b * 8)) & 0xFF);
            if (used[dst]) die("--image overlap at 0x" + Integer.toHexString(dst) + " (section " + secName + ")");
            used[dst] = true;
            img[dst] = (byte) by;
          }
        } else {
          for (int b = 0; b < bpw; b++) {
            int dst = base + b;
            int by = (int) ((v >>> (b * 8)) & 0xFF);
            if (used[dst]) die("--image overlap at 0x" + Integer.toHexString(dst) + " (section " + secName + ")");
            used[dst] = true;
            img[dst] = (byte) by;
          }
        }
      }
    }
  }

  private static long maskBytes(int bytes) {
    if (bytes >= 8) return -1L;
    return (1L << (bytes * 8)) - 1L;
  }

  private static boolean hasArg(String[] args, String wanted) {
    for (String a : args) {
      if (wanted.equals(a)) return true;
    }
    return false;
  }

  private static void usageAndExit(int code) {
    System.out.println("Usage: r8as [options] <file.asm> <output.bin>");
    System.out.println("Options:");
    System.out.println("  -I <dir>             Add include search path (filesystem)");
    System.out.println("  -I<dir>              Same as above");
    System.out.println("  --out-endian <e>      e = big|little; byte order when width>8 output (default: big)");
    System.out.println("  --image <bytes>       Write a fixed-size memory image and place sections by .org origins");
    System.out.println("  --fill <byte>         Fill byte for --image holes (default: 0x00)");
    System.out.println("  --help, -h            Show this help");
    System.exit(code);
  }

  private static void die(String msg) {
    System.err.println("r8as: " + msg);
    System.err.println("Try: r8as --help");
    System.exit(1);
  }
}
