package com.oriondb.db;

import com.oriondb.index.PositionIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Position lookup = an immutable {@link PositionIndex} plus a small overlay of
 * games changed since it was built (scanned directly). The index is rebuilt
 * once the overlay grows past a threshold.
 */
final class Positions {
    private final PositionIndex base;
    private final Set<Integer> excluded;
    private final Map<Integer, GameRecord> overlay;

    private Positions(PositionIndex base, Set<Integer> excluded, Map<Integer, GameRecord> overlay) {
        this.base = base;
        this.excluded = excluded;
        this.overlay = overlay;
    }

    static Positions build(List<GameRecord> records) {
        return new Positions(PositionIndex.build(records), Set.of(), Map.of());
    }

    static Positions empty() {
        return new Positions(PositionIndex.empty(), Set.of(), Map.of());
    }

    /** Applies removals and additions (a replaced game appears in both). */
    Positions apply(Collection<Integer> removedIds, Collection<GameRecord> added, List<GameRecord> allRecords) {
        if (removedIds.isEmpty() && added.isEmpty()) return this;
        Map<Integer, GameRecord> nextOverlay = new LinkedHashMap<>(overlay);
        Set<Integer> nextExcluded = new HashSet<>(excluded);
        for (int id : removedIds) {
            nextOverlay.remove(id);
            nextExcluded.add(id);
        }
        for (GameRecord r : added) if (r.isValid()) nextOverlay.put(r.id(), r);
        if (nextOverlay.size() > 256 + allRecords.size() / 20) return build(allRecords);
        return new Positions(base, nextExcluded, nextOverlay);
    }

    List<PositionIndex.Hit> lookup(long hash) {
        List<PositionIndex.Hit> hits = new ArrayList<>(excluded.isEmpty()
            ? base.lookup(hash, id -> true)
            : base.lookup(hash, id -> !excluded.contains(id)));
        for (GameRecord r : overlay.values()) hits.addAll(PositionIndex.scan(r, hash));
        return hits;
    }

    int indexedPositions() {
        return base.size();
    }
}
