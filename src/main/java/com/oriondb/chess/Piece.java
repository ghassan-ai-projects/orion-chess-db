package com.oriondb.chess;

/**
 * Piece encoding: types 0-5 (P,N,B,R,Q,K), color bit 8.
 * White pieces are 0-5, black pieces are 8-13, empty is NONE (-1).
 */
public final class Piece {
    public static final int PAWN = 0;
    public static final int KNIGHT = 1;
    public static final int BISHOP = 2;
    public static final int ROOK = 3;
    public static final int QUEEN = 4;
    public static final int KING = 5;

    public static final int WHITE = 0;
    public static final int BLACK = 8;

    public static final int WHITE_PAWN = PAWN | WHITE;
    public static final int WHITE_KNIGHT = KNIGHT | WHITE;
    public static final int WHITE_BISHOP = BISHOP | WHITE;
    public static final int WHITE_ROOK = ROOK | WHITE;
    public static final int WHITE_QUEEN = QUEEN | WHITE;
    public static final int WHITE_KING = KING | WHITE;

    public static final int BLACK_PAWN = PAWN | BLACK;
    public static final int BLACK_KNIGHT = KNIGHT | BLACK;
    public static final int BLACK_BISHOP = BISHOP | BLACK;
    public static final int BLACK_ROOK = ROOK | BLACK;
    public static final int BLACK_QUEEN = QUEEN | BLACK;
    public static final int BLACK_KING = KING | BLACK;

    public static final int NONE = -1;

    private static final char[] TYPE_CHARS = {'P', 'N', 'B', 'R', 'Q', 'K'};
    private static final String[] TYPE_NAMES = {"Pawn", "Knight", "Bishop", "Rook", "Queen", "King"};

    private Piece() {}

    /** Piece type (0-5) regardless of color. */
    public static int type(int piece) {
        return piece & 7;
    }

    /** Piece color: WHITE (0) or BLACK (8). */
    public static int color(int piece) {
        return piece & 8;
    }

    public static boolean isWhite(int piece) {
        return piece != NONE && (piece & 8) == 0;
    }

    public static boolean isBlack(int piece) {
        return piece != NONE && (piece & 8) == 8;
    }

    public static int make(int type, int color) {
        return type | color;
    }

    public static int opposite(int color) {
        return color ^ 8;
    }

    /** Uppercase letter for white, lowercase for black (FEN convention). */
    public static char toFenChar(int piece) {
        if (piece == NONE) return '.';
        char c = TYPE_CHARS[type(piece)];
        return isBlack(piece) ? Character.toLowerCase(c) : c;
    }

    /** Parse a FEN piece character; returns NONE for unknown characters. */
    public static int fromFenChar(char c) {
        int color = Character.isLowerCase(c) ? BLACK : WHITE;
        switch (Character.toUpperCase(c)) {
            case 'P': return PAWN | color;
            case 'N': return KNIGHT | color;
            case 'B': return BISHOP | color;
            case 'R': return ROOK | color;
            case 'Q': return QUEEN | color;
            case 'K': return KING | color;
            default: return NONE;
        }
    }

    /** SAN letter for a piece type: "" for pawns, otherwise N/B/R/Q/K. */
    public static String sanLetter(int type) {
        return type == PAWN ? "" : String.valueOf(TYPE_CHARS[type]);
    }

    /** Parse a SAN piece letter (N/B/R/Q/K) to a type, or -1 if not a piece letter. */
    public static int typeFromSanLetter(char c) {
        switch (c) {
            case 'N': return KNIGHT;
            case 'B': return BISHOP;
            case 'R': return ROOK;
            case 'Q': return QUEEN;
            case 'K': return KING;
            default: return -1;
        }
    }

    public static String name(int piece) {
        if (piece == NONE) return "None";
        return (isWhite(piece) ? "White " : "Black ") + TYPE_NAMES[type(piece)];
    }
}
