package io.github.robincores.toolchain.r8as;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small, dependency-free integer expression evaluator for assembler constants.
 *
 * Supported:
 * - Literals: decimal, 0xHEX, $HEX (underscores allowed)
 * - Symbols: IDENT from the current symbol table, and '.' (current location / ip)
 * - Operators:
 *     unary: +  -  ~  !
 *     mul:   *  /  %
 *     add:   +  -
 *     shift: << >>
 *     rel:   <  <= >  >=
 *     eq:    == !=
 *     bit:   &  ^  |
 *     log:   && ||
 * - Parentheses
 *
 * Notes:
 * - Rel/eq/log ops return 1 (true) or 0 (false).
 * - && and || short-circuit (and will not evaluate unknown symbols in skipped branches).
 */
final class ExpressionEvaluator {

  private ExpressionEvaluator() {}

  /**
   * @return evaluated long, or null if it references an unknown symbol (in an evaluated branch)
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

    LT, LE, GT, GE,
    EQEQ, NEQ,

    AMP, CARET, BAR,
    ANDAND, OROR,

    TILDE, BANG,

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

        // multi-char operators (order matters)
        if (c == '<') {
          if (peek2('<')) { i += 2; out.add(new Tok(TokType.LSHIFT, "<<")); continue; }
          if (peekChar('=')) { i += 2; out.add(new Tok(TokType.LE, "<=")); continue; }
          i++; out.add(new Tok(TokType.LT, "<")); continue;
        }
        if (c == '>') {
          if (peek2('>')) { i += 2; out.add(new Tok(TokType.RSHIFT, ">>")); continue; }
          if (peekChar('=')) { i += 2; out.add(new Tok(TokType.GE, ">=")); continue; }
          i++; out.add(new Tok(TokType.GT, ">")); continue;
        }
        if (c == '=') {
          if (peekChar('=')) { i += 2; out.add(new Tok(TokType.EQEQ, "==")); continue; }
          throw new IllegalArgumentException("Illegal character in expression: '=' (did you mean '==') in \"" + in + "\"");
        }
        if (c == '!') {
          if (peekChar('=')) { i += 2; out.add(new Tok(TokType.NEQ, "!=")); continue; }
          i++; out.add(new Tok(TokType.BANG, "!")); continue;
        }
        if (c == '&') {
          if (peekChar('&')) { i += 2; out.add(new Tok(TokType.ANDAND, "&&")); continue; }
          i++; out.add(new Tok(TokType.AMP, "&")); continue;
        }
        if (c == '|') {
          if (peekChar('|')) { i += 2; out.add(new Tok(TokType.OROR, "||")); continue; }
          i++; out.add(new Tok(TokType.BAR, "|")); continue;
        }

        // single-char tokens
        switch (c) {
          case '(': i++; out.add(new Tok(TokType.LPAREN, "(")); continue;
          case ')': i++; out.add(new Tok(TokType.RPAREN, ")")); continue;
          case '+': i++; out.add(new Tok(TokType.PLUS, "+")); continue;
          case '-': i++; out.add(new Tok(TokType.MINUS, "-")); continue;
          case '*': i++; out.add(new Tok(TokType.STAR, "*")); continue;
          case '/': i++; out.add(new Tok(TokType.SLASH, "/")); continue;
          case '%': i++; out.add(new Tok(TokType.PERCENT, "%")); continue;
          case '^': i++; out.add(new Tok(TokType.CARET, "^")); continue;
          case '~': i++; out.add(new Tok(TokType.TILDE, "~")); continue;
          case '.': i++; out.add(new Tok(TokType.DOT, ".")); continue;
          default: break;
        }

        // number (including $HEX, 0xHEX)
        if (c == '$' || isDigit(c) || (c == '0' && (i + 1) < in.length() && (in.charAt(i + 1) == 'x' || in.charAt(i + 1) == 'X'))) {
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

  // ---------------- Parsing (recursive descent, with optional evaluation) ----------------

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

    long parseExpr() { return parseLogicalOr(true); }

    private static boolean truth(long v) { return v != 0; }

    private long parseLogicalOr(boolean eval) {
      long v = parseLogicalAnd(eval);
      while (peek().t == TokType.OROR) {
        consume(TokType.OROR);
        if (eval) {
          if (truth(v)) {
            // short-circuit: still must consume RHS
            parseLogicalAnd(false);
            v = 1;
          } else {
            long r = parseLogicalAnd(true);
            v = truth(r) ? 1 : 0;
          }
        } else {
          parseLogicalAnd(false);
          v = 0;
        }
      }
      return eval ? v : 0;
    }

    private long parseLogicalAnd(boolean eval) {
      long v = parseBitOr(eval);
      while (peek().t == TokType.ANDAND) {
        consume(TokType.ANDAND);
        if (eval) {
          if (!truth(v)) {
            parseBitOr(false);
            v = 0;
          } else {
            long r = parseBitOr(true);
            v = truth(r) ? 1 : 0;
          }
        } else {
          parseBitOr(false);
          v = 0;
        }
      }
      return eval ? v : 0;
    }

    private long parseBitOr(boolean eval) {
      long v = parseBitXor(eval);
      while (peek().t == TokType.BAR) {
        consume(TokType.BAR);
        long r = parseBitXor(eval);
        if (eval) v |= r;
      }
      return eval ? v : 0;
    }

    private long parseBitXor(boolean eval) {
      long v = parseBitAnd(eval);
      while (peek().t == TokType.CARET) {
        consume(TokType.CARET);
        long r = parseBitAnd(eval);
        if (eval) v ^= r;
      }
      return eval ? v : 0;
    }

    private long parseBitAnd(boolean eval) {
      long v = parseEquality(eval);
      while (peek().t == TokType.AMP) {
        consume(TokType.AMP);
        long r = parseEquality(eval);
        if (eval) v &= r;
      }
      return eval ? v : 0;
    }

    private long parseEquality(boolean eval) {
      long v = parseRelational(eval);
      while (peek().t == TokType.EQEQ || peek().t == TokType.NEQ) {
        TokType op = peek().t;
        consume(op);
        long r = parseRelational(eval);
        if (eval) {
          boolean ok = (op == TokType.EQEQ) ? (v == r) : (v != r);
          v = ok ? 1 : 0;
        }
      }
      return eval ? v : 0;
    }

    private long parseRelational(boolean eval) {
      long v = parseShift(eval);
      while (peek().t == TokType.LT || peek().t == TokType.LE || peek().t == TokType.GT || peek().t == TokType.GE) {
        TokType op = peek().t;
        consume(op);
        long r = parseShift(eval);
        if (eval) {
          boolean ok = switch (op) {
            case LT -> v < r;
            case LE -> v <= r;
            case GT -> v > r;
            case GE -> v >= r;
            default -> false;
          };
          v = ok ? 1 : 0;
        }
      }
      return eval ? v : 0;
    }

    private long parseShift(boolean eval) {
      long v = parseAdd(eval);
      while (peek().t == TokType.LSHIFT || peek().t == TokType.RSHIFT) {
        TokType op = peek().t;
        consume(op);
        long r = parseAdd(eval);
        if (eval) {
          if (op == TokType.LSHIFT) v = v << (int) r;
          else v = v >> (int) r;
        }
      }
      return eval ? v : 0;
    }

    private long parseAdd(boolean eval) {
      long v = parseMul(eval);
      while (peek().t == TokType.PLUS || peek().t == TokType.MINUS) {
        TokType op = peek().t;
        consume(op);
        long r = parseMul(eval);
        if (eval) {
          if (op == TokType.PLUS) v += r;
          else v -= r;
        }
      }
      return eval ? v : 0;
    }

    private long parseMul(boolean eval) {
      long v = parseUnary(eval);
      while (peek().t == TokType.STAR || peek().t == TokType.SLASH || peek().t == TokType.PERCENT) {
        TokType op = peek().t;
        consume(op);
        long r = parseUnary(eval);
        if (eval) {
          if (op == TokType.STAR) {
            v *= r;
          } else if (op == TokType.SLASH) {
            if (r == 0) throw new IllegalArgumentException("Division by zero");
            v /= r;
          } else {
            if (r == 0) throw new IllegalArgumentException("Division by zero");
            v %= r;
          }
        }
      }
      return eval ? v : 0;
    }

    private long parseUnary(boolean eval) {
      Tok t = peek();

      if (t.t == TokType.PLUS) {
        consume(TokType.PLUS);
        if (eval) return +parseUnary(true);
        parseUnary(false);
        return 0;
      }

      if (t.t == TokType.MINUS) {
        consume(TokType.MINUS);
        if (eval) return -parseUnary(true);
        parseUnary(false);
        return 0;
      }

      if (t.t == TokType.TILDE) {
        consume(TokType.TILDE);
        if (eval) return ~parseUnary(true);
        parseUnary(false);
        return 0;
      }

      if (t.t == TokType.BANG) {
        consume(TokType.BANG);
        if (eval) return (truth(parseUnary(true)) ? 0 : 1);
        parseUnary(false);
        return 0;
      }

      return parsePrimary(eval);
    }

    private long parsePrimary(boolean eval) {
      Tok t = peek();
      switch (t.t) {
        case NUM -> {
          consume(TokType.NUM);
          return eval ? parseNumber(t.s) : 0;
        }
        case IDENT -> {
          consume(TokType.IDENT);
          if (!eval) return 0;
          Symbol sym = symbols.get(t.s.toLowerCase(Locale.ROOT));
          if (sym == null) throw new UnknownSymbol(t.s);
          return sym.value;
        }
        case DOT -> {
          consume(TokType.DOT);
          return eval ? dot : 0;
        }
        case LPAREN -> {
          consume(TokType.LPAREN);
          long v = parseLogicalOr(eval);
          consume(TokType.RPAREN);
          return eval ? v : 0;
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
      if (x.startsWith("$") && x.length() > 1) return Long.parseUnsignedLong(x.substring(1), 16);
      if (x.startsWith("0x") || x.startsWith("0X")) return Long.parseUnsignedLong(x.substring(2), 16);
      return Long.parseLong(x, 10);
    }
  }
}