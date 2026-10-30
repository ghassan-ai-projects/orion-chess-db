package com.oriondb.query;

import com.oriondb.db.GameRecord;

import java.util.List;

/**
 * One page of matches.
 *
 * @param total   number of matching games (before pagination)
 * @param matches the requested page
 */
public record SearchResult(int total, List<Match> matches) {
    /**
     * A matching game. {@code ply} is where a position/pattern criterion first
     * matched on the mainline (half-moves from the start), {@link #NONE} when
     * there was no such criterion, or {@link #IN_VARIATION} when the position
     * occurs only inside a variation.
     */
    public record Match(GameRecord record, int ply) {
        public static final int NONE = -1, IN_VARIATION = -2;

        public boolean inVariation() {
            return ply == IN_VARIATION;
        }
    }

    public List<GameRecord> records() {
        return matches.stream().map(Match::record).toList();
    }
}
