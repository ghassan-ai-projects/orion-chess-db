package com.oriondb.chess;

/**
 * Moves encoded as ints for compactness:
 * <pre>
 *   bits 0-5   from square
 *   bits 6-11  to square
 *   bits 12-14 promotion piece type (0 = none, else Piece.KNIGHT..Piece.QUEEN)
 *   bits 15-17 special flag
 * </pre>
 */
public final class Move {
    public static final int NONE = -1;

    // Special flags
    public static final int NORMAL = 0;
    public static final int CASTLE_KINGSIDE = 1;
    public static final int CASTLE_QUEENSIDE = 2;
    public static final int EN_PASSANT = 3;
    public static final int DOUBLE_PUSH = 4;
    public static final int NULL_MOVE = 5;

    private Move() {}

    public static int make(int from, int to) {
        return from | (to << 6);
    }

    public static int make(int from, int to, int special) {
        return from | (to << 6) | (special << 15);
    }

    public static int makePromotion(int from, int to, int promotionType) {
        return from | (to << 6) | (promotionType << 12);
    }

    public static int nullMove() {
        return NULL_MOVE << 15;
    }

    public static int from(int move) {
        return move & 63;
    }

    public static int to(int move) {
        return (move >> 6) & 63;
    }

    /** Promotion piece type, or 0 if not a promotion. */
    public static int promotion(int move) {
        return (move >> 12) & 7;
    }

    public static int special(int move) {
        return (move >> 15) & 7;
    }

    public static boolean isNull(int move) {
        return special(move) == NULL_MOVE;
    }

    /** UCI notation: "e2e4", "e7e8q". Null move is "0000". */
    public static String toUci(int move) {
        if (move == NONE || isNull(move)) return "0000";
        StringBuilder sb = new StringBuilder(5);
        sb.append(Square.toAlgebraic(from(move)));
        sb.append(Square.toAlgebraic(to(move)));
        int promo = promotion(move);
        if (promo != 0) {
            sb.append(Character.toLowerCase("PNBRQK".charAt(promo)));
        }
        return sb.toString();
    }
}
