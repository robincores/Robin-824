package org.robincores.r8.assembler;

import java.util.*;
import java.util.regex.*;

public class Assembler {

  static String hex(int v, int nd) {
    try {
      // Default to 2 digits if nd is not provided or is 0
      if (nd == 0) nd = 2;

      // If nd is 8, handle as two 4-digit parts
      if (nd == 8) {
        return hex((v >> 16) & 0xFFFF, 4) + hex(v & 0xFFFF, 4);
      }

      // Convert integer to hexadecimal string and ensure uppercase
      String s = Integer.toHexString(v).toUpperCase();

      // Pad the result with leading zeroes until it reaches the required length
      while (s.length() < nd) {
        s = "0" + s;
      }

      return s;
    } catch (Exception e) {
      // Fallback if there's an error
      return Integer.toString(v);
    }
  }

  static int[] stringToData(String s) {
    int[] data = new int[s.length()];

    // Loop through each character in the string and get its ASCII value
    for (int i = 0; i < s.length(); i++) {
      data[i] = s.charAt(i);
    }

    return data;
  }

  // ---
  AssemblerSpec spec;
  int ip = 0;
  int origin = 0;
  int linenum = 0;
  Map<String, Symbol> symbols = new HashMap<>();
  List<AssemblerError> errors = new ArrayList<>();
  List<Integer> outwords = new ArrayList<>();
  List<AssemblerLine> asmlines = new ArrayList<>();
  List<AssemblerFixup> fixups = new ArrayList<>();
  int width = 8;
  int codelen = 0;
  boolean aborted = false;

  public Assembler(AssemblerSpec spec) {
    this.spec = spec;
    if (spec != null) {
      preprocessRules();
    }
  }

  // Converts a rule to a regular expression and stores it
  void rule2regex(AssemblerRule rule, Map<String, AssemblerVar> vars) {
    String s = rule.fmt;
    if (s == null || !(s instanceof String)) {
      throw new IllegalArgumentException("Each rule must have a 'fmt' string field");
    }
    if (rule.bits == null || !(rule.bits instanceof List)) {
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
        replacement = "(\\w+)";  // Enum-like variable, expects a word match
      } else {
        // Escape $ sign for immediate value or constant
        replacement = "([0-9]+|\\$[0-9a-f]+|\\w+)";
      }

      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);  // Append the remaining part of the string

    // Compile the final regex
    try {
      rule.re = Pattern.compile("^" + result.toString() + "$", Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Bad regex for rule '" + rule.fmt + "': " + result + " -- " + e);
    }

    rule.varlist = varlist;
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

  // Add a warning message to the errors list
  void warning(String msg, Integer line) {
    // If line is null, use the current line number (linenum)
    this.errors.add(new AssemblerError(msg, (line != null) ? line : this.linenum));
  }

  void warning(String msg) {
    warning(msg, null);
  }

  // Mark an error as fatal and add it to the errors list
  void fatal(String msg, Integer line) {
    // Call the warning method to add the error message
    this.warning(msg, line);
    // Set aborted to true to indicate a fatal error
    this.aborted = true;
  }

  void fatal(String msg) {
    fatal(msg, null);
  }

  // Trigger a fatal error if a message is provided
  void fatalIf(String msg, Integer line) {
    // If the message is not null, call fatal to mark the error as fatal
    if (msg != null) {
      this.fatal(msg, line);
    }
  }

  void fatalIf(String msg) {
    fatalIf(msg, null);
  }

  void addBytes(AssemblerInstruction result) {
    this.asmlines.add(new AssemblerLine(this.linenum, this.ip, result.nbits));
    int opcode = result.opcode;
    int nb = result.nbits / this.width;

    for (int i = 0; i < nb; i++) {
      // If the width is less than 32 bits, we need to shift and mask the bits accordingly
      if (this.width < 32) {
        this.outwords.add((opcode >> ((nb - 1 - i) * this.width)) & ((1 << this.width) - 1));
      } else {
        // For larger widths (32 bits or more), we just store the opcode directly
        this.outwords.add(opcode);
      }
      this.ip++;
    }
  }

  void addWords(int[] data) {
    // Add a new AssemblerLine entry to the asmlines list
    this.asmlines.add(new AssemblerLine(this.linenum, this.ip, this.width * data.length));

    // Loop through the data array
    for (int i = 0; i < data.length; i++) {
      if (this.width < 32) {
        // If width is less than 32 bits, we mask the data appropriately and add it to outwords
        this.outwords.add(data[i] & ((1 << this.width) - 1));
      } else {
        // For widths 32 bits or larger, add the data directly to outwords
        this.outwords.add(data[i]);
      }
      // Increment the instruction pointer
      this.ip++;
    }
  }

  int[] parseData(String[] toks) {
    // Create an array to hold the parsed data
    int[] data = new int[toks.length];

    // Loop through the tokens and parse each one as a constant
    for (int i = 0; i < toks.length; i++) {
      data[i] = this.parseConst(toks[i]);
    }

    // Return the parsed data array
    return data;
  }

  void alignIP(int align) {
    // Check if the alignment value is valid
    if (align < 1 || align > this.codelen) {
      this.fatal("Invalid alignment value");
    } else {
      // Adjust the instruction pointer (ip) based on alignment
      this.ip = ((this.ip + align - 1) / align) * align;
    }
  }

  public boolean isNaN(int value) {
    return value == Integer.MIN_VALUE; // You can use a specific value to represent "NaN" in your context
  }

  // Parse a constant, handling numeric and symbol parsing
  int parseConst(String s, Integer nbits) {
    // TODO: check bit length if necessary using nbits

    try {
      // Check if the string is hexadecimal (prefixed with "0x" or "$")
      if (s.startsWith("0x") || s.startsWith("$")) {
        // Parse as a hexadecimal value
        return Integer.parseInt(s.replaceFirst("0x", "").replaceFirst("\\$", ""), 16);
      } else {
        // Otherwise, parse as a decimal value
        return Integer.parseInt(s);
      }
    } catch (NumberFormatException e) {
      return Integer.MIN_VALUE; // Return a sentinel value to indicate an invalid number
    }
  }

  int parseConst(String s) {
    return parseConst(s, null);
  }

  // Helper method to swap endian of a value
  int swapEndian(int value, int nbits) {
    int y = 0;
    while (nbits > 0) {
      int n = Math.min(nbits, width);
      int mask = (1 << n) - 1;
      y <<= n;
      y |= (value & mask);
      value >>>= n;
      nbits -= n;
    }
    return y;
  }

  // Build an instruction based on the matched rule
  public AssemblerLineResult buildInstruction(AssemblerRule rule, Matcher m) {
    int opcode = 0;
    int oplen = 0;

    // Iterate over each component of the rule output ("bits")
    for (Object b : rule.bits) {
      int n, x;

      // If it's a string, then it's a bit constant
      if (b instanceof String) {
        n = ((String) b).length();
        x = Integer.parseInt((String) b, 2);  // Parse as binary
      } else {
        // If it's a slice {a,b,n} or just a number
        int index = (b instanceof Number) ? ((Number) b).intValue() : ((AssemblerRuleSlice) b).a;

        // It's an indexed variable, look up its variable
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

        // If it's an enumerated type, look up the index of its keyword
        if (v.toks != null) {
          x = v.toks.indexOf(id);
          if (x < 0) {
            return new AssemblerErrorResult("Can't use '" + id + "' here, only one of: " + v.toks);
          }
        } else {
          // Otherwise, parse it as a constant
          x = parseConst(id, n);

          // Is it a label? Add fixup
          if (isNaN(x)) {
            this.fixups.add(new AssemblerFixup(
                id, this.ip, v.bits, 0, oplen, n, this.linenum,
                v.iprel, v.ipofs, v.ipmul == 0 ? 1 : v.ipmul, v.endian
            ));
            x = 0;
          } else {
            int mask = (1 << v.bits) - 1;
            if ((x & mask) != x) {
              return new AssemblerErrorResult("Value " + x + " does not fit in " + v.bits + " bits");
            }
          }
        }

        // If little endian, swap the byte order
        if ("little".equals(v.endian)) {
          x = swapEndian(x, v.bits);
        }

        // Is it an array slice? Slice the bits
        if (!(b instanceof Number)) {
          x = (x >>> shift) & ((1 << ((AssemblerRuleSlice) b).n) - 1);
        }
      }

      // Add bits to opcode
      opcode = (opcode << n) | x;
      oplen += n;
    }

    if (oplen == 0) {
      warning("Opcode had zero length");
    } else if (oplen > 32) {
      warning("Opcodes > 32 bits not supported");
    } else if ((oplen % width) != 0) {
      warning("Opcode was not word-aligned (" + oplen + " bits)");
    }

    return new AssemblerInstruction(opcode, oplen);
  }

  public String loadArch(String arch) {
    Map<String, Object> json = this.loadJSON(arch + ".json");
    if (json != null && json.containsKey("vars") && json.containsKey("rules")) {
      this.spec = (AssemblerSpec) json; // Assuming json is castable to AssemblerSpec
      this.preprocessRules();
    } else {
      return "Could not load arch file '" + arch + ".json'";
    }

    return null; // Return null to indicate success
  }

  void parseDirective(String[] tokens) {
    String cmd = tokens[0].toLowerCase();

    switch (cmd) {
      case ".define":
        symbols.put(tokens[1].toLowerCase(), new Symbol(Integer.parseInt(tokens[2])));
        break;

      case ".org":
        ip = origin = Integer.parseInt(tokens[1]);
        break;

      case ".len":
        codelen = Integer.parseInt(tokens[1]);
        break;

      case ".width":
        width = Integer.parseInt(tokens[1]);
        break;

      case ".arch":
        fatalIf(loadArch(tokens[1]));
        break;

      case ".include":
        fatalIf(loadInclude(tokens[1]));
        break;

      case ".module":
        fatalIf(loadModule(tokens[1]));
        break;

      case ".data":
        addWords(parseData(Arrays.copyOfRange(tokens, 1, tokens.length)));
        break;

      case ".string":
        addWords(stringToData(String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length))));
        break;

      case ".align":
        alignIP(parseConst(tokens[1]));
        break;

      default:
        warning("Unrecognized directive: " + String.join(" ", tokens));
        break;
    }
  }

  // Assemble a line of assembly code
  public AssemblerInstruction assemble(String line) {
    this.linenum++;

    // remove comments
    line = line.replaceAll(";.*", "").trim();

    // Ensure the line is not empty before accessing characters
    if (line.isEmpty()) {
      return null; // Skip empty lines
    }

    // is it a directive?
    if (line.charAt(0) == '.') {
      String[] tokens = line.split("\\s+");
      this.parseDirective(tokens);
      return null;
    }

    // make it lowercase
    line = line.toLowerCase();

    // find labels using Pattern and Matcher
    Pattern pattern = Pattern.compile("(\\w+):");
    Matcher matcher = pattern.matcher(line);
    while (matcher.find()) {
      String label = matcher.group(1);
      this.symbols.put(label, new Symbol(this.ip)); // Store label with current ip
    }
    line = matcher.replaceAll(""); // Replace labels with an empty string

    line = line.trim();
    if (line.isEmpty()) {
      return null; // empty line
    }

    // check if spec is loaded
    if (this.spec == null) {
      this.fatal("Need to load .arch first");
      return null;
    }

    String lastError = null;
    for (AssemblerRule rule : this.spec.rules) {
      Matcher m = rule.re.matcher(line);
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

    this.warning(lastError != null ? lastError : "Could not decode instruction: " + line);
    return null;
  }

  // Apply fixups for unresolved symbols after instruction assembly
  public void applyFixup(AssemblerFixup fix, Symbol sym) {
    int ofs = fix.ofs + (int) Math.floor(fix.dstofs / this.width);
    int mask = (1 << fix.size) - 1;
    int value = this.parseConst(Integer.toString(sym.value), fix.dstlen);

    if (fix.iprel) {
      value = (value - fix.ofs) * fix.ipmul - fix.ipofs;
    }

    if (fix.srcofs == 0 && (value > mask || value < -mask)) {
      this.warning("Symbol " + fix.sym + " (" + value + ") does not fit in " + fix.dstlen + " bits", fix.line);
    }

    if (fix.srcofs > 0) {
      value >>>= fix.srcofs;
    }
    value &= (1 << fix.dstlen) - 1;

    // If width is 32 bits
    if (this.width == 32) {
      int shift = 32 - fix.dstofs - fix.dstlen;
      value <<= shift;
    }

    // Apply fixup when the size is less than or equal to the width
    if (fix.size <= this.width) {
      int index = ofs - this.origin;
      int currentValue = index < outwords.size() ? outwords.get(index) : 0;
      outwords.set(index, currentValue ^ value);
    } else {
      // If big endian, swap the byte order
      if ("big".equals(fix.endian)) {
        value = this.swapEndian(value, fix.size);
      }

      // Apply multi-byte fixup
      while (value != 0) {
        int index = ofs - this.origin;
        int currentValue = index < outwords.size() ? outwords.get(index) : 0;

        if ((value & currentValue) != 0) {
          this.warning("Instruction bits overlapped: " + hex(currentValue, 8) +
              " " + hex(value, 8), null);
        } else {
          outwords.set(index, currentValue ^ (value & ((1 << this.width) - 1)));
        }

        value >>>= this.width;
        ofs++;
      }
    }
  }

  // Finalize the assembly process and apply all fixups
  public AssemblerState finish() {
    // Apply fixups
    for (AssemblerFixup fix : this.fixups) {
      Symbol sym = this.symbols.get(fix.sym);
      if (sym != null) {
        this.applyFixup(fix, sym);
      } else {
        this.warning("Symbol '" + fix.sym + "' not found");
      }
    }

    // Update asmlines
    for (AssemblerLine al : this.asmlines) {
      al.insns = "";
      for (int j = 0; j < al.nbits / this.width; j++) {
        int index = al.offset + j - this.origin;
        int word = (index < outwords.size()) ? outwords.get(index) : 0;
        if (j > 0) al.insns += " ";
        al.insns += hex(word, this.width / 4);
      }
    }

    // Ensure outwords list has enough elements to match codelen
    while (outwords.size() < this.codelen) {
      outwords.add(0);
    }

    // Clear fixups and return the final state
    this.fixups.clear();
    return this.state();
  }

  // Assemble an entire file of assembly code
  public AssemblerState assembleFile(String text) {
    String[] lines = text.split("\\n");

    for (int i = 0; i < lines.length && !this.aborted; i++) {
      try {
        this.assemble(lines[i]);
      } catch (Exception e) {
        e.printStackTrace();
        this.fatal("Exception during assembly: " + e);
      }
    }

    return this.finish();
  }

  // Return the assembler state after finishing
  public AssemblerState state() {
    AssemblerState assemblerState = new AssemblerState();

    assemblerState.ip = this.ip;
    assemblerState.line = this.linenum;
    assemblerState.origin = this.origin;
    assemblerState.codelen = this.codelen;
    assemblerState.intermediate = new HashMap<>(); // Placeholder for intermediate (TODO)
    assemblerState.output = new ArrayList<>(this.outwords); // Deep copy of the output
    assemblerState.lines = new ArrayList<>(this.asmlines); // Deep copy of the lines
    assemblerState.errors = new ArrayList<>(this.errors); // Deep copy of the errors
    assemblerState.fixups = new ArrayList<>(this.fixups); // Deep copy of the fixups

    return assemblerState;
  }

  // Assuming loadJSON parses the JSON file and returns a Map or custom object
  public Map<String, Object> loadJSON(String path) {
    // Implement JSON loading logic here
    // For example, use a JSON library like Jackson or Gson to parse the file
    // This is just a placeholder implementation:
    return new HashMap<>(); // Replace with actual JSON parsing
  }

  public String loadInclude(String path) {
    // Implement logic to load an include file from the given path
    // Placeholder implementation:
    return ""; // Replace with actual file reading logic
  }

  public String loadModule(String path) {
    // Implement logic to load a module file from the given path
    // Placeholder implementation:
    return ""; // Replace with actual module loading logic
  }
}
