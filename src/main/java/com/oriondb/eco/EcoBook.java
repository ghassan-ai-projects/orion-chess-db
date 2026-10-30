package com.oriondb.eco;

import com.oriondb.chess.Position;
import com.oriondb.chess.San;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * ECO classification from the embedded lichess chess-openings book (CC0,
 * ~3,800 named lines). Keyed by position hash, so classification is
 * transposition-aware: a game gets the deepest book position its mainline
 * reaches.
 */
public final class EcoBook {
    /** A named opening; {@code ply} is the book line's length. */
    public record Opening(String eco, String name, int ply) {}

    private static final class Holder {
        static final EcoBook INSTANCE = new EcoBook();
    }

    private final Map<Long, Opening> byHash = new HashMap<>(8192);

    private EcoBook() {
        InputStream in = EcoBook.class.getResourceAsStream("openings.tsv");
        if (in == null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.readLine(); // header
            String line;
            while ((line = r.readLine()) != null) {
                String[] cols = line.split("\t");
                if (cols.length < 3) continue;
                Position p = Position.start();
                int ply = 0;
                for (String tok : cols[2].split("\\s+")) {
                    if (tok.isEmpty() || Character.isDigit(tok.charAt(0))) continue;
                    p = p.play(San.parse(p, tok));
                    ply++;
                }
                Opening o = new Opening(cols[0], cols[1], ply);
                Opening prev = byHash.get(p.hash());
                if (prev == null || ply < prev.ply()) byHash.put(p.hash(), o);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static EcoBook get() {
        return Holder.INSTANCE;
    }

    /** The book opening at exactly this position, or null. */
    public Opening lookup(Position p) {
        return byHash.get(p.hash());
    }

    /** Deepest book opening along a line of positions (index = ply), or null. */
    public Opening classify(Position[] line) {
        Opening best = null;
        int misses = 0;
        for (int i = 1; i < line.length; i++) {
            Opening o = byHash.get(line[i].hash());
            if (o != null) {
                best = o;
                misses = 0;
            } else if (best != null && ++misses > 12) {
                break; // left book long ago
            }
            if (i > 60) break;
        }
        return best;
    }

    public int size() {
        return byHash.size();
    }
}
