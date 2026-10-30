package com.oriondb.chess;

/**
 * Pieces are ints {@code color * 6 + type}; {@link #NONE} is an empty square.
 * Types: PAWN..KING = 0..5. Colors: WHITE = 0, BLACK = 1.
 */
public final class Piece {
    public static final int WHITE = 0, BLACK = 1;
    public static final int PAWN = 0, KNIGHT = 1, BISHOP = 2, ROOK = 3, QUEEN = 4, KING = 5;
    public static final int NONE = -1;

    public static final int W_PAWN = 0, W_KNIGHT = 1, W_BISHOP = 2, W_ROOK = 3, W_QUEEN = 4, W_KING = 5;
    public static final int B_PAWN = 6, B_KNIGHT = 7, B_BISHOP = 8, B_ROOK = 9, B_QUEEN = 10, B_KING = 11;

    private static final String FEN_CHARS = "PNBRQKpnbrqk";
    private static final String TYPE_CHARS = "PNBRQK";
    /** Conventional material values in pawns, king = 0. */
    private static final int[] VALUES = {1, 3, 3, 5, 9, 0};

    private Piece() {}

    public static int make(int color, int type) {
        return color * 6 + type;
    }

    public static int color(int piece) {
        return piece / 6;
    }

    public static int type(int piece) {
        return piece % 6;
    }

    public static int opposite(int color) {
        return color ^ 1;
    }

    public static int value(int type) {
        return VALUES[type];
    }

    /** FEN letter: uppercase for white. */
    public static char fenChar(int piece) {
        return FEN_CHARS.charAt(piece);
    }

    /** Parses a FEN letter, or {@link #NONE}. */
    public static int fromFenChar(char c) {
        int i = FEN_CHARS.indexOf(c);
        return i < 0 ? NONE : i;
    }

    /** Uppercase type letter as used in SAN ("P" for pawns). */
    public static char typeChar(int type) {
        return TYPE_CHARS.charAt(type);
    }

    /** Type from an uppercase or lowercase letter (P N B R Q K), or {@link #NONE}. */
    public static int typeFromChar(char c) {
        return TYPE_CHARS.indexOf(Character.toUpperCase(c));
    }

    public static String colorName(int color) {
        return color == WHITE ? "white" : "black";
    }
}
