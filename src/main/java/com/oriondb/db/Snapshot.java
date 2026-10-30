package com.oriondb.db;

import com.oriondb.chess.Position;
import com.oriondb.game.Game;
import com.oriondb.index.PositionIndex;

import java.util.List;
import java.util.Map;

/**
 * A consistent, immutable view of the database at one moment. All searches
 * and statistics run against a snapshot, so they never see half-applied
 * changes and need no locking.
 */
public final class Snapshot {
    private final Database db;
    private final List<GameRecord> records;
    private final Map<Integer, GameRecord> byId;
    private final Positions positions;
    private final long version;

    Snapshot(Database db, List<GameRecord> records, Map<Integer, GameRecord> byId, Positions positions, long version) {
        this.db = db;
        this.records = records;
        this.byId = byId;
        this.positions = positions;
        this.version = version;
    }

    /** All games in file order (including invalid ones; check {@link GameRecord#isValid()}). */
    public List<GameRecord> records() {
        return records;
    }

    public int size() {
        return records.size();
    }

    /** Increases with every committed change. */
    public long version() {
        return version;
    }

    /** The record with this id, or null. */
    public GameRecord find(int id) {
        return byId.get(id);
    }

    /** The record with this id; throws if there is none. */
    public GameRecord get(int id) {
        GameRecord r = byId.get(id);
        if (r == null) throw new DatabaseException("no game with id " + id);
        return r;
    }

    /** Games reaching a position (transposition-aware), as (game id, ply) pairs. */
    public List<PositionIndex.Hit> positionHits(Position p) {
        return positions.lookup(p.hash());
    }

    public int indexedPositions() {
        return positions.indexedPositions();
    }

    /** Full PGN text of a game as stored. */
    public String pgn(int id) {
        return db.textOf(get(id));
    }

    /** The full game with variations and comments. */
    public Game game(int id) {
        return db.parse(get(id));
    }
}
