package com.oriondb.model;

import com.oriondb.chess.Board;
import com.oriondb.chess.Move;

/**
 * Replay helpers bridging the game model and the chess engine.
 */
public final class Games {

    /** Visits every mainline position of a game during replay. */
    public interface PositionVisitor {
        /**
         * @param ply    0-based index of the move about to be played
         * @param before position before the move
         * @param move   encoded move (never {@link Move#NONE})
         * @param after  position after the move
         */
        void visit(int ply, Board before, int move, Board after);
    }

    private Games() {}

    /** The game's starting position: the FEN tag for set-up games, else the standard start. */
    public static Board startingBoard(Game game) {
        String fen = game.tag("FEN");
        if (fen != null && !fen.isBlank()) {
            return Board.fromFen(fen);
        }
        return Board.startingPosition();
    }

    /**
     * Replay the mainline, invoking the visitor for each move. Stops quietly
     * at the first unparseable SAN (games from PgnReader with validation are
     * always fully replayable).
     *
     * @return the final position reached
     */
    public static Board replayMainline(Game game, PositionVisitor visitor) {
        Board board = startingBoard(game);
        int ply = 0;
        for (MoveNode node = game.root(); node != null; node = node.next()) {
            int move = board.parseSan(node.san());
            if (move == Move.NONE) {
                break;
            }
            Board after = board.play(move);
            if (visitor != null) {
                visitor.visit(ply, board, move, after);
            }
            board = after;
            ply++;
        }
        return board;
    }

    /** Final mainline position. */
    public static Board finalBoard(Game game) {
        return replayMainline(game, null);
    }

    /**
     * Zobrist hashes of every mainline position: index k is the position
     * before the k-th move (index 0 = start), and the last entry is the
     * final position. Length is plyCount + 1 (or less if a SAN fails).
     */
    public static long[] mainlineHashes(Game game) {
        int plyCount = game.plyCount();
        long[] hashes = new long[plyCount + 1];
        Board board = startingBoard(game);
        hashes[0] = board.zobrist();
        int ply = 0;
        for (MoveNode node = game.root(); node != null; node = node.next()) {
            int move = board.parseSan(node.san());
            if (move == Move.NONE) {
                long[] truncated = new long[ply + 1];
                System.arraycopy(hashes, 0, truncated, 0, ply + 1);
                return truncated;
            }
            board = board.play(move);
            ply++;
            hashes[ply] = board.zobrist();
        }
        return hashes;
    }
}
