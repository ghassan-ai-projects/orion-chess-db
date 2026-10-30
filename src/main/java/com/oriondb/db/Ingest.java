package com.oriondb.db;

import com.oriondb.game.Game;
import com.oriondb.pgn.PgnException;
import com.oriondb.pgn.PgnParser;
import com.oriondb.pgn.PgnText;
import com.oriondb.pgn.RawGame;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Parses raw games into records in parallel chunks, preserving order. */
final class Ingest {
    private static final int CHUNK = 2048;

    /**
     * One parsed game. {@code record} has id 0 and no source yet.
     *
     * @param tagId id stored in the game's tool tag, or -1
     */
    record Parsed(RawGame raw, String text, Map<String, String> tags, GameRecord record, int tagId) {
        boolean valid() {
            return record.isValid();
        }
    }

    private Ingest() {}

    static Parsed parse(RawGame raw) {
        String text = raw.text();
        try {
            PgnParser.Result r = PgnParser.parse(text);
            Game g = r.game();
            Map<String, String> tags = g.tags().asMap();
            return new Parsed(raw, text, tags, RecordBuilder.build(0, g, null), RecordBuilder.idFrom(tags));
        } catch (PgnException | RuntimeException e) {
            Map<String, String> tags = PgnText.readTags(text);
            String msg = e instanceof PgnException ? e.getMessage() : "internal error: " + e;
            return new Parsed(raw, text, tags, RecordBuilder.invalid(0, tags, msg, null), RecordBuilder.idFrom(tags));
        }
    }

    /** Parses every game from the iterator, handing results to {@code sink} in source order. */
    static void parseAll(Iterator<RawGame> games, Consumer<Parsed> sink) {
        java.util.ArrayList<RawGame> chunk = new java.util.ArrayList<>(CHUNK);
        while (games.hasNext()) {
            chunk.add(games.next());
            if (chunk.size() == CHUNK) {
                flush(chunk, sink);
                chunk.clear();
            }
        }
        flush(chunk, sink);
    }

    private static void flush(List<RawGame> chunk, Consumer<Parsed> sink) {
        if (chunk.isEmpty()) return;
        List<Parsed> parsed = chunk.parallelStream().map(Ingest::parse).toList();
        parsed.forEach(sink);
    }
}
