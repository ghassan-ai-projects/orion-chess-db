package com.oriondb.stats;

/** Wins/draws/losses from one side's point of view, with rating sums for performance. */
public final class Tally {
    private int wins, draws, losses;
    private long opponentEloSum;
    private int opponentEloCount;

    public void add(double score, int opponentElo) {
        if (score == 1) wins++;
        else if (score == 0) losses++;
        else draws++;
        if (opponentElo > 0) {
            opponentEloSum += opponentElo;
            opponentEloCount++;
        }
    }

    public int games() { return wins + draws + losses; }
    public int wins() { return wins; }
    public int draws() { return draws; }
    public int losses() { return losses; }
    public double points() { return wins + draws * 0.5; }

    /** Score percentage 0..100 (0 when empty). */
    public double percent() {
        return games() == 0 ? 0 : 100.0 * points() / games();
    }

    public int averageOpponentElo() {
        return opponentEloCount == 0 ? 0 : (int) Math.round((double) opponentEloSum / opponentEloCount);
    }

    /** Performance over games with a rated opponent (0 if none). */
    public int performance() {
        return opponentEloCount == 0 ? 0 : Performance.rating(points(), games(), averageOpponentElo());
    }

    public void merge(Tally o) {
        wins += o.wins;
        draws += o.draws;
        losses += o.losses;
        opponentEloSum += o.opponentEloSum;
        opponentEloCount += o.opponentEloCount;
    }

    @Override
    public String toString() {
        return String.format("+%d =%d -%d (%.1f%%)", wins, draws, losses, percent());
    }
}
