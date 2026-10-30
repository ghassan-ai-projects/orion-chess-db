package com.oriondb.analysis;

import com.oriondb.chess.Bitboards;
import com.oriondb.chess.Piece;
import com.oriondb.chess.Position;
import com.oriondb.chess.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Structural features, detected per color. A game "has" a feature for a
 * color when it persists for at least {@link #MIN_PLIES} consecutive mainline
 * plies after the opening (ply 10).
 */
public enum PawnFeature {
    ISOLATED_QUEEN_PAWN, HANGING_PAWNS, PASSED_PAWN, CONNECTED_PASSED_PAWNS, DOUBLED_PAWNS,
    MAROCZY_BIND, CARLSBAD, STONEWALL, OPPOSITE_COLORED_BISHOPS;

    public static final int MIN_PLIES = 4;
    private static final int FROM_PLY = 10;
    private static final long DARK = 0xAA55AA55AA55AA55L;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Bit for this feature and color in a feature mask. */
    public int bit(int color) {
        return 1 << (ordinal() * 2 + color);
    }

    public static PawnFeature parse(String s) {
        String k = s.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (k.equals("IQP")) return ISOLATED_QUEEN_PAWN;
        for (PawnFeature f : values()) if (f.name().equals(k)) return f;
        throw new IllegalArgumentException("unknown structure '" + s + "'; known: iqp, " + keysList());
    }

    public static String keysList() {
        List<String> out = new ArrayList<>();
        for (PawnFeature f : values()) out.add(f.key());
        return String.join(", ", out);
    }

    /** Readable list like "isolated_queen_pawn(white)". */
    public static List<String> describe(int mask) {
        List<String> out = new ArrayList<>();
        for (PawnFeature f : values()) {
            for (int c = 0; c < 2; c++) if ((mask & f.bit(c)) != 0) out.add(f.key() + "(" + Piece.colorName(c) + ")");
        }
        return out;
    }

    /** Feature mask over a line of positions (index = ply). */
    public static int gameMask(Position[] positions) {
        int mask = 0;
        int[] run = new int[values().length * 2];
        for (int ply = FROM_PLY; ply < positions.length; ply++) {
            int present = mask(positions[ply]);
            for (int b = 0; b < run.length; b++) {
                if ((present & (1 << b)) != 0) {
                    if (++run[b] >= MIN_PLIES) mask |= 1 << b;
                } else {
                    run[b] = 0;
                }
            }
        }
        return mask;
    }

    /** Features present in one position. */
    public static int mask(Position p) {
        int m = 0;
        for (int c = 0; c < 2; c++) {
            for (PawnFeature f : values()) if (f.present(p, c)) m |= f.bit(c);
        }
        return m;
    }

    private static long fileMask(int file) {
        return Bitboards.FILE_A << file;
    }

    private static boolean has(long pawns, int file, int rank) {
        return (pawns & Square.bit(Square.of(file, rank))) != 0;
    }

    public boolean present(Position p, int c) {
        long own = p.pieces(c, Piece.PAWN), opp = p.pieces(c ^ 1, Piece.PAWN);
        int r2 = c == 0 ? 1 : 6, r3 = c == 0 ? 2 : 5, r4 = c == 0 ? 3 : 4, r5 = c == 0 ? 4 : 3, r6 = c == 0 ? 5 : 2;
        return switch (this) {
            case ISOLATED_QUEEN_PAWN -> (own & fileMask(3)) != 0 && (own & (fileMask(2) | fileMask(4))) == 0
                && (opp & fileMask(3)) == 0;
            case HANGING_PAWNS -> (own & fileMask(2)) != 0 && (own & fileMask(3)) != 0
                && (own & (fileMask(1) | fileMask(4))) == 0 && (opp & (fileMask(2) | fileMask(3))) == 0;
            case PASSED_PAWN -> passers(own, opp, c) != 0;
            case CONNECTED_PASSED_PAWNS -> {
                long passers = passers(own, opp, c);
                boolean connected = false;
                for (int f = 0; f < 7; f++) {
                    if ((passers & fileMask(f)) != 0 && (passers & fileMask(f + 1)) != 0) connected = true;
                }
                yield connected;
            }
            case DOUBLED_PAWNS -> {
                boolean doubled = false;
                for (int f = 0; f < 8; f++) if (Long.bitCount(own & fileMask(f)) >= 2) doubled = true;
                yield doubled;
            }
            case MAROCZY_BIND -> has(own, 2, r4) && has(own, 4, r4) && (own & fileMask(3)) == 0
                && (opp & fileMask(2)) == 0 && has(opp, 3, r6);
            case CARLSBAD -> has(own, 3, r4) && has(own, 4, r3) && (own & fileMask(2)) == 0
                && has(opp, 2, r6) && has(opp, 3, r5) && (opp & fileMask(4)) == 0;
            case STONEWALL -> has(own, 2, r3) && has(own, 3, r4) && has(own, 4, r3) && has(own, 5, r4);
            case OPPOSITE_COLORED_BISHOPS -> {
                long wb = p.pieces(Piece.WHITE, Piece.BISHOP), bb = p.pieces(Piece.BLACK, Piece.BISHOP);
                // Symmetric feature: report it once, for white.
                yield c == 0 && Long.bitCount(wb) == 1 && Long.bitCount(bb) == 1
                    && ((wb & DARK) != 0) != ((bb & DARK) != 0);
            }
        };
    }

    private static long passers(long own, long opp, int c) {
        long result = 0;
        for (long t = own; t != 0; t &= t - 1) {
            int sq = Long.numberOfTrailingZeros(t);
            int f = Square.file(sq), r = Square.rank(sq);
            long front = 0;
            for (int ff = Math.max(0, f - 1); ff <= Math.min(7, f + 1); ff++) {
                if (c == 0) for (int rr = r + 1; rr < 8; rr++) front |= Square.bit(Square.of(ff, rr));
                else for (int rr = r - 1; rr >= 0; rr--) front |= Square.bit(Square.of(ff, rr));
            }
            if ((opp & front) == 0) result |= Square.bit(sq);
        }
        return result;
    }
}
