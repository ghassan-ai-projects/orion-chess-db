package com.oriondb.query;

import com.oriondb.analysis.Material;
import com.oriondb.analysis.Motif;
import com.oriondb.analysis.PawnFeature;
import com.oriondb.chess.Position;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.index.PositionIndex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Executes a {@link Query} against a {@link Snapshot}. Header and analysis
 * filters run as a parallel scan over records, positions go through the
 * index, and replay-based criteria (piece patterns, moves, sequences) run
 * only on games that survived the cheaper filters. Boolean sub-queries are
 * evaluated the same way, recursively.
 */
public final class Searcher {
    private Searcher() {}

    public static SearchResult search(Snapshot snap, Query q) {
        Map<Integer, Integer> byPosition = q.position == null ? null : positionPlies(snap, q);
        Predicate<GameRecord> filter = compile(snap, q);
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

        boolean replay = q.pattern != null || !q.moves.isEmpty() || q.sequence != null;
        List<SearchResult.Match> matches = new ArrayList<>(candidates.parallelStream().map(r -> {
            int ply = SearchResult.Match.NONE;
            if (byPosition != null) {
                int p = byPosition.get(r.id());
                ply = p == PositionIndex.Hit.VARIATION ? SearchResult.Match.IN_VARIATION : p;
            }
            if (replay) {
                int p = replayPly(r, q);
                if (p < 0) return null;
                if (ply == SearchResult.Match.NONE) ply = p;
            }
            return new SearchResult.Match(r, ply);
        }).filter(m -> m != null).toList());

        List<Keyed> sorted = new ArrayList<>(matches.size());
        for (SearchResult.Match m : matches) sorted.add(new Keyed(sortKey(q.sort, m.record()), m));
        sorted.sort((a, b) -> compareKeys(q, a.key, a.match.record().id(), b.key, b.match.record().id()));
        int total = sorted.size();
        int from = q.after != null ? afterCursor(sorted, q) : Math.min(q.offset, total);
        int to = (int) Math.min((long) from + q.limit, total);
        List<SearchResult.Match> page = sorted.subList(from, to).stream().map(Keyed::match).toList();
        String next = to < total && !page.isEmpty() ? cursor(q, page.get(page.size() - 1).record()) : null;
        return new SearchResult(total, page, snap.version(), next);
    }

    /** Ids of every match, ignoring pagination. */
    public static List<Integer> ids(Snapshot snap, Query q) {
        int offset = q.offset, limit = q.limit;
        String after = q.after;
        q.all().after(null);
        try {
            return search(snap, q).matches().stream().map(m -> m.record().id()).toList();
        } finally {
            q.page(offset, limit).after(after);
        }
    }

    /**
     * The full predicate of a query (all criteria, including positions,
     * replay criteria and sub-queries). Useful for filtering other views,
     * e.g. the opening explorer.
     */
    public static Predicate<GameRecord> predicate(Snapshot snap, Query q) {
        Predicate<GameRecord> base = compile(snap, q);
        if (q.position != null) {
            java.util.Set<Integer> ids = positionPlies(snap, q).keySet();
            Predicate<GameRecord> b = base;
            base = r -> ids.contains(r.id()) && b.test(r);
        }
        if (q.pattern != null || !q.moves.isEmpty() || q.sequence != null) {
            Predicate<GameRecord> b = base;
            base = r -> b.test(r) && replayPly(r, q) >= 0;
        }
        return base;
    }

    private static Map<Integer, Integer> positionPlies(Snapshot snap, Query q) {
        Map<Integer, Integer> plies = new HashMap<>();
        for (PositionIndex.Hit h : snap.positionHits(q.position)) {
            if (!h.inMainline() && !q.positionInVariations) continue;
            // Prefer the first mainline ply; -1 only when the position occurs solely in variations.
            plies.merge(h.gameId(), h.ply(), (a, b) -> a < 0 ? b : b < 0 ? a : Math.min(a, b));
        }
        return plies;
    }

    /**
     * Replays the mainline once and checks the piece pattern, required moves and
     * the move sequence. Returns the ply of the first match (pattern position,
     * else the first required move), or -1 if any criterion fails.
     */
    private static int replayPly(GameRecord r, Query q) {
        int n = r.plyCount();
        Position p = r.startPosition();
        int patternPly = q.pattern == null ? 0 : q.pattern.matches(p) ? 0 : -1;
        boolean[] moveSeen = new boolean[q.moves.size()];
        int firstMovePly = -1, seqPly = -1;
        List<MovePattern> seq = q.sequence;
        // Rolling window of the last positions for sequence checks.
        Position[] before = seq == null ? null : new Position[n];
        for (int i = 0; i < n; i++) {
            int move = r.move(i);
            Position next = p.play(move);
            if (patternPly < 0 && q.pattern.matches(next)) patternPly = i + 1;
            if (moveAllowed(q, p)) {
                for (int k = 0; k < moveSeen.length; k++) {
                    if (!moveSeen[k] && q.moves.get(k).matches(p, move, next)) {
                        moveSeen[k] = true;
                        if (firstMovePly < 0) firstMovePly = i + 1;
                    }
                }
            }
            if (before != null) {
                before[i] = p;
                int start = i - seq.size() + 1;
                if (seqPly < 0 && start >= 0 && moveAllowed(q, before[start])
                        && sequenceAt(r, before, start, next, seq)) {
                    seqPly = start + 1;
                }
            }
            p = next;
        }
        if (patternPly < 0) return -1;
        for (boolean seen : moveSeen) if (!seen) return -1;
        if (seq != null && seqPly < 0) return -1;
        if (q.pattern != null) return patternPly;
        if (!q.moves.isEmpty()) return firstMovePly;
        return seqPly;
    }

    private static boolean sequenceAt(GameRecord r, Position[] before, int start, Position last, List<MovePattern> seq) {
        for (int k = 0; k < seq.size(); k++) {
            int i = start + k;
            Position after = i + 1 < start + seq.size() ? before[i + 1] : last;
            if (!seq.get(k).matches(before[i], r.move(i), after)) return false;
        }
        return true;
    }

    /** Side and move-number restrictions for move:/sequence: (applied to the first move of a sequence). */
    private static boolean moveAllowed(Query q, Position before) {
        if (q.moveBy != Query.Color.ANY && before.sideToMove() != (q.moveBy == Query.Color.WHITE ? 0 : 1)) return false;
        int number = before.fullmoveNumber();
        if (q.moveFrom > 0 && number < q.moveFrom) return false;
        return q.moveTo <= 0 || number <= q.moveTo;
    }

    // ------------------------------------------------------------ ordering and cursors

    /** Sort key of a record: Long or lowercase String. */
    private static Comparable<?> sortKey(Query.Sort sort, GameRecord r) {
        return switch (sort) {
            case ID -> (long) r.id();
            case DATE -> (long) r.date();
            case WHITE -> r.white().toLowerCase(Locale.ROOT);
            case BLACK -> r.black().toLowerCase(Locale.ROOT);
            case EVENT -> r.event().toLowerCase(Locale.ROOT);
            case ELO -> (long) averageElo(r);
            case WHITE_ELO -> (long) r.whiteElo();
            case BLACK_ELO -> (long) r.blackElo();
            case PLY -> (long) r.plyCount();
            case ECO -> r.eco() == null ? "~" : r.eco();
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Query q, Comparable a, int idA, Comparable b, int idB) {
        int c = a.compareTo(b);
        if (q.descending) c = -c;
        return c != 0 ? c : Integer.compare(idA, idB);
    }

    /** A match with its precomputed sort key. */
    private record Keyed(Comparable<?> key, SearchResult.Match match) {}

    /** Cursor = sort, direction, key and id of the last row, base64url-encoded. */
    private static String cursor(Query q, GameRecord last) {
        Comparable<?> key = sortKey(q.sort, last);
        String raw = q.sort + "|" + q.descending + "|" + (key instanceof Long ? "L" : "S") + key + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int afterCursor(List<Keyed> sorted, Query q) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(q.after), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cursor");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals(q.sort.name()) || !parts[1].equals(String.valueOf(q.descending))) {
            throw new IllegalArgumentException("cursor does not belong to this sort order");
        }
        Comparable<?> key = parts[2].startsWith("L") ? (Comparable<?>) Long.parseLong(parts[2].substring(1)) : parts[2].substring(1);
        int id = Integer.parseInt(parts[3]);
        int lo = 0, hi = sorted.size();
        while (lo < hi) { // first row strictly after the cursor
            int mid = (lo + hi) >>> 1;
            Keyed k = sorted.get(mid);
            if (compareKeys(q, k.key, k.match.record().id(), key, id) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    static int averageElo(GameRecord r) {
        int w = r.whiteElo(), b = r.blackElo();
        if (w > 0 && b > 0) return (w + b) / 2;
        return Math.max(w, b);
    }

    // ------------------------------------------------------------ filters

    /**
     * Name predicate used by player filters, reports and head-to-head: a query
     * matches every spelling of every player identity it matches (so
     * "kasparov garry" also finds "Kasparov, Gary" and "Kasparov,G").
     */
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
        if (q.balanceMin != null || q.balanceMax != null) {
            int lo = q.balanceMin == null ? Integer.MIN_VALUE : q.balanceMin;
            int hi = q.balanceMax == null ? Integer.MAX_VALUE : q.balanceMax;
            fs.add(r -> {
                for (long m : r.materials()) {
                    int b = Material.balance(m);
                    if (b >= lo && b <= hi) return true;
                }
                return false;
            });
        }
        // Boolean sub-queries last: they may replay games.
        for (Query sub : q.allOf) fs.add(predicate(snap, sub));
        if (!q.anyOf.isEmpty()) {
            List<Predicate<GameRecord>> any = q.anyOf.stream().map(sub -> predicate(snap, sub)).toList();
            fs.add(r -> {
                for (Predicate<GameRecord> f : any) if (f.test(r)) return true;
                return false;
            });
        }
        for (Query sub : q.noneOf) fs.add(predicate(snap, sub).negate());
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
