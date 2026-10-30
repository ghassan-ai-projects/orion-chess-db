package com.oriondb.analysis;

import com.oriondb.chess.Piece;
import com.oriondb.chess.Position;

/**
 * Material signatures packed into a long: per color 4 bits each for
 * Q, R, B, N, P counts (white in bits 0-19, black in bits 20-39).
 * Written as "KRP vs KR" (white first).
 */
public final class Material {
    private static final int[] ORDER = {Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT, Piece.PAWN};

    private Material() {}

    public static long signature(Position pos) {
        long key = 0;
        for (int color = 0; color < 2; color++) {
            for (int i = 0; i < ORDER.length; i++) {
                long n = Math.min(15, pos.count(color, ORDER[i]));
                key |= n << (color * 20 + i * 4);
            }
        }
        return key;
    }

    /** Count of one piece type in a signature. */
    public static int count(long signature, int color, int type) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == type) return (int) ((signature >>> (color * 20 + i * 4)) & 15);
        }
        return type == Piece.KING ? 1 : 0;
    }

    /** The same material with colors swapped. */
    public static long mirror(long signature) {
        return ((signature & 0xFFFFFL) << 20) | ((signature >>> 20) & 0xFFFFFL);
    }

    /** Total number of pieces including kings. */
    public static int pieceCount(long signature) {
        int n = 2;
        for (int shift = 0; shift < 40; shift += 4) n += (int) ((signature >>> shift) & 15);
        return n;
    }

    /** Material balance in pawns, white minus black (Q9 R5 B3 N3 P1). */
    public static int balance(long signature) {
        int b = 0;
        for (int type = Piece.PAWN; type <= Piece.QUEEN; type++) {
            b += Piece.value(type) * (count(signature, Piece.WHITE, type) - count(signature, Piece.BLACK, type));
        }
        return b;
    }

    public static String format(long signature) {
        return side(signature, Piece.WHITE) + " vs " + side(signature, Piece.BLACK);
    }

    private static String side(long signature, int color) {
        StringBuilder sb = new StringBuilder("K");
        for (int type : ORDER) {
            sb.append(String.valueOf(Piece.typeChar(type)).repeat(count(signature, color, type)));
        }
        return sb.toString();
    }

    /**
     * Parses "KRP vs KR", "KRPvKR", "KRP-KR" or "KRPKR" (second K starts black).
     *
     * @throws IllegalArgumentException for malformed input
     */
    public static long parse(String text) {
        String s = text.replaceAll("\\s+", "").toUpperCase().replace("VS", "V").replace("-", "V");
        String white, black;
        int v = s.indexOf('V');
        if (v >= 0) {
            white = s.substring(0, v);
            black = s.substring(v + 1);
        } else {
            int k2 = s.indexOf('K', 1);
            if (!s.startsWith("K") || k2 < 0) throw new IllegalArgumentException("bad material signature '" + text + "'");
            white = s.substring(0, k2);
            black = s.substring(k2);
        }
        return parseSide(white, Piece.WHITE, text) | parseSide(black, Piece.BLACK, text);
    }

    private static long parseSide(String side, int color, String text) {
        if (!side.startsWith("K")) side = "K" + side;
        long key = 0;
        for (char c : side.substring(1).toCharArray()) {
            int idx = "QRBNP".indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("bad material signature '" + text + "'");
            key += 1L << (color * 20 + idx * 4);
        }
        return key;
    }
}
