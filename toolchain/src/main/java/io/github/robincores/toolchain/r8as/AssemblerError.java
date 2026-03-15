package io.github.robincores.toolchain.r8as;

/**
 * A single diagnostic produced during assembly.
 *
 * Backwards compatible with the old (msg,line) shape, but now optionally carries
 * source name and column.
 */
public final class AssemblerError {

    public final String msg;
    public final int line;
    /** 1-based column, or 1 if unknown. */
    public final int col;
    /** Optional source name (file path). */
    public final String source;

    public AssemblerError(String msg, int line) {
        this(msg, line, 1, null);
    }

    public AssemblerError(String msg, int line, int col) {
        this(msg, line, col, null);
    }

    public AssemblerError(String msg, int line, int col, String source) {
        this.msg = msg;
        this.line = line;
        this.col = (col <= 0) ? 1 : col;
        this.source = source;
    }

    public String format() {
        String src = (source == null || source.isBlank()) ? "<input>" : source;
        return src + ":" + line + ":" + col + ": " + msg;
    }

    @Override
    public String toString() {
        return format();
    }
}
