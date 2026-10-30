package com.oriondb.stats;

/** Rating arithmetic: expected scores and performance ratings (logistic Elo model). */
public final class Performance {
    private Performance() {}

    /** Rating difference that yields score fraction {@code p}, capped at ±800. */
    public static int ratingDifference(double p) {
        if (p >= 0.99) return 800;
        if (p <= 0.01) return -800;
        return (int) Math.round(Math.max(-800, Math.min(800, -400 * Math.log10(1 / p - 1))));
    }

    /** Performance rating from total score, games and average opponent rating; 0 if unknown. */
    public static int rating(double score, int games, double averageOpponent) {
        if (games == 0 || averageOpponent <= 0) return 0;
        return (int) Math.round(averageOpponent + ratingDifference(score / games));
    }

    public static double expected(int rating, int opponent) {
        return 1 / (1 + Math.pow(10, (opponent - rating) / 400.0));
    }
}
