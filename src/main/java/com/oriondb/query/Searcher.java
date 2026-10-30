package com.oriondb.query;

import com.oriondb.analysis.Motif;
import com.oriondb.analysis.PawnFeature;
import com.oriondb.chess.Position;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.index.PositionIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Executes a {@link Query} against a {@link Snapshot}: header and analysis
 * filters as a parallel scan over records, positions via the index, and
 * piece patterns by replaying only the games that survived the cheaper filters.
 */
public final class Searcher {
    private Searcher() {}

    public static SearchResult search(Snapshot snap, Query q) {
        Map<Integer, Integer> positionPly = null;
        if (q.position != null) {
            positionPly = new HashMap<>();
            for (PositionIndex.Hit h : snap.positionHits(q.position)) {
                if (!h.inMainline() && !q.positionInVariations) continue;
                // Prefer the first mainline ply; -1 only when the position occurs solely in variations.
                positionPly.merge(h.gameId(), h.ply(), (a, b) -> a < 0 ? b : b < 0 ? a : Math.min(a, b));
            }
        }
        Predicate<GameRecord> filter = compile(snap, q);
        Map<Integer, Integer> byPosition = positionPly;
        List<GameRecord> candidates;
        if (byPosition != null) {
            candidates = new ArrayList<>(byPosition.size());
            for (int id : byPosition.keySet()) {
                GameRecord r = snap.find(id);
                if (r != null && filter.test(r)) candidates.add(r);
            }
        } else {
            candidates = snap.records().parallelStream().filter(filter).toList();
        }

        List<SearchResult.Match> matches = new ArrayList<>(candidates.size());
        if (q.pattern != null) {
            List<SearchResult.Match> found = candidates.parallelStream().map(r -> {
                int ply = patternPly(r, q.pattern);
                return ply < 0 ? null : new SearchResult.Match(r, ply);
            }).filter(m -> m != null).toList();
            matches.addAll(found);
        } else {
            for (GameRecord r : candidates) {
                int ply = byPosition == null ? SearchResult.Match.NONE : byPosition.get(r.id());
                matches.add(new SearchResult.Match(r, ply == PositionIndex.Hit.VARIATION ? SearchResult.Match.IN_VARIATION : ply));
            }
        }
        matches.sort(comparator(q));
        int total = matches.size();
        int from = Math.min(q.offset, total);
        int to = (int) Math.min((long) from + q.limit, total);
        return new SearchResult(total, List.copyOf(matches.subList(from, to)));
    }

    /** Ids of every match, ignoring pagination. */
    public static List<Integer> ids(Snapshot snap, Query q) {
        int offset = q.offset, limit = q.limit;
        q.all();
        try {
            return search(snap, q).matches().stream().map(m -> m.record().id()).toList();
        } finally {
            q.page(offset, limit);
        }
    }

    private static int patternPly(GameRecord r, PiecePattern pattern) {
        Position p = r.startPosition();
        if (pattern.matches(p)) return 0;
        for (int i = 0; i < r.plyCount(); i++) {
            p = p.play(r.move(i));
            if (pattern.matches(p)) return i + 1;
        }
        return -1;
    }

    private static Comparator<SearchResult.Match> comparator(Query q) {
        Comparator<GameRecord> c = switch (q.sort) {
            case ID -> Comparator.comparingInt(GameRecord::id);
            case DATE -> Comparator.comparingInt(GameRecord::date);
            case WHITE -> Comparator.comparing(r -> r.white().toLowerCase(Locale.ROOT));
            case BLACK -> Comparator.comparing(r -> r.black().toLowerCase(Locale.ROOT));
            case EVENT -> Comparator.comparing(r -> r.event().toLowerCase(Locale.ROOT));
            case ELO -> Comparator.comparingInt(Searcher::averageElo);
            case PLY -> Comparator.comparingInt(GameRecord::plyCount);
            case ECO -> Comparator.comparing(r -> r.eco() == null ? "~" : r.eco());
        };
        if (q.descending) c = c.reversed();
        c = c.thenComparingInt(GameRecord::id);
        Comparator<GameRecord> fc = c;
        return (a, b) -> fc.compare(a.record(), b.record());
    }

    static int averageElo(GameRecord r) {
        int w = r.whiteElo(), b = r.blackElo();
        if (w > 0 && b > 0) return (w + b) / 2;
        return Math.max(w, b);
    }

    // ------------------------------------------------------------ filters

    /**
     * A player-name query matches every spelling of every player identity it
     * matches (so "kasparov garry" also finds "Kasparov, Gary" and "Kasparov,G").
     */
    /** Name predicate used by player filters, reports and head-to-head (identity-aware). */
    public static Predicate<String> playerMatcher(Snapshot snap, String query) {
        return playerNames(PlayerIndex.of(snap), query);
    }

    private static Predicate<String> playerNames(PlayerIndex players, String query) {
        java.util.Set<String> spellings = new java.util.HashSet<>();
        for (PlayerIndex.Player p : players.search(query, Integer.MAX_VALUE)) spellings.addAll(p.spellings());
        return spellings::contains;
    }

    private static Predicate<GameRecord> compile(Snapshot snap, Query q) {
        PlayerIndex players = q.white != null || q.black != null || q.player != null ? PlayerIndex.of(snap) : null;
        List<Predicate<GameRecord>> fs = new ArrayList<>();
        fs.add(GameRecord::isValid);
        if (q.ids != null) fs.add(r -> q.ids.contains(r.id()));
        if (q.white != null) {
            java.util.function.Predicate<String> m = playerNames(players, q.white);
            fs.add(r -> m.test(r.white()));
        }
        if (q.black != null) {
            java.util.function.Predicate<String> m = playerNames(players, q.black);
            fs.add(r -> m.test(r.black()));
        }
        if (q.player != null) {
            java.util.function.Predicate<String> m = playerNames(players, q.player);
            Query.Color color = q.playerColor;
            Query.Outcome outcome = q.outcome;
            fs.add(r -> {
                boolean asWhite = color != Query.Color.BLACK && m.test(r.white());
                boolean asBlack = color != Query.Color.WHITE && m.test(r.black());
                if (!asWhite && !asBlack) return false;
                if (outcome == null) return true;
                return (asWhite && outcomeOf(r, 0) == outcome) || (asBlack && outcomeOf(r, 1) == outcome);
            });
        }
        if (q.event != null) fs.add(contains(GameRecord::event, q.event));
        if (q.site != null) fs.add(contains(GameRecord::site, q.site));
        if (q.round != null) fs.add(r -> r.round().strip().equals(q.round) || r.round().startsWith(q.round + "."));
        if (q.opening != null) fs.add(contains(r -> r.opening() == null ? "" : r.opening(), q.opening));
        if (q.text != null) {
            Names.Matcher m = new Names.Matcher(q.text);
            fs.add(r -> m.matches(r.white() + " " + r.black() + " " + r.event() + " " + r.site() + " "
                + (r.opening() == null ? "" : r.opening())));
        }
        if (q.comment != null) fs.add(r -> r.comments() != null && r.comments().contains(q.comment));
        if (q.dateFrom > 0) fs.add(r -> r.date() >= q.dateFrom);
        if (q.dateTo > 0) fs.add(r -> r.date() > 0 && r.date() <= q.dateTo);
        if (q.results != null) fs.add(r -> q.results.contains(r.resultCode()));
        if (q.ecoFrom != null) {
            fs.add(r -> r.eco() != null && r.eco().compareTo(q.ecoFrom) >= 0 && r.eco().compareTo(q.ecoTo) <= 0);
        }
        if (q.minElo > 0 || q.maxElo > 0) fs.add(r -> eloMatches(r, q));
        if (q.minPly > 0) fs.add(r -> r.plyCount() >= q.minPly);
        if (q.maxPly > 0) fs.add(r -> r.plyCount() <= q.maxPly);
        if (q.line != null) fs.add(r -> r.startsWith(q.line) && !r.has(GameRecord.SETUP));
        if (q.material >= 0) fs.add(r -> r.reachedMaterial(q.material, !q.materialExactColors));
        if (q.maxPieces > 0) fs.add(r -> r.minPieces() <= q.maxPieces);
        if (!q.motifs.isEmpty()) fs.add(r -> motifsMatch(r, q));
        if (!q.features.isEmpty()) {
            fs.add(r -> {
                for (PawnFeature f : q.features) {
                    int mask = switch (q.featureColor) {
                        case WHITE -> f.bit(0);
                        case BLACK -> f.bit(1);
                        case ANY -> f.bit(0) | f.bit(1);
                    };
                    if ((r.pawnMask() & mask) == 0) return false;
                }
                return true;
            });
        }
        for (String l : q.labelsAll) fs.add(r -> r.hasLabel(l));
        if (!q.labelsAny.isEmpty()) fs.add(r -> q.labelsAny.stream().anyMatch(r::hasLabel));
        for (String l : q.labelsNone) fs.add(r -> !r.hasLabel(l));
        if (q.annotated != null) {
            int any = GameRecord.HAS_COMMENTS | GameRecord.HAS_VARIATIONS | GameRecord.HAS_NAGS;
            fs.add(r -> ((r.flags() & any) != 0) == q.annotated);
        }
        if (q.hasComments != null) fs.add(r -> r.has(GameRecord.HAS_COMMENTS) == q.hasComments);
        if (q.hasVariations != null) fs.add(r -> r.has(GameRecord.HAS_VARIATIONS) == q.hasVariations);
        if (q.hasEvals != null) fs.add(r -> r.has(GameRecord.HAS_EVALS) == q.hasEvals);
        if (q.chess960 != null) fs.add(r -> r.has(GameRecord.CHESS960) == q.chess960);
        for (Map.Entry<String, String> e : q.tagEquals.entrySet()) {
            String name = e.getKey(), value = e.getValue();
            fs.add(r -> {
                String v = r.tag(name);
                if (v == null) return false;
                return value.equals("*") || v.equalsIgnoreCase(value);
            });
        }
        return r -> {
            for (Predicate<GameRecord> f : fs) if (!f.test(r)) return false;
            return true;
        };
    }

    private static Predicate<GameRecord> contains(java.util.function.Function<GameRecord, String> field, String needle) {
        Names.Matcher m = new Names.Matcher(needle);
        return r -> m.matches(field.apply(r));
    }

    static Query.Outcome outcomeOf(GameRecord r, int color) {
        return switch (r.resultCode()) {
            case GameRecord.WHITE_WINS -> color == 0 ? Query.Outcome.WIN : Query.Outcome.LOSS;
            case GameRecord.BLACK_WINS -> color == 1 ? Query.Outcome.WIN : Query.Outcome.LOSS;
            case GameRecord.DRAW -> Query.Outcome.DRAW;
            default -> null;
        };
    }

    private static boolean eloMatches(GameRecord r, Query q) {
        int w = r.whiteElo(), b = r.blackElo();
        int lo = q.minElo, hi = q.maxElo > 0 ? q.maxElo : Integer.MAX_VALUE;
        return switch (q.eloMode) {
            case EITHER -> (w >= lo && w <= hi && w > 0) || (b >= lo && b <= hi && b > 0);
            case BOTH -> w >= lo && w <= hi && b >= lo && b <= hi && w > 0 && b > 0;
            case AVERAGE -> {
                if (w == 0 || b == 0) yield false;
                int avg = (w + b) / 2;
                yield avg >= lo && avg <= hi;
            }
        };
    }

    private static boolean motifsMatch(GameRecord r, Query q) {
        int needed = 0;
        for (Motif m : q.motifs) needed |= m.bit();
        if ((r.motifMask() & needed) != needed) return false;
        if (q.motifBy == Query.Color.ANY) return true;
        // The mover of ply p (1-based) is the side to move at the start for odd p.
        int firstMover = r.startPosition().sideToMove();
        int wanted = q.motifBy == Query.Color.WHITE ? 0 : 1;
        int found = 0;
        for (int[] hit : r.motifHits()) {
            int mover = (hit[0] % 2 == 1) ? firstMover : firstMover ^ 1;
            if (mover == wanted) found |= 1 << hit[1];
        }
        return (found & needed) == needed;
    }
}
