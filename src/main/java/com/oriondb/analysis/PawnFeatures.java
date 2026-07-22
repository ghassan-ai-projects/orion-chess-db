package com.oriondb.analysis;

import com.oriondb.chess.Board;
import com.oriondb.chess.Piece;
import com.oriondb.chess.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pawn structure features as a bitmask. Computed per position; a game's
 * mask is the OR over all its positions ("the game featured ...").
 */
public final class PawnFeatures {
    public static final int WHITE_IQP = 1;          // isolated d-pawn
    public static final int BLACK_IQP = 1 << 1;
    public static final int WHITE_DOUBLED = 1 << 2;
    public static final int BLACK_DOUBLED = 1 << 3;
    public static final int WHITE_ISOLATED = 1 << 4; // any isolated pawn
    public static final int BLACK_ISOLATED = 1 << 5;
    public static final int WHITE_PASSED = 1 << 6;   // any passed pawn
    public static final int BLACK_PASSED = 1 << 7;
    public static final int WHITE_ADVANCED_PASSER = 1 << 8;  // passed pawn on 6th rank or beyond
    public static final int BLACK_ADVANCED_PASSER = 1 << 9;

    private PawnFeatures() {}

    public static int compute(Board board) {
        // File occupation per side
        int[] whitePerFile = new int[8];
        int[] blackPerFile = new int[8];
        long whitePawns = 0;
        long blackPawns = 0;
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.pieceAt(sq);
            if (piece == Piece.WHITE_PAWN) {
                whitePerFile[Square.file(sq)]++;
                whitePawns |= 1L << sq;
            } else if (piece == Piece.BLACK_PAWN) {
                blackPerFile[Square.file(sq)]++;
                blackPawns |= 1L << sq;
            }
        }

        int mask = 0;
        mask |= sideFeatures(true, whitePerFile, blackPerFile, whitePawns, blackPawns);
        mask |= sideFeatures(false, blackPerFile, whitePerFile, blackPawns, whitePawns);
        return mask;
    }

    private static int sideFeatures(boolean white, int[] mine, int[] theirs,
                                    long myPawns, long theirPawns) {
        int mask = 0;

        // Doubled
        for (int f = 0; f < 8; f++) {
            if (mine[f] >= 2) {
                mask |= white ? WHITE_DOUBLED : BLACK_DOUBLED;
                break;
            }
        }

        // Isolated (and the IQP special case: isolated d-pawn)
        for (int f = 0; f < 8; f++) {
            if (mine[f] == 0) continue;
            boolean leftEmpty = f == 0 || mine[f - 1] == 0;
            boolean rightEmpty = f == 7 || mine[f + 1] == 0;
            if (leftEmpty && rightEmpty) {
                mask |= white ? WHITE_ISOLATED : BLACK_ISOLATED;
                if (f == 3 && mine[f] == 1) {
                    mask |= white ? WHITE_IQP : BLACK_IQP;
                }
            }
        }

        // Passed pawns: no enemy pawn ahead on the same or adjacent files
        for (int sq = 0; sq < 64; sq++) {
            if ((myPawns & (1L << sq)) == 0) continue;
            int file = Square.file(sq);
            int rank = Square.rank(sq);
            boolean passed = true;
            for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1) && passed; f++) {
                if (white) {
                    for (int r = rank + 1; r < 8; r++) {
                        if ((theirPawns & (1L << Square.of(f, r))) != 0) {
                            passed = false;
                            break;
                        }
                    }
                } else {
                    for (int r = rank - 1; r >= 0; r--) {
                        if ((theirPawns & (1L << Square.of(f, r))) != 0) {
                            passed = false;
                            break;
                        }
                    }
                }
            }
            if (passed) {
                mask |= white ? WHITE_PASSED : BLACK_PASSED;
                boolean advanced = white ? rank >= 5 : rank <= 2;
                if (advanced) {
                    mask |= white ? WHITE_ADVANCED_PASSER : BLACK_ADVANCED_PASSER;
                }
            }
        }

        return mask;
    }

    /** Human-readable names of the set bits. */
    public static List<String> names(int mask) {
        List<String> names = new ArrayList<>();
        if ((mask & WHITE_IQP) != 0) names.add("white-iqp");
        if ((mask & BLACK_IQP) != 0) names.add("black-iqp");
        if ((mask & WHITE_DOUBLED) != 0) names.add("white-doubled");
        if ((mask & BLACK_DOUBLED) != 0) names.add("black-doubled");
        if ((mask & WHITE_ISOLATED) != 0) names.add("white-isolated");
        if ((mask & BLACK_ISOLATED) != 0) names.add("black-isolated");
        if ((mask & WHITE_PASSED) != 0) names.add("white-passed");
        if ((mask & BLACK_PASSED) != 0) names.add("black-passed");
        if ((mask & WHITE_ADVANCED_PASSER) != 0) names.add("white-advanced-passer");
        if ((mask & BLACK_ADVANCED_PASSER) != 0) names.add("black-advanced-passer");
        return names;
    }

    /** Parse a feature name to its bit; 0 if unknown. */
    public static int fromName(String name) {
        if (name == null) return 0;
        switch (name.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "white-iqp": return WHITE_IQP;
            case "black-iqp": return BLACK_IQP;
            case "iqp": return WHITE_IQP | BLACK_IQP;
            case "white-doubled": return WHITE_DOUBLED;
            case "black-doubled": return BLACK_DOUBLED;
            case "doubled": return WHITE_DOUBLED | BLACK_DOUBLED;
            case "white-isolated": return WHITE_ISOLATED;
            case "black-isolated": return BLACK_ISOLATED;
            case "isolated": return WHITE_ISOLATED | BLACK_ISOLATED;
            case "white-passed": return WHITE_PASSED;
            case "black-passed": return BLACK_PASSED;
            case "passed": return WHITE_PASSED | BLACK_PASSED;
            case "white-advanced-passer": return WHITE_ADVANCED_PASSER;
            case "black-advanced-passer": return BLACK_ADVANCED_PASSER;
            case "advanced-passer": return WHITE_ADVANCED_PASSER | BLACK_ADVANCED_PASSER;
            default: return 0;
        }
    }
}
