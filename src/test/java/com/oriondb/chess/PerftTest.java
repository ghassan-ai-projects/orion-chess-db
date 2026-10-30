package com.oriondb.chess;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Move generator validation against reference perft counts
 * (chessprogramming.org), covering castling, en passant pins, promotions,
 * checks, and Chess960 castling.
 */
class PerftTest {

    static long perft(Position pos, int depth) {
        int[] moves = pos.legalMoves();
        if (depth == 1) return moves.length;
        long nodes = 0;
        for (int m : moves) nodes += perft(pos.play(m), depth - 1);
        return nodes;
    }

    @ParameterizedTest(name = "{0} depth {1}")
    @CsvSource(delimiter = ';', value = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1; 4; 197281",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1; 3; 97862",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1; 5; 674624",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1; 4; 422333",
        "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1; 4; 422333",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8; 3; 62379",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10; 3; 89890",
        // Chess960 (reference values from the chessprogramming.org Chess960 perft list)
        "bqnb1rkr/pp3ppp/3ppn2/2p5/5P2/P2P4/NPP1P1PP/BQ1BNRKR w HFhf - 2 9; 3; 12189",
        "2nnrbkr/p1qppppp/8/1ppb4/6PP/3PP3/PPP2P2/BQNNRBKR w HEhe - 1 9; 3; 18002",
    })
    void perft(String fen, int depth, long expected) {
        assertEquals(expected, perft(Position.fromFen(fen), depth));
    }

    @ParameterizedTest(name = "slow {0} depth {1}")
    @CsvSource(delimiter = ';', value = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1; 5; 4865609",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1; 4; 4085603",
    })
    void deepPerft(String fen, int depth, long expected) {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("oriondb.slow"));
        assertEquals(expected, perft(Position.fromFen(fen), depth));
    }
}
