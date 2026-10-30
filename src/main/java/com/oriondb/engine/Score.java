package com.oriondb.engine;

import java.util.Locale;

/**
 * An engine evaluation: centipawns or a forced mate, from one side's point
 * of view (engines report from the side to move; {@link #forWhite(int)}
 * converts to White's view).
 *
 * <p>Mate scores count moves (not plies): {@code mate(3)} means the
 * viewpoint side mates in 3, {@code mate(-3)} that it gets mated in 3.
 * {@link #mated()} is a position where the viewpoint side is already
 * checkmated (UCI "mate 0"); its negation {@link #mating()} is a position
 * where the opponent is.
 */
public final class Score implements Comparable<Score> {
    /** Centipawn equivalent of a mate, used for clamping and centipawn loss. */
    public static final int MATE_CP = 1000;

    private static final Score MATED = new Score(true, 0, -1);
    private static final Score MATING = new Score(true, 0, 1);

    private final boolean mate;
    private final int value;
    private final int sign;

    private Score(boolean mate, int value, int sign) {
        this.mate = mate;
        this.value = value;
        this.sign = sign;
    }

    /** A centipawn score. */
    public static Score cp(int centipawns) {
        return new Score(false, centipawns, Integer.signum(centipawns));
    }

    /**
     * Mate in {@code moves} (positive: the viewpoint side mates; negative: it
     * is mated). {@code mate(0)} is the same as {@link #mated()}.
     */
    public static Score mate(int moves) {
        return moves == 0 ? MATED : new Score(true, moves, Integer.signum(moves));
    }

    /** The viewpoint side is checkmated. */
    public static Score mated() {
        return MATED;
    }

    /** The viewpoint side has delivered checkmate (the opponent is mated). */
    public static Score mating() {
        return MATING;
    }

    /**
     * Parses a PGN {@code [%eval]} value: pawns ("0.35", "-1.2", "+3") or a
     * mate ("#3", "#-3", "#+3"). "#0"/"#-0" are taken as mates on the board.
     *
     * @throws IllegalArgumentException if the text is not an evaluation
     */
    public static Score parse(String text) {
        String s = text.strip();
        try {
            if (s.startsWith("#")) {
                String n = s.substring(1);
                int moves = Integer.parseInt(n.startsWith("+") ? n.substring(1) : n);
                if (moves == 0) return n.startsWith("-") ? MATED : MATING;
                return mate(moves);
            }
            double pawns = Double.parseDouble(s);
            if (Double.isNaN(pawns) || Double.isInfinite(pawns)) throw new NumberFormatException(s);
            return cp((int) Math.round(pawns * 100));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("not an evaluation: '" + text + "'");
        }
    }

    public boolean isMate() {
        return mate;
    }

    /** Centipawns; for mates, {@code ±MATE_CP}. */
    public int centipawns() {
        return mate ? sign * MATE_CP : value;
    }

    /** Signed moves to mate (0 for a mate on the board or a centipawn score). */
    public int mateIn() {
        return mate ? value : 0;
    }

    /** Centipawns clamped to {@code ±MATE_CP}, mates mapped to the bounds. */
    public int clampedCentipawns() {
        return Math.max(-MATE_CP, Math.min(MATE_CP, centipawns()));
    }

    /** The same evaluation from the opponent's point of view. */
    public Score negate() {
        if (this == MATED) return MATING;
        if (this == MATING) return MATED;
        return new Score(mate, -value, -sign);
    }

    /**
     * Converts a score given from the side to move's point of view (as UCI
     * engines report it) to White's point of view.
     *
     * @param sideToMove 0 = white, 1 = black
     */
    public Score forWhite(int sideToMove) {
        return sideToMove == 0 ? this : negate();
    }

    /**
     * Expected result in [-1, 1] for the viewpoint side, using the lichess
     * model {@code 2 / (1 + exp(-0.00368208 * cp)) - 1} with centipawns
     * clamped to ±1000; mates are ±1.
     */
    public double winningChances() {
        if (mate) return sign;
        int cp = clampedCentipawns();
        return 2 / (1 + Math.exp(-0.00368208 * cp)) - 1;
    }

    /** Win percentage 0..100 for the viewpoint side ({@code 50 + 50 * winningChances}). */
    public double winPercent() {
        return 50 + 50 * winningChances();
    }

    /** Display form: "+0.35", "-1.20", "0.00", "#3", "#-3" ("#0"/"#-0" for a mate on the board). */
    @Override
    public String toString() {
        if (mate) return value == 0 ? (sign > 0 ? "#0" : "#-0") : "#" + value;
        if (value == 0) return "0.00";
        return String.format(Locale.ROOT, "%+.2f", value / 100.0);
    }

    /**
     * PGN {@code [%eval]} form: "0.35", "-1.20", "#3", "#-3". Call it on a
     * White-POV score ({@link #forWhite(int)}), as PGN evals are from White's view.
     */
    public String toPgnEval() {
        if (mate) return toString();
        return String.format(Locale.ROOT, "%.2f", value / 100.0);
    }

    /** Ordering from the viewpoint side: being mated sooner is worst, mating sooner is best. */
    @Override
    public int compareTo(Score o) {
        return Long.compare(sortKey(), o.sortKey());
    }

    private long sortKey() {
        if (!mate) return value;
        return sign > 0 ? 1_000_000L - value : -1_000_000L - value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Score s && s.mate == mate && s.value == value && s.sign == sign;
    }

    @Override
    public int hashCode() {
        return (mate ? 31 : 0) + value * 3 + sign;
    }
}
