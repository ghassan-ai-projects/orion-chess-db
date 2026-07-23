package com.oriondb.pgn;

import com.oriondb.chess.Board;
import com.oriondb.model.Game;
import com.oriondb.model.MoveNode;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * PGN export. Writes the Seven Tag Roster in standard order followed by the
 * remaining tags, then the movetext with comments, NAGs, and variations,
 * wrapped at 80 columns.
 */
public final class PgnWriter {
    private static final String[] SEVEN_TAG_ROSTER = {
        "Event", "Site", "Date", "Round", "White", "Black", "Result"
    };
    private static final int WRAP_COLUMN = 80;

    /** One game as PGN text, ending with a newline. */
    public String write(Game game) {
        StringWriter writer = new StringWriter(1024);
        try {
            write(game, writer);
        } catch (IOException impossible) {
            throw new AssertionError("StringWriter failed", impossible);
        }
        return writer.toString();
    }

    /** All games separated by blank lines. */
    public String write(List<Game> games) {
        StringWriter writer = new StringWriter(games.size() * 1024);
        try {
            write(games, writer);
        } catch (IOException impossible) {
            throw new AssertionError("StringWriter failed", impossible);
        }
        return writer.toString();
    }

    /** Stream one game to a character writer. The writer is not closed. */
    public void write(Game game, Writer writer) throws IOException {
        writeTags(game, writer);
        writer.append('\n');
        writeMovetext(game, writer);
    }

    /** Stream games separated by blank lines. The writer is not closed. */
    public void write(Iterable<Game> games, Writer writer) throws IOException {
        boolean first = true;
        for (Game game : games) {
            if (!first) writer.append('\n');
            first = false;
            writeTags(game, writer);
            writer.append('\n');
            writeMovetext(game, writer);
        }
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    private void writeTags(Game game, Writer writer) throws IOException {
        for (String key : SEVEN_TAG_ROSTER) {
            String value = game.tag(key);
            if (value == null) {
                value = key.equals("Date") ? Game.UNKNOWN_DATE
                    : key.equals("Result") ? "*" : "?";
            }
            writeTag(writer, key, value);
        }
        for (Map.Entry<String, String> tag : game.tags().entrySet()) {
            if (!isRosterTag(tag.getKey())) {
                writeTag(writer, tag.getKey(), tag.getValue());
            }
        }
    }

    private static boolean isRosterTag(String key) {
        for (String roster : SEVEN_TAG_ROSTER) {
            if (roster.equals(key)) return true;
        }
        return false;
    }

    private void writeTag(Writer writer, String key, String value) throws IOException {
        writer.append('[').append(key).append(" \"")
          .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\"]\n");
    }

    // ------------------------------------------------------------------
    // Movetext
    // ------------------------------------------------------------------

    /** Token emitter with word wrap. */
    private static final class LineBuffer {
        private final Writer out;
        private int column;

        LineBuffer(Writer out) {
            this.out = out;
        }

        void emit(String token) throws IOException {
            if (token.isEmpty()) return;
            if (column == 0) {
                out.append(token);
                column = token.length();
            } else if (column + 1 + token.length() > WRAP_COLUMN) {
                out.append('\n').append(token);
                column = token.length();
            } else {
                out.append(' ').append(token);
                column += 1 + token.length();
            }
        }

        void finish() throws IOException {
            if (column > 0) out.append('\n');
        }
    }

    private void writeMovetext(Game game, Writer writer) throws IOException {
        LineBuffer buffer = new LineBuffer(writer);

        if (game.gameComment() != null) {
            emitComment(buffer, game.gameComment());
        }

        // Initial numbering: from the FEN tag for set-up games.
        int moveNumber = 1;
        boolean whiteToMove = true;
        String fen = game.tag("FEN");
        if (fen != null && !fen.isBlank()) {
            try {
                Board start = Board.fromFen(fen);
                moveNumber = start.fullMoveNumber();
                whiteToMove = start.whiteToMove();
            } catch (IllegalArgumentException ignored) {
                // fall back to 1/white
            }
        }

        if (game.root() != null) {
            emitLine(buffer, game.root(), moveNumber, whiteToMove);
        }
        buffer.emit(game.result());
        buffer.finish();
    }

    /**
     * Emit one line of moves. {@code moveNumber}/{@code whiteToMove} describe
     * the first move of the line.
     */
    private void emitLine(
            LineBuffer buffer, MoveNode first, int moveNumber, boolean whiteToMove)
            throws IOException {
        boolean needNumber = true;
        MoveNode node = first;
        int number = moveNumber;
        boolean white = whiteToMove;

        while (node != null) {
            if (node.commentBefore() != null) {
                emitComment(buffer, node.commentBefore());
                needNumber = true;
            }

            String numberPrefix = "";
            if (white) {
                numberPrefix = number + ". ";
            } else if (needNumber) {
                numberPrefix = number + "... ";
            }
            buffer.emit(numberPrefix + node.san() + node.nagSuffix());
            needNumber = false;

            // Non-suffix NAGs as $n tokens
            boolean suffixUsed = !node.nagSuffix().isEmpty();
            for (int nag : node.nags()) {
                if (suffixUsed && nag >= 1 && nag <= 6) {
                    suffixUsed = false; // only the first suffix-capable NAG is inlined
                    continue;
                }
                buffer.emit("$" + nag);
                needNumber = true;
            }

            if (node.comment() != null) {
                emitComment(buffer, node.comment());
                needNumber = true;
            }

            for (MoveNode variation : node.variations()) {
                buffer.emit("(");
                emitLine(buffer, variation, number, white);
                buffer.emit(")");
                needNumber = true;
            }

            if (!white) number++;
            white = !white;
            node = node.next();
        }
    }

    private void emitComment(LineBuffer buffer, String comment) throws IOException {
        // PGN comments cannot contain '}'.
        String safe = comment.replace('}', ')');
        buffer.emit("{" + safe + "}");
    }
}
