package com.oriondb.engine;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;

import java.util.List;

/**
 * The result of analysing one position.
 *
 * @param position the analysed position
 * @param lines    principal variations, best first (may be empty for a terminal position)
 * @param bestMove the engine's {@code bestmove}, or {@link Move#NONE} if there is none
 * @param nodes    total nodes searched
 * @param timeMs   search time reported by the engine
 */
public record Analysis(Position position, List<PvLine> lines, int bestMove, long nodes, long timeMs) {

    public Analysis {
        lines = List.copyOf(lines);
    }

    /** The best line, or null if the engine reported none. */
    public PvLine best() {
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** Score of the best line from the side to move's point of view, or null. */
    public Score score() {
        return lines.isEmpty() ? null : lines.get(0).score();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(position.fen());
        for (PvLine l : lines) sb.append("\n  ").append(l);
        return sb.toString();
    }
}
