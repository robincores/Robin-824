package io.github.robincores.toolchain.r8as;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free integer expression evaluator for assembler constants.
 *
 * Supported:
 * - Literals: decimal, 0xHEX, $HEX (underscores allowed)
 * - Symbols: IDENT from the current symbol table, and '.' (current location / ip)
 * - Operators: unary + - ~, binary + - * / % << >> & ^ |
 * - Parentheses
 */
final class ExpressionEvaluator {

  private ExpressionEvaluator() {}

  /**
   * @return evaluated long, or null if it references an unknown symbol
   */
  static Long eval(String expr, Map<String, Symbol> symbols, int dot) {
    if (expr == null) return null;
    final Lexer lx = new Lexer(expr);
    final Parser p = new Parser(lx.lex(), symbols, dot);
    try {
      long v = p.parseExpr();
      if (p.hasMore()) {
        throw new IllegalArgumentException("Unexpected token: " + p.peek());
      }
      return v;
    } catch (UnknownSymbol us) {
      return null;
    }
  }

  // ---------------- Tokenization ----------------

  enum TokType {
    NUM, IDENT, DOT,
    LPAREN, RPAREN,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    LSHIFT, RSHIFT,
    AMP, CARET, BAR,
    TILDE,
    END
  }

  record Tok(TokType t, String s) {
    @Override public String toString() { return t + (s == null ? "" : ("(" + s + ")")); }
  }

  static final class Lexer {
    private final String in;
    private int i = 0;

    Lexer(String in) { this.in = in; }

    List<Tok> lex() {
      ArrayList<Tok> out = new ArrayList<>();
      while (true) {
        skipWs();
        if (i >= in.length()) { out.add(new Tok(TokType.END, null)); break; }

        char c = in.charAt(i);

        // two-char operators
        if (c == '<' && peek2('<')) { i += 2; out.add(new Tok(TokType.LSHIFT, "<<")); continue; }
        if (c == '>' && peek2('>')) { i += 2; out.add(new Tok(TokType.RSHIFT, ">>")); continue; }

        // single-char tokens
        switch (c) {
          case '(': i++; out.add(new Tok(TokType.LPAREN, "(")); continue;
          case ')': i++; out.add(new Tok(TokType.RPAREN, ")")); continue;
          case '+': i++; out.add(new Tok(TokType.PLUS, "+")); continue;
          case '-': i++; out.add(new Tok(TokType.MINUS, "-")); continue;
          case '*': i++; out.add(new Tok(TokType.STAR, "*")); continue;
          case '/': i++; out.add(new Tok(TokType.SLASH, "/")); continue;
          case '%': i++; out.add(new Tok(TokType.PERCENT, "%")); continue;
          case '&': i++; out.add(new Tok(TokType.AMP, "&")); continue;
          case '^': i++; out.add(new Tok(TokType.CARET, "^")); continue;
          case '|': i++; out.add(new Tok(TokType.BAR, "|")); continue;
          case '~': i++; out.add(new Tok(TokType.TILDE, "~")); continue;
          case '.': i++; out.add(new Tok(TokType.DOT, ".")); continue;
        }

        // number
        if (isDigit(c) || c == '$' || (c == '0' && (peekChar('x') || peekChar('X')))) {
          out.add(new Tok(TokType.NUM, readNumber()));
          continue;
        }

        // ident
        if (isIdentStart(c)) {
          out.add(new Tok(TokType.IDENT, readIdent()));
          continue;
        }

        throw new IllegalArgumentException("Illegal character in expression: '" + c + "' in \"" + in + "\"");
      }
      return out;
    }

    private void skipWs() {
      while (i < in.length()) {
        char c = in.charAt(i);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
        else break;
      }
    }

    private boolean peek2(char expected) {
      return (i + 1) < in.length() && in.charAt(i + 1) == expected;
    }

    private boolean peekChar(char expected) {
      return (i + 1) < in.length() && in.charAt(i + 1) == expected;
    }

    private String readNumber() {
      int start = i;
      if (in.charAt(i) == '$') {
        i++;
        while (i < in.length() && isHexOrUnderscore(in.charAt(i))) i++;
        return in.substring(start, i);
      }
      if (i + 1 < in.length() && in.charAt(i) == '0' && (in.charAt(i + 1) == 'x' || in.charAt(i + 1) == 'X')) {
        i += 2;
        while (i < in.length() && isHexOrUnderscore(in.charAt(i))) i++;
        return in.substring(start, i);
      }
      while (i < in.length() && (isDigit(in.charAt(i)) || in.charAt(i) == '_')) i++;
      return in.substring(start, i);
    }

    private String readIdent() {
      int start = i;
      i++;
      while (i < in.length() && isIdentPart(in.charAt(i))) i++;
      return in.substring(start, i);
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isHexOrUnderscore(char c) {
      return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '_';
    }
    private static boolean isIdentStart(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
    private static boolean isIdentPart(char c) { return isIdentStart(c) || isDigit(c); }
  }

  // ---------------- Parsing (recursive descent) ----------------

  static final class UnknownSymbol extends RuntimeException {
    UnknownSymbol(String s) { super(s); }
  }

  static final class Parser {
    private final List<Tok> toks;
    private final Map<String, Symbol> symbols;
    private final int dot;
    private int p = 0;

    Parser(List<Tok> toks, Map<String, Symbol> symbols, int dot) {
      this.toks = toks;
      this.symbols = symbols;
      this.dot = dot;
    }

    boolean hasMore() { return peek().t != TokType.END; }
    Tok peek() { return toks.get(p); }

    long parseExpr() { return parseBitOr(); }

    private long parseBitOr() {
      long v = parseBitXor();
      while (peek().t == TokType.BAR) {
        consume(TokType.BAR);
        v |= parseBitXor();
      }
      return v;
    }

    private long parseBitXor() {
      long v = parseBitAnd();
      while (peek().t == TokType.CARET) {
        consume(TokType.CARET);
        v ^= parseBitAnd();
      }
      return v;
    }

    private long parseBitAnd() {
      long v = parseShift();
      while (peek().t == TokType.AMP) {
        consume(TokType.AMP);
        v &= parseShift();
      }
      return v;
    }

    private long parseShift() {
      long v = parseAdd();
      while (peek().t == TokType.LSHIFT || peek().t == TokType.RSHIFT) {
        if (peek().t == TokType.LSHIFT) {
          consume(TokType.LSHIFT);
          v = v << (int) parseAdd();
        } else {
          consume(TokType.RSHIFT);
          v = v >> (int) parseAdd();
        }
      }
      return v;
    }

    private long parseAdd() {
      long v = parseMul();
      while (peek().t == TokType.PLUS || peek().t == TokType.MINUS) {
        if (peek().t == TokType.PLUS) {
          consume(TokType.PLUS);
          v += parseMul();
        } else {
          consume(TokType.MINUS);
          v -= parseMul();
        }
      }
      return v;
    }

    private long parseMul() {
      long v = parseUnary();
      while (peek().t == TokType.STAR || peek().t == TokType.SLASH || peek().t == TokType.PERCENT) {
        if (peek().t == TokType.STAR) {
          consume(TokType.STAR);
          v *= parseUnary();
        } else if (peek().t == TokType.SLASH) {
          consume(TokType.SLASH);
          long d = parseUnary();
          if (d == 0) throw new IllegalArgumentException("Division by zero");
          v /= d;
        } else {
          consume(TokType.PERCENT);
          long d = parseUnary();
          if (d == 0) throw new IllegalArgumentException("Division by zero");
          v %= d;
        }
      }
      return v;
    }

    private long parseUnary() {
      Tok t = peek();
      if (t.t == TokType.PLUS) { consume(TokType.PLUS); return +parseUnary(); }
      if (t.t == TokType.MINUS) { consume(TokType.MINUS); return -parseUnary(); }
      if (t.t == TokType.TILDE) { consume(TokType.TILDE); return ~parseUnary(); }
      return parsePrimary();
    }

    private long parsePrimary() {
      Tok t = peek();
      switch (t.t) {
        case NUM -> {
          consume(TokType.NUM);
          return parseNumber(t.s);
        }
        case IDENT -> {
          consume(TokType.IDENT);
          Symbol sym = symbols.get(t.s.toLowerCase());
          if (sym == null) throw new UnknownSymbol(t.s);
          return sym.value;
        }
        case DOT -> {
          consume(TokType.DOT);
          return dot;
        }
        case LPAREN -> {
          consume(TokType.LPAREN);
          long v = parseExpr();
          consume(TokType.RPAREN);
          return v;
        }
        default -> throw new IllegalArgumentException("Expected primary, got " + t);
      }
    }

    private void consume(TokType expected) {
      Tok t = peek();
      if (t.t != expected) {
        throw new IllegalArgumentException("Expected " + expected + " but got " + t);
      }
      p++;
    }

    private static long parseNumber(String s) {
      String x = s.replace("_", "");
      if (x.startsWith("$") && x.length() > 1) {
        return Long.parseUnsignedLong(x.substring(1), 16);
      }
      if (x.startsWith("0x") || x.startsWith("0X")) {
        return Long.parseUnsignedLong(x.substring(2), 16);
      }
      return Long.parseLong(x, 10);
    }
  }
}
