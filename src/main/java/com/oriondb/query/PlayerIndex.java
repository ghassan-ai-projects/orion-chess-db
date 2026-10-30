package com.oriondb.query;

import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Players of a database, with spelling variants grouped into one identity:
 * "Carlsen,M", "Carlsen, Magnus" and "Carlsen,Magnus" are one player, while
 * "Carlsen,H" (a different initial) and "Kasparov,Georgy" vs "Kasparov, Garry"
 * (different given names) stay apart. Abbreviated variants ("Kasparov,G") join
 * the most frequent full-name identity with the same surname and initial.
 *
 * <p>Heuristic, like every name-unification scheme without a player
 * encyclopedia; applications can layer their own alias table on top.
 * Computed once per snapshot version.
 */
public final class PlayerIndex {

    /** One spelling as it appears in PGN tags. */
    public record Variant(String name, int games) {}

    /**
     * @param id        stable key for this identity within the database ("carlsen|m|mag")
     * @param name      display name (the most common full spelling)
     * @param variants  spellings, most frequent first
     */
    public record Player(String id, String name, List<Variant> variants, int games, int firstYear, int lastYear,
                         int peakElo, int latestElo) {
        public Set<String> spellings() {
            return Set.copyOf(variants.stream().map(Variant::name).toList());
        }
    }

    private final List<Player> players;
    private final Map<String, Player> bySpelling;
    private final Map<String, Player> byId;

    private PlayerIndex(List<Player> players) {
        this.players = players;
        this.bySpelling = new HashMap<>();
        this.byId = new HashMap<>();
        for (Player p : players) {
            byId.put(p.id(), p);
            for (Variant v : p.variants()) bySpelling.put(v.name(), p);
        }
    }

    /** The index for a snapshot (memoized per data version). */
    public static PlayerIndex of(Snapshot snap) {
        return snap.derived("oriondb.players", () -> build(snap));
    }

    private static final class Acc {
        int games, firstYear, lastYear, peak, latest, latestDate = -1;

        void add(GameRecord r, int elo) {
            games++;
            if (r.year() > 0) {
                firstYear = firstYear == 0 ? r.year() : Math.min(firstYear, r.year());
                lastYear = Math.max(lastYear, r.year());
            }
            if (elo > 0) {
                peak = Math.max(peak, elo);
                if (r.date() >= latestDate) {
                    latestDate = r.date();
                    latest = elo;
                }
            }
        }

        void merge(Acc o) {
            games += o.games;
            if (o.firstYear > 0) firstYear = firstYear == 0 ? o.firstYear : Math.min(firstYear, o.firstYear);
            lastYear = Math.max(lastYear, o.lastYear);
            peak = Math.max(peak, o.peak);
            if (o.latestDate > latestDate) {
                latestDate = o.latestDate;
                latest = o.latest;
            }
        }
    }

    private static PlayerIndex build(Snapshot snap) {
        Map<String, Acc> spellings = new LinkedHashMap<>();
        for (GameRecord r : snap.records()) {
            if (!r.isValid()) continue;
            if (!r.white().isBlank()) spellings.computeIfAbsent(r.white(), k -> new Acc()).add(r, r.whiteElo());
            if (!r.black().isBlank()) spellings.computeIfAbsent(r.black(), k -> new Acc()).add(r, r.blackElo());
        }
        // Group spellings: surname + initial, then split by distinct full given names.
        Map<String, Map<String, List<String>>> groups = new LinkedHashMap<>();
        for (String s : spellings.keySet()) {
            Names.PersonName p = Names.PersonName.parse(s);
            String given = p.firstGiven();
            String key = p.surnameText() + "|" + (given.isEmpty() ? "" : given.substring(0, 1));
            String full = p.abbreviated() ? "" : given.substring(0, Math.min(3, given.length()));
            groups.computeIfAbsent(key, k -> new LinkedHashMap<>()).computeIfAbsent(full, k -> new ArrayList<>()).add(s);
        }
        List<Player> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> g : groups.entrySet()) {
            Map<String, List<String>> byGiven = g.getValue();
            List<String> abbreviated = byGiven.remove("");
            if (abbreviated != null) {
                String target = byGiven.entrySet().stream()
                    .max(Comparator.comparingInt(e -> gamesOf(e.getValue(), spellings)))
                    .map(Map.Entry::getKey).orElse("");
                byGiven.computeIfAbsent(target, k -> new ArrayList<>()).addAll(abbreviated);
            }
            for (Map.Entry<String, List<String>> identity : byGiven.entrySet()) {
                out.add(player(g.getKey() + "|" + identity.getKey(), identity.getValue(), spellings));
            }
        }
        out.sort(Comparator.comparingInt(Player::games).reversed().thenComparing(Player::name));
        return new PlayerIndex(List.copyOf(out));
    }

    private static int gamesOf(List<String> names, Map<String, Acc> spellings) {
        return names.stream().mapToInt(n -> spellings.get(n).games).sum();
    }

    private static Player player(String id, List<String> names, Map<String, Acc> spellings) {
        Acc total = new Acc();
        List<Variant> variants = new ArrayList<>();
        for (String n : names) {
            total.merge(spellings.get(n));
            variants.add(new Variant(n, spellings.get(n).games));
        }
        variants.sort(Comparator.comparingInt(Variant::games).reversed().thenComparing(Variant::name));
        // Display: the most frequent spelling with a full given name, else the most frequent one.
        String display = variants.stream().map(Variant::name)
            .filter(n -> !Names.PersonName.parse(n).abbreviated()).findFirst().orElse(variants.get(0).name());
        return new Player(id, display, List.copyOf(variants), total.games, total.firstYear, total.lastYear,
            total.peak, total.latest);
    }

    /** All players, most games first. */
    public List<Player> all() {
        return players;
    }

    public int size() {
        return players.size();
    }

    /** The identity a PGN spelling belongs to, or null. */
    public Player forSpelling(String name) {
        return bySpelling.get(name);
    }

    public Player byId(String id) {
        return byId.get(id);
    }

    /**
     * Players matching a name query, most games first (good for autocomplete).
     * An identity with a full-name spelling matches only through its full
     * spellings, so "kasparov georgy" does not pick up Garry via "Kasparov,G".
     */
    public List<Player> search(String query, int limit) {
        Names.Matcher m = Names.Matcher.person(query);
        return players.stream().filter(p -> {
            boolean hasFull = p.variants().stream().anyMatch(v -> !Names.PersonName.parse(v.name()).abbreviated());
            return p.variants().stream().anyMatch(v ->
                (!hasFull || !Names.PersonName.parse(v.name()).abbreviated()) && m.matches(v.name()));
        }).limit(limit).toList();
    }
}
