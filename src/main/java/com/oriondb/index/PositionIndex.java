package com.oriondb.index;

import com.oriondb.chess.Position;
import com.oriondb.db.GameRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * Transposition-aware position index over game mainlines: sorted parallel
 * arrays of (position hash, game id, ply), queried by binary search.
 * About 14 bytes per indexed position; immutable once built.
 */
public final class PositionIndex {
    /** A game reaching the position after {@code ply} half-moves. */
    public record Hit(int gameId, int ply) {}

    private static final PositionIndex EMPTY = new PositionIndex(new long[0], new int[0], new short[0]);

    private final long[] hashes;
    private final int[] ids;
    private final short[] plies;

    private PositionIndex(long[] hashes, int[] ids, short[] plies) {
        this.hashes = hashes;
        this.ids = ids;
        this.plies = plies;
    }

    public static PositionIndex empty() {
        return EMPTY;
    }

    /** Indexes every position (start included) of each valid game's mainline. Runs in parallel. */
    public static PositionIndex build(List<GameRecord> records) {
        List<GameRecord> valid = records.stream().filter(GameRecord::isValid).toList();
        int[] offsets = new int[valid.size() + 1];
        for (int i = 0; i < valid.size(); i++) offsets[i + 1] = offsets[i] + Math.min(valid.get(i).plyCount(), Short.MAX_VALUE - 1) + 1;
        int total = offsets[valid.size()];
        long[] h = new long[total];
        int[] id = new int[total];
        short[] ply = new short[total];
        IntStream.range(0, valid.size()).parallel().forEach(i -> {
            GameRecord r = valid.get(i);
            int at = offsets[i];
            int n = offsets[i + 1] - at;
            Position p = r.startPosition();
            for (int k = 0; k < n; k++) {
                if (k > 0) p = p.play(r.move(k - 1));
                h[at + k] = p.hash();
                id[at + k] = r.id();
                ply[at + k] = (short) k;
            }
        });
        sort(h, id, ply);
        return new PositionIndex(h, id, ply);
    }

    /** Hashes positions of one game's mainline (used for games not yet in an index). */
    public static List<Hit> scan(GameRecord r, long hash) {
        List<Hit> hits = new ArrayList<>(1);
        Position p = r.startPosition();
        if (p.hash() == hash) hits.add(new Hit(r.id(), 0));
        for (int k = 0; k < r.plyCount(); k++) {
            p = p.play(r.move(k));
            if (p.hash() == hash) hits.add(new Hit(r.id(), k + 1));
        }
        return hits;
    }

    public int size() {
        return hashes.length;
    }

    /** All (game, ply) pairs reaching the position; {@code include} filters game ids. */
    public List<Hit> lookup(long hash, IntPredicate include) {
        List<Hit> out = new ArrayList<>();
        for (int i = lowerBound(hash); i < hashes.length && hashes[i] == hash; i++) {
            if (include.test(ids[i])) out.add(new Hit(ids[i], plies[i]));
        }
        return out;
    }

    /** Number of index entries for a position (counts repetitions within a game). */
    public int count(long hash) {
        int lo = lowerBound(hash), hi = lo;
        while (hi < hashes.length && hashes[hi] == hash) hi++;
        return hi - lo;
    }

    private int lowerBound(long hash) {
        int lo = 0, hi = hashes.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(hashes[mid], hash) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** LSD radix sort on unsigned hash, 16 bits per pass, carrying ids and plies. */
    private static void sort(long[] h, int[] id, short[] ply) {
        int n = h.length;
        long[] h2 = new long[n];
        int[] id2 = new int[n];
        short[] ply2 = new short[n];
        int[] count = new int[1 << 16];
        for (int shift = 0; shift < 64; shift += 16) {
            java.util.Arrays.fill(count, 0);
            for (long v : h) count[(int) ((v >>> shift) & 0xFFFF)]++;
            int sum = 0;
            for (int b = 0; b < count.length; b++) {
                int c = count[b];
                count[b] = sum;
                sum += c;
            }
            for (int i = 0; i < n; i++) {
                int dst = count[(int) ((h[i] >>> shift) & 0xFFFF)]++;
                h2[dst] = h[i];
                id2[dst] = id[i];
                ply2[dst] = ply[i];
            }
            long[] th = h; h = h2; h2 = th;
            int[] ti = id; id = id2; id2 = ti;
            short[] tp = ply; ply = ply2; ply2 = tp;
        }
        // Four passes: the sorted data is back in the original arrays.
    }
}
