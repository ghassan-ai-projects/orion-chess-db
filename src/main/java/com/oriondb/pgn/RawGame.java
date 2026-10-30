package com.oriondb.pgn;

/**
 * The unparsed bytes of one game as found in a PGN source.
 *
 * @param index  0-based position of the game in the source
 * @param offset byte offset of the first byte in the source
 * @param bytes  the game's bytes, trimmed of surrounding blank lines
 * @param line   1-based line number of the first byte in the source
 */
public record RawGame(int index, long offset, byte[] bytes, long line) {
    /** Decoded text (UTF-8, falling back to Windows-1252 for legacy files). */
    public String text() {
        return PgnText.decode(bytes);
    }
}
