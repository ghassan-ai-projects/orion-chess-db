package com.oriondb.mcp;

import com.oriondb.db.Batch;
import com.oriondb.db.Database;
import com.oriondb.db.Snapshot;
import com.oriondb.engine.EngineConfig;
import com.oriondb.engine.EngineException;
import com.oriondb.engine.UciEngine;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/** Shared state of one server session: the database and a lazily started engine. */
final class Context implements AutoCloseable {
    private final Database db;
    private UciEngine engine;

    Context(Database db) {
        this.db = db;
    }

    Database db() {
        return db;
    }

    Snapshot snap() {
        return db.snapshot();
    }

    /**
     * Runs {@code body} in one atomic batch. A failure inside the body leaves
     * the database unchanged; a failure after it (saving) is reported as such.
     */
    <R> R mutate(Function<Batch, R> body) {
        if (db.isReadOnly()) throw new ToolException("the database is open read-only; changes are not allowed");
        boolean[] applied = {false};
        try {
            return db.batch(b -> {
                R r = body.apply(b);
                applied[0] = true;
                return r;
            });
        } catch (RuntimeException e) {
            if (applied[0]) {
                throw new ToolException("the change was applied in memory but saving to disk failed: " + Tools.message(e));
            }
            throw e;
        }
    }

    /** The engine, started on first use and restarted if it died. */
    synchronized UciEngine engine() throws EngineException {
        if (engine != null && engine.isAlive()) return engine;
        if (engine != null) engine.close();
        engine = null;
        Optional<EngineConfig> config = EngineConfig.detect();
        if (config.isEmpty()) {
            throw new ToolException("no UCI chess engine found. Install Stockfish (e.g. 'brew install stockfish' or "
                + "'apt install stockfish') or set the " + EngineConfig.ENV
                + " environment variable to the engine executable's path, then restart the MCP server.");
        }
        engine = new UciEngine(config.get());
        engine.setAnalysisTimeout(Duration.ofSeconds(60));
        return engine;
    }

    @Override
    public synchronized void close() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
