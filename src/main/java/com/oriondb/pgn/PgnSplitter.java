package com.oriondb.pgn;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Splits a PGN byte stream into games without parsing moves, tracking byte
 * offsets so a game can later be re-read from the file directly.
 *
 * <p>A game starts at a line beginning with {@code [} once the previous game
 * has movetext (or after a blank line, for an {@code [Event} line following a
 * tags-only game). Brace comments are tracked so {@code [} inside a comment
 * does not split, but an {@code [Event "} line inside an unclosed comment
 * recovers and starts a new game rather than swallowing the rest of the file.
 */
public final class PgnSplitter implements Iterator<RawGame>, Closeable {
    private final InputStream in;
    private final byte[] buf = new byte[1 << 16];
    private int bufPos, bufLen;
    private long streamOffset;
    private long lineNumber = 1;
    private boolean eof;

    private byte[] line = new byte[256];
    private int lineLen;
    private long lineOffset;
    private long lineStartNumber;
    private boolean pendingLine;

    private RawGame next;
    private int index;

    public PgnSplitter(InputStream in) {
        this.in = in;
    }

    @Override
    public boolean hasNext() {
        if (next == null) {
            try {
                next = readGame();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return next != null;
    }

    @Override
    public RawGame next() {
        if (!hasNext()) throw new NoSuchElementException();
        RawGame g = next;
        next = null;
        return g;
    }

    private RawGame readGame() throws IOException {
        ByteArrayOutputStream game = new ByteArrayOutputStream(2048);
        long start = -1, startLine = 0;
        long lastContentEnd = 0; // length of game buffer up to the end of the last non-blank line
        boolean sawTag = false, sawMovetext = false, blankAfterTag = false, inComment = false;

        while (pendingLine || readLine()) {
            pendingLine = false;
            int first = firstNonSpace();
            boolean blank = first < 0;
            if (start >= 0 && !blank && line[first] == '[' && !isCommandLine(first)) {
                boolean recover = inComment && startsWithTag(first, "Event");
                boolean boundary = !inComment && (sawMovetext || (blankAfterTag && startsWithTag(first, "Event")));
                if (boundary || recover) {
                    pendingLine = true;
                    break;
                }
            }
            if (blank) {
                if (start >= 0) {
                    game.write(line, 0, lineLen);
                    if (sawTag && !sawMovetext) blankAfterTag = true;
                }
                continue;
            }
            if (start < 0) {
                if (lineOffset == 0 && lineLen >= 3 && (line[0] & 0xFF) == 0xEF && (line[1] & 0xFF) == 0xBB && (line[2] & 0xFF) == 0xBF) {
                    // Skip a UTF-8 byte order mark.
                    System.arraycopy(line, 3, line, 0, lineLen - 3);
                    lineLen -= 3;
                    lineOffset += 3;
                    first = firstNonSpace();
                    if (first < 0) continue;
                }
                start = lineOffset;
                startLine = lineStartNumber;
            }
            if (line[first] == '%') {
                // escape line: ignored by parsers, kept verbatim
            } else if (line[first] == '[' && !sawMovetext && !inComment) {
                sawTag = true;
                blankAfterTag = false;
            } else {
                inComment = scanMovetext(inComment);
                sawMovetext = true;
            }
            game.write(line, 0, lineLen);
            lastContentEnd = game.size();
        }
        if (start < 0) return null;
        byte[] all = game.toByteArray();
        int end = (int) lastContentEnd;
        while (end > 0 && isSpace(all[end - 1])) end--;
        byte[] bytes = java.util.Arrays.copyOf(all, end);
        return new RawGame(index++, start, bytes, startLine);
    }

    /** Updates comment state across one movetext line; returns whether a brace comment is still open. */
    private boolean scanMovetext(boolean inComment) {
        for (int i = 0; i < lineLen; i++) {
            byte b = line[i];
            if (inComment) {
                if (b == '}') inComment = false;
            } else if (b == '{') {
                inComment = true;
            } else if (b == ';') {
                break;
            }
        }
        return inComment;
    }

    private boolean isCommandLine(int first) {
        return first + 1 < lineLen && line[first + 1] == '%';
    }

    private boolean startsWithTag(int first, String name) {
        int i = first + 1;
        for (int k = 0; k < name.length(); k++, i++) {
            if (i >= lineLen || line[i] != name.charAt(k)) return false;
        }
        return i < lineLen && (line[i] == ' ' || line[i] == '\t' || line[i] == '"');
    }

    private int firstNonSpace() {
        for (int i = 0; i < lineLen; i++) if (!isSpace(line[i])) return i;
        return -1;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == '\f';
    }

    /** Reads the next line (including its terminator) into {@link #line}. */
    private boolean readLine() throws IOException {
        lineLen = 0;
        lineOffset = streamOffset;
        lineStartNumber = lineNumber;
        while (true) {
            if (bufPos == bufLen) {
                if (eof) return lineLen > 0;
                bufLen = in.read(buf);
                bufPos = 0;
                if (bufLen <= 0) {
                    bufLen = 0;
                    eof = true;
                    return lineLen > 0;
                }
            }
            byte b = buf[bufPos++];
            streamOffset++;
            if (lineLen == line.length) line = java.util.Arrays.copyOf(line, line.length * 2);
            line[lineLen++] = b;
            if (b == '\n') {
                lineNumber++;
                return true;
            }
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
