package com.oriondb.chess;

/**
 * Moves are ints: bits 0-5 from, 6-11 to, 12-14 promotion type (0 = none),
 * 15-16 kind. Castling is encoded as "king takes own rook" (to = rook
 * square), which represents standard chess and Chess960 uniformly.
 * {@link #NONE} (0) is never a valid move.
 */
public final class Move {
    public static final int NONE = 0;

    private static final int NORMAL = 0, CASTLE = 1, EN_PASSANT = 2, NULL_KIND = 3;
    /** The null move ("--" in PGN): passes the turn. */
    public static final int NULL = NULL_KIND << 15;

    private Move() {}

    public static int of(int from, int to) {
        return from | (to << 6);
    }

    public static int promotion(int from, int to, int promotionType) {
        return from | (to << 6) | (promotionType << 12);
    }

    public static int castle(int kingFrom, int rookFrom) {
        return kingFrom | (rookFrom << 6) | (CASTLE << 15);
    }

    public static int enPassant(int from, int to) {
        return from | (to << 6) | (EN_PASSANT << 15);
    }

    public static int from(int move) {
        return move & 63;
    }

    /** Destination; for castling this is the rook's square (see {@link #kingTarget}). */
    public static int to(int move) {
        return (move >>> 6) & 63;
    }

    /** Promotion piece type, or 0 (= PAWN, never a promotion) when none. */
    public static int promotionType(int move) {
        return (move >>> 12) & 7;
    }

    public static boolean isPromotion(int move) {
        return promotionType(move) != 0;
    }

    public static boolean isCastle(int move) {
        return (move >>> 15) == CASTLE;
    }

    public static boolean isEnPassant(int move) {
        return (move >>> 15) == EN_PASSANT;
    }

    public static boolean isNull(int move) {
        return move == NULL;
    }

    public static boolean isKingsideCastle(int move) {
        return isCastle(move) && to(move) > from(move);
    }

    /** Where the king lands when castling (g- or c-file). */
    public static int kingTarget(int move) {
        int rank = Square.rank(from(move));
        return Square.of(isKingsideCastle(move) ? 6 : 2, rank);
    }

    /** Where the rook lands when castling (f- or d-file). */
    public static int rookTarget(int move) {
        int rank = Square.rank(from(move));
        return Square.of(isKingsideCastle(move) ? 5 : 3, rank);
    }

    /** The square the moving piece ends on (king target for castling). */
    public static int destination(int move) {
        return isCastle(move) ? kingTarget(move) : to(move);
    }

    /**
     * 16-bit storage encoding: from(6) | to(6) | promo(2) | kind(2) where promo
     * 0..3 = N, B, R, Q and kind 0 normal, 1 castle, 2 en passant, 3 promotion.
     * The null move encodes as 0 (a1-a1 is never a real move).
     */
    public static short pack(int move) {
        if (isNull(move)) return 0;
        int kind;
        int promo = 0;
        if (isCastle(move)) kind = 1;
        else if (isEnPassant(move)) kind = 2;
        else if (isPromotion(move)) {
            kind = 3;
            promo = promotionType(move) - Piece.KNIGHT;
        } else kind = 0;
        return (short) (from(move) | (to(move) << 6) | (promo << 12) | (kind << 14));
    }

    public static int unpack(short packed) {
        int p = packed & 0xFFFF;
        if (p == 0) return NULL;
        int from = p & 63, to = (p >>> 6) & 63, promo = (p >>> 12) & 3, kind = p >>> 14;
        return switch (kind) {
            case 1 -> castle(from, to);
            case 2 -> enPassant(from, to);
            case 3 -> promotion(from, to, promo + Piece.KNIGHT);
            default -> of(from, to);
        };
    }

    /** Debug form, e.g. "e2e4", "e7e8q", "e1h1(O-O)". Use {@link Uci} or {@link San} for real notation. */
    public static String toString(int move) {
        if (move == NONE) return "(none)";
        if (isNull(move)) return "0000";
        String s = Square.name(from(move)) + Square.name(to(move));
        if (isPromotion(move)) s += Character.toLowerCase(Piece.typeChar(promotionType(move)));
        if (isCastle(move)) s += isKingsideCastle(move) ? "(O-O)" : "(O-O-O)";
        return s;
    }
}
