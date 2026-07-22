package com.oriondb.chess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {

    @Test
    void fenRoundTrip() {
        String[] fens = {
            Board.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "4k3/8/8/8/8/8/8/4K3 b - - 12 47",
        };
        for (String fen : fens) {
            assertEquals(fen, Board.fromFen(fen).toFen());
        }
    }

    @Test
    void sanRoundTripInManyPositions() {
        String[] fens = {
            Board.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            // Rook disambiguation on both file and rank
            "3k4/8/8/8/R6R/8/8/R3K3 w - - 0 1",
        };
        for (String fen : fens) {
            Board board = Board.fromFen(fen);
            for (int move : board.legalMoves()) {
                String san = board.toSan(move);
                assertEquals(move, board.parseSan(san),
                    "SAN round trip failed for " + san + " in " + fen);
            }
        }
    }

    @Test
    void sanDisambiguation() {
        // Two knights on b2 and f2 can both reach d3
        Board twoKnights = Board.fromFen("4k3/8/8/8/8/8/1N3N2/4K3 w - - 0 1");
        int move = twoKnights.parseSan("Nbd3");
        assertNotEquals(Move.NONE, move);
        assertEquals("Nbd3", twoKnights.toSan(move));
        // Ambiguous without disambiguation
        assertEquals(Move.NONE, twoKnights.parseSan("Nd3"));
    }

    @Test
    void castlingParsing() {
        Board board = Board.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
        int kingside = board.parseSan("O-O");
        assertNotEquals(Move.NONE, kingside);
        assertEquals(Move.CASTLE_KINGSIDE, Move.special(kingside));
        assertEquals(Move.NONE, Board.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/RN2K2R w KQkq - 0 1")
            .parseSan("O-O-O"), "castling through occupied b1 square must use rook path rules");
        // Zeros notation
        assertNotEquals(Move.NONE, board.parseSan("0-0-0"));
        // UCI castling form
        assertEquals(kingside, board.parseUci("e1g1"));
    }

    @Test
    void pinnedPieceDisambiguation() {
        // Rooks on d2 and f2 could both go to e2, but the d2 rook is pinned
        // by the b4 bishop. Correct SAN is plain "Re2" (no disambiguation),
        // and it must resolve to the f2 rook. (Black king on g8, off the e-file.)
        Board board = Board.fromFen("6k1/8/8/8/1b6/8/3R1R2/4K3 w - - 0 1");
        int move = board.parseSan("Re2");
        assertNotEquals(Move.NONE, move, "pinned rook must not create ambiguity");
        assertEquals(Square.of(5, 1), Move.from(move), "must be the f2 rook");
        assertEquals("Re2", board.toSan(move));
    }

    @Test
    void enPassant() {
        Board board = Board.startingPosition()
            .playSan("e4").playSan("Nf6").playSan("e5").playSan("d5");
        int ep = board.parseSan("exd6");
        assertNotEquals(Move.NONE, ep);
        assertEquals(Move.EN_PASSANT, Move.special(ep));
        Board after = board.play(ep);
        assertEquals(Piece.NONE, after.pieceAt(Square.fromAlgebraic("d5")), "captured pawn removed");
        assertEquals(Piece.WHITE_PAWN, after.pieceAt(Square.fromAlgebraic("d6")));
    }

    @Test
    void promotions() {
        Board board = Board.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1");
        Board queened = board.playSan("a8=Q");
        assertEquals(Piece.WHITE_QUEEN, queened.pieceAt(Square.A8));
        // Tolerant form without '='
        assertNotEquals(Move.NONE, board.parseSan("a8Q"));
        // Underpromotion
        Board knighted = board.playSan("a8=N");
        assertEquals(Piece.WHITE_KNIGHT, knighted.pieceAt(Square.A8));
    }

    @Test
    void checkAndMateSuffixes() {
        Board board = Board.startingPosition()
            .playSan("f3").playSan("e5").playSan("g4");
        int mate = board.parseSan("Qh4");
        assertEquals("Qh4#", board.toSan(mate));
        Board after = board.play(mate);
        assertTrue(after.isCheckmate());
        assertFalse(after.isStalemate());
    }

    @Test
    void stalemate() {
        Board board = Board.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        assertTrue(board.isStalemate());
        assertFalse(board.isCheckmate());
    }

    @Test
    void zobristTranspositions() {
        // 1.Nf3 Nf6 2.Ng1 Ng8 returns to the start position hash
        Board board = Board.startingPosition();
        long startHash = board.zobrist();
        Board back = board.playSan("Nf3").playSan("Nf6").playSan("Ng1").playSan("Ng8");
        assertEquals(startHash, back.zobrist());

        // Different move orders reaching the same position hash equal
        Board a = Board.startingPosition().playSan("e4").playSan("e5").playSan("Nf3").playSan("Nc6");
        Board b = Board.startingPosition().playSan("Nf3").playSan("Nc6").playSan("e4").playSan("e5");
        assertEquals(a.zobrist(), b.zobrist());
    }

    @Test
    void zobristEnPassantRelevance() {
        // A double push with no enemy pawn adjacent must hash like the
        // same position without the e.p. square.
        Board afterDouble = Board.startingPosition().playSan("e4");
        Board samePosNoEp = Board.fromFen(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1");
        assertEquals(samePosNoEp.zobrist(), afterDouble.zobrist());
    }

    @Test
    void nullMoves() {
        Board board = Board.startingPosition();
        int nullMove = board.parseSan("--");
        assertTrue(Move.isNull(nullMove));
        Board after = board.play(nullMove);
        assertEquals(Piece.BLACK, after.sideToMove());
        assertEquals(board.toFen().split(" ")[0], after.toFen().split(" ")[0]);
    }

    @Test
    void legalMoveCountStartPosition() {
        List<Integer> moves = Board.startingPosition().legalMoves();
        assertEquals(20, moves.size());
    }

    @Test
    void illegalSanRejected() {
        Board board = Board.startingPosition();
        assertEquals(Move.NONE, board.parseSan("e5"));      // not reachable
        assertEquals(Move.NONE, board.parseSan("Ke2"));     // blocked
        assertEquals(Move.NONE, board.parseSan("O-O"));     // no castling yet
        assertEquals(Move.NONE, board.parseSan("garbage"));
        assertThrows(IllegalArgumentException.class, () -> board.playSan("Qh5"));
    }
}
