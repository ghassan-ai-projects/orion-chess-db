package com.oriondb.search;

import com.oriondb.db.Database;
import com.oriondb.db.GameData;
import com.oriondb.model.Game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Player dossiers and head-to-head reports.
 *
 * Player matching is case-insensitive substring over the White/Black tags:
 * "carlsen" matches "Carlsen, Magnus". The most frequent exact tag value
 * becomes the canonical display name.
 */
public final class PlayerStats {

    /** Win/draw/loss line from the player's perspective. */
    public record Score(int games, int wins, int draws, int losses) {
        public double percent() {
            int decided = wins + draws + losses;
            return decided == 0 ? 0 : Math.round(1000.0 * (wins + draws / 2.0) / decided) / 10.0;
        }

        Score plusWin() { return new Score(games + 1, wins + 1, draws, losses); }
        Score plusDraw() { return new Score(games + 1, wins, draws + 1, losses); }
        Score plusLoss() { return new Score(games + 1, wins, draws, losses + 1); }
        Score plusOther() { return new Score(games + 1, wins, draws, losses); }
    }

    /** One row of a player's opening repertoire. */
    public record OpeningStat(String eco, String name, int games, double scorePercent) {}

    public record BestWin(int gameId, String opponent, int opponentElo, String date) {}

    public record Report(
        String name,
        List<String> matchedNames,
        int totalGames,
        Score asWhite,
        Score asBlack,
        /** Elo performance over rated opponents, or null without data. */
        Integer performance,
        Integer highestElo,
        String firstGameDate,
        String lastGameDate,
        List<OpeningStat> openingsAsWhite,
        List<OpeningStat> openingsAsBlack,
        List<BestWin> bestWins
    ) {}

    public record HeadToHead(
        String playerA,
        String playerB,
        /** From A's perspective. */
        Score overall,
        Score aAsWhite,
        Score aAsBlack,
        List<Integer> gameIds
    ) {}

    private static final int TOP_OPENINGS = 5;
    private static final int TOP_WINS = 5;

    private final Database db;

    public PlayerStats(Database db) {
        this.db = db;
    }

    /** Dossier for a player; null when no games match. */
    public Report report(String playerQuery) {
        String needle = playerQuery.toLowerCase(Locale.ROOT).trim();
        if (needle.isEmpty()) return null;

        Map<String, Integer> nameCounts = new HashMap<>();
        Score asWhite = new Score(0, 0, 0, 0);
        Score asBlack = new Score(0, 0, 0, 0);
        long oppEloSum = 0;
        double ratedPoints = 0;
        int ratedGames = 0;
        Integer highestElo = null;
        String firstDate = null;
        String lastDate = null;
        Map<String, int[]> whiteOpenings = new LinkedHashMap<>(); // eco|name -> {games, 2*points}
        Map<String, int[]> blackOpenings = new LinkedHashMap<>();
        List<BestWin> wins = new ArrayList<>();

        for (Game game : db.games()) {
            boolean isWhite = matches(game.white(), needle);
            boolean isBlack = matches(game.black(), needle);
            if (!isWhite && !isBlack) continue;
            if (isWhite && isBlack) {
                // Same substring on both sides — count as white to avoid double counting.
                isBlack = false;
            }

            String tagName = isWhite ? game.white() : game.black();
            nameCounts.merge(tagName, 1, Integer::sum);

            String result = game.result();
            double points = pointsFor(result, isWhite);

            if (isWhite) {
                asWhite = tally(asWhite, result, true);
            } else {
                asBlack = tally(asBlack, result, false);
            }

            Integer myElo = isWhite ? game.whiteElo() : game.blackElo();
            Integer oppElo = isWhite ? game.blackElo() : game.whiteElo();
            if (myElo != null && (highestElo == null || myElo > highestElo)) {
                highestElo = myElo;
            }
            if (oppElo != null && points >= 0) {
                oppEloSum += oppElo;
                ratedPoints += points;
                ratedGames++;
            }

            String date = game.sortableDate();
            if (date != null) {
                if (firstDate == null || date.compareTo(firstDate) < 0) firstDate = date;
                if (lastDate == null || date.compareTo(lastDate) > 0) lastDate = date;
            }

            // Openings
            GameData data = db.data(game.id());
            String eco = game.tag("ECO");
            if (eco == null && data != null) eco = data.eco();
            String openingName = game.tag("Opening");
            if (openingName == null && data != null) openingName = data.openingName();
            if (eco != null) {
                String key = eco + "|" + (openingName != null ? openingName : "");
                Map<String, int[]> map = isWhite ? whiteOpenings : blackOpenings;
                int[] stat = map.computeIfAbsent(key, k -> new int[2]);
                stat[0]++;
                if (points >= 0) stat[1] += (int) (points * 2);
            }

            // Best wins
            boolean won = (isWhite && result.equals("1-0")) || (!isWhite && result.equals("0-1"));
            if (won && oppElo != null) {
                wins.add(new BestWin(game.id(),
                    isWhite ? game.black() : game.white(), oppElo, date));
            }
        }

        int total = asWhite.games() + asBlack.games();
        if (total == 0) return null;

        String canonical = nameCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(playerQuery);
        List<String> matched = new ArrayList<>(nameCounts.keySet());
        matched.sort(Comparator.comparing(nameCounts::get).reversed());

        Integer performance = null;
        if (ratedGames > 0) {
            double scoreRatio = ratedPoints / ratedGames;
            performance = (int) Math.round(oppEloSum / (double) ratedGames
                + 400 * (2 * scoreRatio - 1));
        }

        wins.sort(Comparator.comparingInt(BestWin::opponentElo).reversed());
        if (wins.size() > TOP_WINS) {
            wins = new ArrayList<>(wins.subList(0, TOP_WINS));
        }

        return new Report(
            canonical, matched, total, asWhite, asBlack,
            performance, highestElo, firstDate, lastDate,
            topOpenings(whiteOpenings), topOpenings(blackOpenings), wins);
    }

    /** Head-to-head between two players; null when they never met. */
    public HeadToHead headToHead(String playerA, String playerB) {
        String a = playerA.toLowerCase(Locale.ROOT).trim();
        String b = playerB.toLowerCase(Locale.ROOT).trim();
        if (a.isEmpty() || b.isEmpty()) return null;

        Score overall = new Score(0, 0, 0, 0);
        Score aWhite = new Score(0, 0, 0, 0);
        Score aBlack = new Score(0, 0, 0, 0);
        List<Integer> ids = new ArrayList<>();
        String nameA = null;
        String nameB = null;

        for (Game game : db.games()) {
            boolean aIsWhite = matches(game.white(), a) && matches(game.black(), b);
            boolean aIsBlack = matches(game.black(), a) && matches(game.white(), b);
            if (!aIsWhite && !aIsBlack) continue;
            if (aIsWhite && aIsBlack) aIsBlack = false; // ambiguous match, count once

            ids.add(game.id());
            String result = game.result();
            if (aIsWhite) {
                nameA = game.white();
                nameB = game.black();
                aWhite = tally(aWhite, result, true);
                overall = tally(overall, result, true);
            } else {
                nameA = game.black();
                nameB = game.white();
                aBlack = tally(aBlack, result, false);
                overall = tally(overall, result, false);
            }
        }

        if (ids.isEmpty()) return null;
        return new HeadToHead(nameA, nameB, overall, aWhite, aBlack, ids);
    }

    // ------------------------------------------------------------------

    private static boolean matches(String tagValue, String needleLower) {
        return tagValue != null && tagValue.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /** Points for the player (1, 0.5, 0) or -1 for unfinished games. */
    private static double pointsFor(String result, boolean asWhite) {
        switch (result) {
            case "1-0": return asWhite ? 1 : 0;
            case "0-1": return asWhite ? 0 : 1;
            case "1/2-1/2": return 0.5;
            default: return -1;
        }
    }

    private static Score tally(Score score, String result, boolean asWhite) {
        switch (result) {
            case "1-0": return asWhite ? score.plusWin() : score.plusLoss();
            case "0-1": return asWhite ? score.plusLoss() : score.plusWin();
            case "1/2-1/2": return score.plusDraw();
            default: return score.plusOther();
        }
    }

    private static List<OpeningStat> topOpenings(Map<String, int[]> openings) {
        List<OpeningStat> stats = new ArrayList<>(openings.size());
        for (Map.Entry<String, int[]> entry : openings.entrySet()) {
            int split = entry.getKey().indexOf('|');
            String eco = entry.getKey().substring(0, split);
            String name = entry.getKey().substring(split + 1);
            int games = entry.getValue()[0];
            double pct = games == 0 ? 0
                : Math.round(1000.0 * entry.getValue()[1] / (2.0 * games)) / 10.0;
            stats.add(new OpeningStat(eco, name.isEmpty() ? null : name, games, pct));
        }
        stats.sort(Comparator.comparingInt(OpeningStat::games).reversed()
            .thenComparing(OpeningStat::eco));
        return stats.size() > TOP_OPENINGS
            ? new ArrayList<>(stats.subList(0, TOP_OPENINGS)) : stats;
    }
}
