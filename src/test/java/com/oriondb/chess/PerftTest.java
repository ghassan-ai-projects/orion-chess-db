package com.oriondb.chess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Perft validation of the move generator against the standard reference
 * node counts (chessprogramming.org). These positions collectively cover
 * castling legality, en passant (including discovered-check pins),
 * promotions, and pin/check handling.
 */
class PerftTest {

    static long perft(Board board, int depth) {
        if (depth == 0) return 1;
        long nodes = 0;
        for (int move : board.legalMoves()) {
            nodes += perft(board.play(move), depth - 1);
        }
        return nodes;
    }

    private static void assertPerft(String fen, int depth, long expected) {
        Board board = Board.fromFen(fen);
        assertEquals(expected, perft(board, depth),
            "perft(" + depth + ") of " + fen);
    }

    @Test
    void startingPosition() {
        assertPerft(Board.START_FEN, 1, 20);
        assertPerft(Board.START_FEN, 2, 400);
        assertPerft(Board.START_FEN, 3, 8_902);
        assertPerft(Board.START_FEN, 4, 197_281);
    }

    @Test
    void kiwipete() {
        String fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
        assertPerft(fen, 1, 48);
        assertPerft(fen, 2, 2_039);
        assertPerft(fen, 3, 97_862);
    }

    @Test
    void position3EnPassantPins() {
        String fen = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
        assertPerft(fen, 1, 14);
        assertPerft(fen, 2, 191);
        assertPerft(fen, 3, 2_812);
        assertPerft(fen, 4, 43_238);
        assertPerft(fen, 5, 674_624);
    }

    @Test
    void position4Promotions() {
        String fen = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
        assertPerft(fen, 1, 6);
        assertPerft(fen, 2, 264);
        assertPerft(fen, 3, 9_467);
        assertPerft(fen, 4, 422_333);
    }

    @Test
    void position5() {
        String fen = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";
        assertPerft(fen, 1, 44);
        assertPerft(fen, 2, 1_486);
        assertPerft(fen, 3, 62_379);
    }

    @Test
    void position6() {
        String fen = "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10";
        assertPerft(fen, 1, 46);
        assertPerft(fen, 2, 2_079);
        assertPerft(fen, 3, 89_890);
    }
}
