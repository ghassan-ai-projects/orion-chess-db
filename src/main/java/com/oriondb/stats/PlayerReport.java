package com.oriondb.stats;

import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.query.Names;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A player's dossier: results by color and year, repertoire, opponents, best wins and worst losses. */
public final class PlayerReport {

    /** Results with one opening (ECO + name) as one color. */
    public record OpeningLine(String eco, String name, Tally tally) {}

    public record Opponent(String name, Tally tally) {}

    /** A notable game: id, opponent, their rating, color played, result, date. */
    public record GameRef(int id, String opponent, int opponentElo, String color, String result, String date) {}

    public record Report(String query, Map<String, Integer> namesMatched, Tally total, Tally asWhite, Tally asBlack,
                         int firstYear, int lastYear, int peakElo, int latestElo,
                         Map<Integer, Tally> byYear, List<OpeningLine> whiteOpenings, List<OpeningLine> blackOpenings,
                         List<Opponent> opponents, List<GameRef> bestWins, List<GameRef> worstLosses,
                         List<GameRef> recent) {}

    private PlayerReport() {}

    /** Builds a report for every game whose White or Black name matches {@code name} (token match). */
    public static Report build(Snapshot snap, String name, int top) {
        Names.Matcher m = new Names.Matcher(name);
        Map<String, Integer> names = new LinkedHashMap<>();
        Tally total = new Tally(), white = new Tally(), black = new Tally();
        Map<Integer, Tally> byYear = new TreeMap<>();
        Map<String, Tally> wOpen = new LinkedHashMap<>(), bOpen = new LinkedHashMap<>();
        Map<String, String> openingNames = new LinkedHashMap<>();
        Map<String, Tally> opponents = new LinkedHashMap<>();
        List<GameRef> wins = new ArrayList<>(), losses = new ArrayList<>();
        List<GameRecord> all = new ArrayList<>();
        int firstYear = 0, lastYear = 0, peak = 0, latestElo = 0, latestDate = -1;

        for (GameRecord r : snap.records()) {
            if (!r.isValid()) continue;
            boolean asWhite = m.matches(r.white()), asBlack = !asWhite && m.matches(r.black());
            if (!asWhite && !asBlack) continue;
            int color = asWhite ? 0 : 1;
            String self = asWhite ? r.white() : r.black();
            String opp = asWhite ? r.black() : r.white();
            int selfElo = asWhite ? r.whiteElo() : r.blackElo();
            int oppElo = asWhite ? r.blackElo() : r.whiteElo();
            names.merge(self, 1, Integer::sum);
            all.add(r);
            if (r.year() > 0) {
                firstYear = firstYear == 0 ? r.year() : Math.min(firstYear, r.year());
                lastYear = Math.max(lastYear, r.year());
            }
            if (selfElo > 0) {
                peak = Math.max(peak, selfElo);
                if (r.date() >= latestDate) {
                    latestDate = r.date();
                    latestElo = selfElo;
                }
            }
            Double score = scoreFor(r, color);
            if (score == null) continue;
            total.add(score, oppElo);
            (color == 0 ? white : black).add(score, oppElo);
            if (r.year() > 0) byYear.computeIfAbsent(r.year(), y -> new Tally()).add(score, oppElo);
            String eco = r.eco() == null ? "?" : r.eco();
            String key = eco + "|" + (r.opening() == null ? "" : familyOf(r.opening()));
            openingNames.putIfAbsent(key, r.opening() == null ? "" : familyOf(r.opening()));
            (color == 0 ? wOpen : bOpen).computeIfAbsent(key, k -> new Tally()).add(score, oppElo);
            opponents.computeIfAbsent(opp, k -> new Tally()).add(score, oppElo);
            GameRef ref = new GameRef(r.id(), opp, oppElo, color == 0 ? "white" : "black", r.resultText(), r.dateText());
            if (score == 1) wins.add(ref);
            if (score == 0) losses.add(ref);
        }
        wins.sort(Comparator.comparingInt(GameRef::opponentElo).reversed());
        losses.sort(Comparator.comparingInt(GameRef::opponentElo));
        List<GameRef> recent = all.stream()
            .sorted(Comparator.comparingInt(GameRecord::date).reversed().thenComparing(Comparator.comparingInt(GameRecord::id).reversed()))
            .limit(top)
            .map(r -> {
                boolean w = m.matches(r.white());
                return new GameRef(r.id(), w ? r.black() : r.white(), w ? r.blackElo() : r.whiteElo(),
                    w ? "white" : "black", r.resultText(), r.dateText());
            }).toList();
        return new Report(name, names, total, white, black, firstYear, lastYear, peak, latestElo, byYear,
            openings(wOpen, openingNames, top), openings(bOpen, openingNames, top),
            opponents.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Tally> e) -> e.getValue().games()).reversed())
                .limit(top).map(e -> new Opponent(e.getKey(), e.getValue())).toList(),
            wins.stream().limit(top).toList(), losses.stream().limit(top).toList(), recent);
    }

    private static List<OpeningLine> openings(Map<String, Tally> map, Map<String, String> names, int top) {
        return map.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, Tally> e) -> e.getValue().games()).reversed())
            .limit(top)
            .map(e -> new OpeningLine(e.getKey().substring(0, e.getKey().indexOf('|')), names.get(e.getKey()), e.getValue()))
            .toList();
    }

    /** "Sicilian Defense: Najdorf Variation, English Attack" -> "Sicilian Defense: Najdorf Variation". */
    static String familyOf(String opening) {
        int comma = opening.indexOf(',');
        return comma > 0 ? opening.substring(0, comma) : opening;
    }

    /** Score of {@code color} (1, 0.5, 0) or null for unfinished games. */
    static Double scoreFor(GameRecord r, int color) {
        return switch (r.resultCode()) {
            case GameRecord.WHITE_WINS -> color == 0 ? 1.0 : 0.0;
            case GameRecord.BLACK_WINS -> color == 1 ? 1.0 : 0.0;
            case GameRecord.DRAW -> 0.5;
            default -> null;
        };
    }
}
