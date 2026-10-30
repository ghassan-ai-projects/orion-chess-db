package com.oriondb.stats;

import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.query.Names;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups of games with identical moves (and, unless relaxed, the same players). */
public final class Duplicates {

    /** A duplicate group; {@code keep} is the suggested copy to keep (the richest one). */
    public record Group(List<Integer> ids, int keep) {
        public List<Integer> redundant() {
            return ids.stream().filter(id -> id != keep).toList();
        }
    }

    private Duplicates() {}

    /**
     * @param samePlayersOnly also require matching (normalized) White and Black names
     * @param minPlies        ignore games shorter than this (short draws repeat legitimately)
     */
    public static List<Group> find(Snapshot snap, boolean samePlayersOnly, int minPlies) {
        Map<String, List<GameRecord>> groups = new LinkedHashMap<>();
        for (GameRecord r : snap.records()) {
            if (!r.isValid() || r.plyCount() < minPlies) continue;
            String key = r.movesFingerprint() + (samePlayersOnly
                ? "|" + Names.normalize(r.white()) + "|" + Names.normalize(r.black()) : "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Group> out = new ArrayList<>();
        for (List<GameRecord> g : groups.values()) {
            if (g.size() < 2) continue;
            GameRecord keep = g.stream().max(Comparator.comparingInt(Duplicates::richness)
                .thenComparing(Comparator.comparingInt(GameRecord::id).reversed())).orElseThrow();
            out.add(new Group(g.stream().map(GameRecord::id).toList(), keep.id()));
        }
        return out;
    }

    /** Annotations and metadata count toward keeping a copy. */
    private static int richness(GameRecord r) {
        int score = r.tags().size();
        if (r.has(GameRecord.HAS_COMMENTS)) score += 10;
        if (r.has(GameRecord.HAS_VARIATIONS)) score += 10;
        if (r.has(GameRecord.HAS_NAGS)) score += 5;
        if (r.has(GameRecord.HAS_EVALS)) score += 5;
        if (r.whiteElo() > 0) score++;
        if (r.date() % 100 != 0) score++;
        return score + r.labels().size();
    }
}
