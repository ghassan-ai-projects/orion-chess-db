package com.oriondb.stats;

import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Lifetime score between two players, from the first player's point of view. */
public final class HeadToHead {

    public record Result(String playerA, String playerB, Tally total, Tally aAsWhite, Tally aAsBlack,
                         Map<Integer, Tally> byYear, List<Integer> gameIds) {}

    private HeadToHead() {}

    public static Result compare(Snapshot snap, String a, String b) {
        java.util.function.Predicate<String> ma = com.oriondb.query.Searcher.playerMatcher(snap, a),
            mb = com.oriondb.query.Searcher.playerMatcher(snap, b);
        Tally total = new Tally(), asWhite = new Tally(), asBlack = new Tally();
        Map<Integer, Tally> byYear = new TreeMap<>();
        List<GameRecord> games = new ArrayList<>();
        for (GameRecord r : snap.records()) {
            if (!r.isValid()) continue;
            int aColor;
            if (ma.test(r.white()) && mb.test(r.black())) aColor = 0;
            else if (ma.test(r.black()) && mb.test(r.white())) aColor = 1;
            else continue;
            games.add(r);
            Double s = PlayerReport.scoreFor(r, aColor);
            if (s == null) continue;
            int oppElo = aColor == 0 ? r.blackElo() : r.whiteElo();
            total.add(s, oppElo);
            (aColor == 0 ? asWhite : asBlack).add(s, oppElo);
            if (r.year() > 0) byYear.computeIfAbsent(r.year(), y -> new Tally()).add(s, oppElo);
        }
        games.sort(Comparator.comparingInt(GameRecord::date).thenComparingInt(GameRecord::id));
        return new Result(a, b, total, asWhite, asBlack, byYear, games.stream().map(GameRecord::id).toList());
    }
}
