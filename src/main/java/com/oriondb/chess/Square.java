package com.oriondb.chess;

/** Squares are ints 0..63: a1 = 0, b1 = 1, ..., h8 = 63. */
public final class Square {
    public static final int NONE = -1;
    public static final int A1 = 0, B1 = 1, C1 = 2, D1 = 3, E1 = 4, F1 = 5, G1 = 6, H1 = 7;
    public static final int A8 = 56, B8 = 57, C8 = 58, D8 = 59, E8 = 60, F8 = 61, G8 = 62, H8 = 63;

    private Square() {}

    public static int of(int file, int rank) {
        return rank * 8 + file;
    }

    public static int file(int sq) {
        return sq & 7;
    }

    public static int rank(int sq) {
        return sq >>> 3;
    }

    public static boolean onBoard(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    /** Rank as seen from {@code color}'s side: 0 is that side's back rank. */
    public static int relativeRank(int sq, int color) {
        return color == Piece.WHITE ? rank(sq) : 7 - rank(sq);
    }

    public static char fileChar(int sq) {
        return (char) ('a' + file(sq));
    }

    public static char rankChar(int sq) {
        return (char) ('1' + rank(sq));
    }

    public static String name(int sq) {
        return sq == NONE ? "-" : "" + fileChar(sq) + rankChar(sq);
    }

    /** Parses "e4"; returns {@link #NONE} for anything else. */
    public static int parse(String s) {
        if (s == null || s.length() != 2) return NONE;
        int f = s.charAt(0) - 'a', r = s.charAt(1) - '1';
        return onBoard(f, r) ? of(f, r) : NONE;
    }

    public static long bit(int sq) {
        return 1L << sq;
    }
}
