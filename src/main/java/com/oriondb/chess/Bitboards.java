package com.oriondb.chess;

/**
 * Precomputed attack tables. Sliding attacks use classical ray lookups:
 * the first blocker along a ray is found with a bit scan and the ray beyond
 * it is masked off.
 */
public final class Bitboards {
    public static final long RANK_1 = 0xFFL, RANK_8 = 0xFFL << 56;
    public static final long FILE_A = 0x0101010101010101L;

    private static final long[] KNIGHT = new long[64];
    private static final long[] KING = new long[64];
    private static final long[][] PAWN = new long[2][64];
    /** Directions N, NE, E, SE, S, SW, W, NW; the first four... see {@link #POSITIVE}. */
    private static final long[][] RAY = new long[8][64];
    private static final int[] DF = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DR = {1, 1, 0, -1, -1, -1, 0, 1};
    /** Whether a direction walks toward higher square indices. */
    private static final boolean[] POSITIVE = {true, true, true, false, false, false, false, true};
    private static final int[] ROOK_DIRS = {0, 2, 4, 6};
    private static final int[] BISHOP_DIRS = {1, 3, 5, 7};
    /** Squares strictly between two aligned squares, else 0. */
    private static final long[][] BETWEEN = new long[64][64];
    /** Full line through two aligned squares (edge to edge), else 0. */
    private static final long[][] LINE = new long[64][64];

    static {
        int[][] knightD = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
        for (int sq = 0; sq < 64; sq++) {
            int f = Square.file(sq), r = Square.rank(sq);
            for (int[] d : knightD) {
                if (Square.onBoard(f + d[0], r + d[1])) KNIGHT[sq] |= Square.bit(Square.of(f + d[0], r + d[1]));
            }
            for (int dir = 0; dir < 8; dir++) {
                if (Square.onBoard(f + DF[dir], r + DR[dir])) KING[sq] |= Square.bit(Square.of(f + DF[dir], r + DR[dir]));
                long ray = 0;
                for (int nf = f + DF[dir], nr = r + DR[dir]; Square.onBoard(nf, nr); nf += DF[dir], nr += DR[dir]) {
                    ray |= Square.bit(Square.of(nf, nr));
                }
                RAY[dir][sq] = ray;
            }
            for (int df = -1; df <= 1; df += 2) {
                if (Square.onBoard(f + df, r + 1)) PAWN[Piece.WHITE][sq] |= Square.bit(Square.of(f + df, r + 1));
                if (Square.onBoard(f + df, r - 1)) PAWN[Piece.BLACK][sq] |= Square.bit(Square.of(f + df, r - 1));
            }
        }
        for (int a = 0; a < 64; a++) {
            for (int dir = 0; dir < 8; dir++) {
                long ray = RAY[dir][a];
                for (long rest = ray; rest != 0; rest &= rest - 1) {
                    int b = Long.numberOfTrailingZeros(rest);
                    BETWEEN[a][b] = ray & ~RAY[dir][b] & ~Square.bit(b);
                    LINE[a][b] = ray | RAY[(dir + 4) & 7][a] | Square.bit(a);
                }
            }
        }
    }

    private Bitboards() {}

    public static long knightAttacks(int sq) {
        return KNIGHT[sq];
    }

    public static long kingAttacks(int sq) {
        return KING[sq];
    }

    /** Squares attacked by a pawn of {@code color} standing on {@code sq}. */
    public static long pawnAttacks(int color, int sq) {
        return PAWN[color][sq];
    }

    private static long slide(int dir, int sq, long occ) {
        long ray = RAY[dir][sq];
        long blockers = ray & occ;
        if (blockers == 0) return ray;
        int b = POSITIVE[dir] ? Long.numberOfTrailingZeros(blockers) : 63 - Long.numberOfLeadingZeros(blockers);
        return ray ^ RAY[dir][b];
    }

    public static long rookAttacks(int sq, long occ) {
        long a = 0;
        for (int dir : ROOK_DIRS) a |= slide(dir, sq, occ);
        return a;
    }

    public static long bishopAttacks(int sq, long occ) {
        long a = 0;
        for (int dir : BISHOP_DIRS) a |= slide(dir, sq, occ);
        return a;
    }

    public static long queenAttacks(int sq, long occ) {
        return rookAttacks(sq, occ) | bishopAttacks(sq, occ);
    }

    /** Attacks of a piece type (not pawns) from {@code sq}. */
    public static long attacks(int type, int sq, long occ) {
        return switch (type) {
            case Piece.KNIGHT -> KNIGHT[sq];
            case Piece.BISHOP -> bishopAttacks(sq, occ);
            case Piece.ROOK -> rookAttacks(sq, occ);
            case Piece.QUEEN -> queenAttacks(sq, occ);
            case Piece.KING -> KING[sq];
            default -> throw new IllegalArgumentException("pawn attacks depend on color");
        };
    }

    public static long between(int a, int b) {
        return BETWEEN[a][b];
    }

    /** The full line through two aligned squares, or 0 if not on a common rank/file/diagonal. */
    public static long line(int a, int b) {
        return LINE[a][b];
    }

    /** Squares from {@code a} to {@code b} inclusive along a rank (for castling paths). */
    public static long rankSpan(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        long span = 0;
        for (int s = lo; s <= hi; s++) span |= Square.bit(s);
        return span;
    }
}
