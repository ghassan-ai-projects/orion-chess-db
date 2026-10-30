package com.oriondb.engine;

import com.oriondb.chess.Move;

import java.util.Arrays;
import java.util.List;

/**
 * One principal variation reported by the engine.
 *
 * @param multipv  1-based rank of the line (1 = best)
 * @param depth    search depth in plies
 * @param seldepth selective search depth
 * @param score    evaluation from the analysed position's side to move
 * @param moves    the line as legal moves (truncated at the first illegal move the engine sent)
 * @param san      the same moves in SAN
 * @param nodes    nodes searched when the line was reported
 */
public record PvLine(int multipv, int depth, int seldepth, Score score, int[] moves, List<String> san, long nodes) {

    public PvLine {
        moves = moves.clone();
        san = List.copyOf(san);
    }

    @Override
    public int[] moves() {
        return moves.clone();
    }

    /** The first move of the line, or {@link Move#NONE} if it is empty. */
    public int firstMove() {
        return moves.length == 0 ? Move.NONE : moves[0];
    }

    /** The line in SAN separated by spaces. */
    public String sanLine() {
        return String.join(" ", san);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PvLine p && p.multipv == multipv && p.depth == depth && p.seldepth == seldepth
            && p.score.equals(score) && Arrays.equals(p.moves, moves) && p.nodes == nodes;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(moves) * 31 + score.hashCode() + multipv * 7 + depth;
    }

    @Override
    public String toString() {
        return multipv + ": " + score + " d" + depth + " " + sanLine();
    }
}
