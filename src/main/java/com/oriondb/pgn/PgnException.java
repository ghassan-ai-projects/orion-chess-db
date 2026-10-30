package com.oriondb.pgn;

/** A game that cannot be parsed: bad tags, an illegal mainline move, an unsupported variant. */
public class PgnException extends Exception {
    private final int line;

    public PgnException(String message, int line) {
        super(line > 0 ? message + " (line " + line + ")" : message);
        this.line = line;
    }

    /** 1-based line within the game text, or 0 if unknown. */
    public int line() {
        return line;
    }
}
