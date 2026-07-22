package com.oriondb.analysis;

import com.oriondb.chess.Board;
import com.oriondb.chess.Piece;

/**
 * Material signatures packed into an int: 16 bits per side,
 * P(4) N(3) B(3) R(3) Q(3), white in the low half.
 *
 * The human notation is "KRP vs KR" (white first, pieces in Q,R,B,N,P
 * order after the King).
 */
public final class Material {

    private Material() {}

    public static int key(Board board) {
        return key(board.pieceCounts());
    }

    /** From {@link Board#pieceCounts()}. */
    public static int key(int[] counts) {
        int white = side(
            counts[Piece.WHITE_PAWN], counts[Piece.WHITE_KNIGHT], counts[Piece.WHITE_BISHOP],
            counts[Piece.WHITE_ROOK], counts[Piece.WHITE_QUEEN]);
        int black = side(
            counts[Piece.BLACK_PAWN], counts[Piece.BLACK_KNIGHT], counts[Piece.BLACK_BISHOP],
            counts[Piece.BLACK_ROOK], counts[Piece.BLACK_QUEEN]);
        return white | (black << 16);
    }

    private static int side(int pawns, int knights, int bishops, int rooks, int queens) {
        return clamp(pawns, 15) | (clamp(knights, 7) << 4) | (clamp(bishops, 7) << 7)
            | (clamp(rooks, 7) << 10) | (clamp(queens, 7) << 13);
    }

    private static int clamp(int v, int max) {
        return Math.min(v, max);
    }

    /** Swap the two sides of a key. */
    public static int flip(int key) {
        return ((key >>> 16) & 0xFFFF) | ((key & 0xFFFF) << 16);
    }

    public static int pawns(int key, boolean white) {
        return (sideBits(key, white)) & 15;
    }

    public static int knights(int key, boolean white) {
        return (sideBits(key, white) >> 4) & 7;
    }

    public static int bishops(int key, boolean white) {
        return (sideBits(key, white) >> 7) & 7;
    }

    public static int rooks(int key, boolean white) {
        return (sideBits(key, white) >> 10) & 7;
    }

    public static int queens(int key, boolean white) {
        return (sideBits(key, white) >> 13) & 7;
    }

    private static int sideBits(int key, boolean white) {
        return white ? key & 0xFFFF : (key >>> 16) & 0xFFFF;
    }

    /** Total piece count (both sides, kings included). */
    public static int pieceCount(int key) {
        int total = 2;
        for (boolean white : new boolean[]{true, false}) {
            total += pawns(key, white) + knights(key, white) + bishops(key, white)
                + rooks(key, white) + queens(key, white);
        }
        return total;
    }

    /** "KRP vs KR" style rendering, white side first. */
    public static String toString(int key) {
        return sideString(key, true) + " vs " + sideString(key, false);
    }

    private static String sideString(int key, boolean white) {
        StringBuilder sb = new StringBuilder("K");
        append(sb, 'Q', queens(key, white));
        append(sb, 'R', rooks(key, white));
        append(sb, 'B', bishops(key, white));
        append(sb, 'N', knights(key, white));
        append(sb, 'P', pawns(key, white));
        return sb.toString();
    }

    private static void append(StringBuilder sb, char c, int count) {
        for (int i = 0; i < count; i++) sb.append(c);
    }

    /**
     * Parse "KRP vs KR", "KRP-KR", "KRPvKR", or "KRPPvsKRP" (case-insensitive,
     * kings optional). Returns the packed key.
     *
     * @throws IllegalArgumentException on malformed input
     */
    public static int parse(String signature) {
        if (signature == null) throw new IllegalArgumentException("null material signature");
        String s = signature.toUpperCase().replaceAll("\\s+", "");
        String[] sides = s.split("VS|V|-", 2);
        if (sides.length != 2 || sides[0].isEmpty() || sides[1].isEmpty()) {
            throw new IllegalArgumentException(
                "Material signature must have two sides, e.g. \"KRP vs KR\": " + signature);
        }
        return sideFromString(sides[0]) | (sideFromString(sides[1]) << 16);
    }

    private static int sideFromString(String s) {
        int p = 0, n = 0, b = 0, r = 0, q = 0;
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'K': break; // king implied
                case 'Q': q++; break;
                case 'R': r++; break;
                case 'B': b++; break;
                case 'N': n++; break;
                case 'P': p++; break;
                default:
                    throw new IllegalArgumentException("Unknown piece letter '" + c + "'");
            }
        }
        return side(p, n, b, r, q);
    }
}
