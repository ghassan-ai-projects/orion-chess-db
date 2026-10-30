package com.oriondb.chess;

import java.util.SplittableRandom;

/**
 * Zobrist keys. Generated from a fixed seed so keys are identical across
 * runs and safe to persist in index files. The en passant file is hashed
 * only when a capture is possible (positions store the e.p. square only
 * then), so transpositions hash identically.
 */
public final class Zobrist {
    private static final long[][] PIECE_SQUARE = new long[12][64];
    private static final long[] CASTLING = new long[16];
    private static final long[] EP_FILE = new long[8];
    private static final long BLACK_TO_MOVE;

    static {
        SplittableRandom rnd = new SplittableRandom(0x0D10_DB5EED_2026L);
        for (long[] row : PIECE_SQUARE) {
            for (int i = 0; i < 64; i++) row[i] = rnd.nextLong();
        }
        long[] rights = new long[4];
        for (int i = 0; i < 4; i++) rights[i] = rnd.nextLong();
        for (int mask = 0; mask < 16; mask++) {
            long k = 0;
            for (int i = 0; i < 4; i++) if ((mask & (1 << i)) != 0) k ^= rights[i];
            CASTLING[mask] = k;
        }
        for (int i = 0; i < 8; i++) EP_FILE[i] = rnd.nextLong();
        BLACK_TO_MOVE = rnd.nextLong();
    }

    private Zobrist() {}

    static long piece(int piece, int sq) {
        return PIECE_SQUARE[piece][sq];
    }

    /** Key of a castling-rights mask (bit 0 white O-O, 1 white O-O-O, 2 black O-O, 3 black O-O-O). */
    static long castling(int rightsMask) {
        return CASTLING[rightsMask];
    }

    static long epFile(int file) {
        return EP_FILE[file];
    }

    static long blackToMove() {
        return BLACK_TO_MOVE;
    }
}
