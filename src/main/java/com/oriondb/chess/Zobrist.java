package com.oriondb.chess;

import java.util.SplittableRandom;

/**
 * Zobrist hashing tables for position identity.
 *
 * Keys are deterministic across runs and platforms (SplittableRandom with a
 * fixed seed has a specified algorithm), so hashes can be persisted in cache
 * files. The en-passant file is only hashed when an enemy pawn could actually
 * capture (polyglot convention), so transpositions that differ only in an
 * irrelevant e.p. square hash identically.
 */
public final class Zobrist {
    // Indexed by piece code (0-13, gaps at 6-7 unused) and square.
    static final long[][] PIECE = new long[16][64];
    static final long[] CASTLING = new long[16];
    static final long[] EP_FILE = new long[8];
    static final long SIDE_BLACK;

    static {
        SplittableRandom rng = new SplittableRandom(0x0510EC0DBC0FFEEL);
        for (int piece = 0; piece < 16; piece++) {
            for (int sq = 0; sq < 64; sq++) {
                PIECE[piece][sq] = rng.nextLong();
            }
        }
        for (int i = 0; i < 16; i++) {
            CASTLING[i] = rng.nextLong();
        }
        for (int i = 0; i < 8; i++) {
            EP_FILE[i] = rng.nextLong();
        }
        SIDE_BLACK = rng.nextLong();
    }

    private Zobrist() {}

    /**
     * Full hash of a position. The e.p. component is included only when an
     * enemy pawn is adjacent to the double-pushed pawn (could pseudo-legally
     * capture).
     */
    static long hash(int[] squares, int sideToMove, int castling, int epSquare) {
        long h = 0;
        for (int sq = 0; sq < 64; sq++) {
            int piece = squares[sq];
            if (piece != Piece.NONE) {
                h ^= PIECE[piece][sq];
            }
        }
        h ^= CASTLING[castling & 15];
        if (sideToMove == Piece.BLACK) {
            h ^= SIDE_BLACK;
        }
        if (epSquare != Square.NONE && epRelevant(squares, sideToMove, epSquare)) {
            h ^= EP_FILE[Square.file(epSquare)];
        }
        return h;
    }

    /** True if a pawn of {@code sideToMove} sits adjacent to the e.p. target's pawn. */
    private static boolean epRelevant(int[] squares, int sideToMove, int epSquare) {
        // A capturing pawn stands on the same rank as the double-pushed pawn:
        // one rank before the e.p. square from the capturer's point of view.
        int pawnRank = Square.rank(epSquare) + (sideToMove == Piece.WHITE ? -1 : 1);
        int file = Square.file(epSquare);
        int capturer = Piece.PAWN | sideToMove;
        if (file > 0 && squares[Square.of(file - 1, pawnRank)] == capturer) return true;
        if (file < 7 && squares[Square.of(file + 1, pawnRank)] == capturer) return true;
        return false;
    }
}
