package com.oriondb.chess;

/**
 * Squares 0-63, a1 = 0, b1 = 1, ..., h8 = 63.
 * file = square &amp; 7, rank = square &gt;&gt; 3.
 */
public final class Square {
    public static final int NONE = -1;

    public static final int A1 = 0, B1 = 1, C1 = 2, D1 = 3, E1 = 4, F1 = 5, G1 = 6, H1 = 7;
    public static final int A8 = 56, B8 = 57, C8 = 58, D8 = 59, E8 = 60, F8 = 61, G8 = 62, H8 = 63;

    private Square() {}

    public static int of(int file, int rank) {
        return rank * 8 + file;
    }

    public static int file(int square) {
        return square & 7;
    }

    public static int rank(int square) {
        return square >> 3;
    }

    public static boolean isValid(int square) {
        return square >= 0 && square < 64;
    }

    public static boolean isValid(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    /** "e4" style name. */
    public static String toAlgebraic(int square) {
        if (!isValid(square)) return "-";
        return "" + (char) ('a' + file(square)) + (char) ('1' + rank(square));
    }

    /** Parse "e4" style name; returns NONE if malformed. */
    public static int fromAlgebraic(String s) {
        if (s == null || s.length() != 2) return NONE;
        int file = s.charAt(0) - 'a';
        int rank = s.charAt(1) - '1';
        return isValid(file, rank) ? of(file, rank) : NONE;
    }
}
