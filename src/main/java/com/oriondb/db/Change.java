package com.oriondb.db;

import java.util.Set;

/**
 * What one committed batch changed. Ids in {@code updated} existed before the
 * batch; a game added and deleted within the same batch appears nowhere.
 *
 * @param version the snapshot version this change produced
 */
public record Change(long version, Set<Integer> added, Set<Integer> updated, Set<Integer> deleted) {
    public boolean isEmpty() {
        return added.isEmpty() && updated.isEmpty() && deleted.isEmpty();
    }
}
