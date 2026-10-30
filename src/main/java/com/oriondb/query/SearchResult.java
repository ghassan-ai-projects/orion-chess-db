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
     * matched (half-moves from the start), or -1 when there was none.
     */
    public record Match(GameRecord record, int ply) {}

    public List<GameRecord> records() {
        return matches.stream().map(Match::record).toList();
    }
}
