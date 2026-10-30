package com.oriondb.chess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotationTest {

    private static Position after(String... sans) {
        Position p = Position.start();
        for (String s : sans) p = p.play(San.parse(p, s));
        return p;
    }

    @Test
    void fenRoundTrip() {
        String[] fens = {
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "bqnb1rkr/pp3ppp/3ppn2/2p5/5P2/P2P4/NPP1P1PP/BQ1BNRKR w KQkq - 2 9",
        };
        for (String fen : fens) assertEquals(fen, Position.fromFen(fen).fen());
    }

    @Test
    void enPassantStoredOnlyWhenCapturable() {
        assertEquals(Square.NONE, after("e4").epSquare());
        Position p = after("e4", "a6", "e5", "d5");
        assertEquals(Square.parse("d6"), p.epSquare());
        assertTrue(p.fen().contains(" d6 "));
        // Transposition with and without an irrelevant ep square hashes equal.
        assertEquals(after("e4", "e5").hash(), Position.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2").hash());
    }

    @Test
    void transpositionsHashEqual() {
        assertEquals(after("Nf3", "Nf6", "g3").hash(), after("g3", "Nf6", "Nf3").hash());
        assertEquals(after("Nf3", "Nf6", "g3"), after("g3", "Nf6", "Nf3"));
        assertNotEquals(after("e4").hash(), after("e3").hash());
    }

    @Test
    void sanFormatting() {
        Position p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        assertEquals("O-O", San.format(p, San.parse(p, "O-O")));
        assertEquals("O-O-O", San.format(p, San.parse(p, "0-0-0")));
        assertEquals("Rxa8+", San.format(p, San.parse(p, "Rxa8")));
        // File and rank disambiguation
        Position q = Position.fromFen("4k3/8/8/8/8/8/1N3N2/4K3 w - - 0 1");
        assertEquals("Nbd3", San.format(q, San.parse(q, "Nbd3")));
        Position r = Position.fromFen("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1");
        assertEquals("R1a3", San.format(r, San.parse(r, "R1a3")));
        // Promotion and mate
        Position s = Position.fromFen("6k1/4P3/8/8/8/8/8/K7 w - - 0 1");
        assertEquals("e8=Q+", San.format(s, San.parse(s, "e8Q")));
        Position mate = after("f3", "e5", "g4");
        assertEquals("Qh4#", San.format(mate, San.parse(mate, "Qh4")));
    }

    @Test
    void lenientParsing() {
        Position p = Position.start();
        assertEquals(San.parse(p, "Nf3"), San.parse(p, "Ng1-f3"));
        assertEquals(San.parse(p, "e4"), San.parse(p, "e2e4"));
        assertEquals(San.parse(p, "e4"), San.parse(p, "e4!?"));
        assertEquals(Move.NULL, San.parse(p, "--"));
        assertThrows(ChessFormatException.class, () -> San.parse(p, "e5"));
        assertThrows(ChessFormatException.class, () -> San.parse(p, "Nd2"));
    }

    @Test
    void chess960Castling() {
        Position p = Position.fromFen("bqnbrkrn/pppppppp/8/8/8/8/PPPPPPPP/BQNBRKRN w GEge - 0 1");
        assertTrue(p.hasChess960Castling());
        // Clear the path for O-O: king f1 -> g1, rook g1 -> f1 (squares swap).
        Position q = Position.fromFen("4rkr1/pppppppp/8/8/8/8/PPPPPPPP/4RKR1 w GEge - 0 1");
        int oo = San.parse(q, "O-O");
        assertTrue(Move.isCastle(oo));
        Position after = q.play(oo);
        assertEquals(Piece.W_KING, after.pieceAt(Square.G1));
        assertEquals(Piece.W_ROOK, after.pieceAt(Square.F1));
        assertEquals("f1g1", Uci.format(oo, true));
    }

    @Test
    void packRoundTrip() {
        Position p = Position.fromFen("r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1");
        for (int m : p.legalMoves()) assertEquals(m, Move.unpack(Move.pack(m)));
        assertEquals(Move.NULL, Move.unpack(Move.pack(Move.NULL)));
        Position ep = after("e4", "a6", "e5", "d5");
        for (int m : ep.legalMoves()) assertEquals(m, Move.unpack(Move.pack(m)));
    }

    @Test
    void rejectsBadFen() {
        assertThrows(ChessFormatException.class, () -> Position.fromFen("8/8/8/8/8/8/8/8 w - - 0 1"));
        assertThrows(ChessFormatException.class, () -> Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1"));
        assertThrows(ChessFormatException.class, () -> Position.fromFen("4k3/4R3/8/8/8/8/8/4K3 w - - 0 1"));
    }

    @Test
    void drawDetection() {
        assertTrue(Position.fromFen("4k3/8/8/8/8/8/8/4KB2 w - - 0 1").isInsufficientMaterial());
        assertFalse(Position.fromFen("4k3/8/8/8/8/8/8/3NKB2 w - - 0 1").isInsufficientMaterial());
        assertTrue(Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").isStalemate());
    }
}
