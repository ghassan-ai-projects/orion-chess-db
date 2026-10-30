package com.oriondb.stats;

import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Overview of a database: sizes, date range, results, top players/events/openings, labels. */
public final class DatabaseStats {

    public record Count(String name, int games) {}

    public record Stats(int games, int invalid, int indexedPositions, int firstYear, int lastYear,
                        int whiteWins, int draws, int blackWins, int unfinished, double averagePlies,
                        int averageElo, int annotated, int withEvals, int chess960,
                        List<Count> players, List<Count> events, List<Count> openings, List<Count> labels) {}

    private DatabaseStats() {}

    public static Stats compute(Snapshot snap, int top) {
        int invalid = 0, first = 0, last = 0, ww = 0, dr = 0, bw = 0, un = 0, annotated = 0, evals = 0, c960 = 0;
        long plies = 0, eloSum = 0;
        int eloN = 0, valid = 0;
        Map<String, Integer> players = new HashMap<>(), events = new HashMap<>(), openings = new HashMap<>(), labels = new HashMap<>();
        for (GameRecord r : snap.records()) {
            for (String l : r.labels()) labels.merge(l, 1, Integer::sum);
            if (!r.isValid()) {
                invalid++;
                continue;
            }
            valid++;
            if (r.year() > 0) {
                first = first == 0 ? r.year() : Math.min(first, r.year());
                last = Math.max(last, r.year());
            }
            switch (r.resultCode()) {
                case GameRecord.WHITE_WINS -> ww++;
                case GameRecord.BLACK_WINS -> bw++;
                case GameRecord.DRAW -> dr++;
                default -> un++;
            }
            plies += r.plyCount();
            for (int elo : new int[] {r.whiteElo(), r.blackElo()}) {
                if (elo > 0) {
                    eloSum += elo;
                    eloN++;
                }
            }
            if ((r.flags() & (GameRecord.HAS_COMMENTS | GameRecord.HAS_VARIATIONS | GameRecord.HAS_NAGS)) != 0) annotated++;
            if (r.has(GameRecord.HAS_EVALS)) evals++;
            if (r.has(GameRecord.CHESS960)) c960++;
            if (!r.white().isBlank()) players.merge(r.white(), 1, Integer::sum);
            if (!r.black().isBlank()) players.merge(r.black(), 1, Integer::sum);
            if (!r.event().isBlank() && !r.event().equals("?")) events.merge(r.event(), 1, Integer::sum);
            if (r.eco() != null) openings.merge(r.eco() + (r.opening() == null ? "" : " " + PlayerReport.familyOf(r.opening())), 1, Integer::sum);
        }
        return new Stats(valid + invalid, invalid, snap.indexedPositions(), first, last, ww, dr, bw, un,
            valid == 0 ? 0 : Math.round(10.0 * plies / valid) / 10.0, eloN == 0 ? 0 : (int) (eloSum / eloN),
            annotated, evals, c960, topN(players, top), topN(events, top), topN(openings, top), topN(labels, Integer.MAX_VALUE));
    }

    static List<Count> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(n).map(e -> new Count(e.getKey(), e.getValue())).toList();
    }

    /** Label usage counts (label → games). */
    public static Map<String, Integer> labels(Snapshot snap) {
        Map<String, Integer> out = new LinkedHashMap<>();
        snap.records().stream().flatMap(r -> r.labels().stream()).sorted(Comparator.naturalOrder())
            .forEach(l -> out.merge(l, 1, Integer::sum));
        return out;
    }
}
