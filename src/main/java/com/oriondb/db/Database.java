package com.oriondb.db;

import com.oriondb.model.Game;
import com.oriondb.pgn.PgnReader;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.search.OpeningExplorer;
import com.oriondb.search.PlayerStats;
import com.oriondb.search.Search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An OrionDB chess database.
 *
 * The database file is plain PGN — readable by every chess tool in
 * existence. All games and derived index data live in memory (the engine
 * targets collections of thousands of games, not millions), searches run
 * over in-memory structures, and {@link #save()} atomically rewrites the
 * PGN file.
 *
 * Every game is validated on the way in: moves are replayed against the
 * legal move generator, SAN is normalized, and derived data (position
 * hashes, material keys, motifs, pawn features, ECO classification) is
 * computed once and kept.
 */
public final class Database implements AutoCloseable {

    /** Outcome of a PGN import. */
    public record ImportReport(int gamesAdded, int duplicatesSkipped, List<String> warnings) {}

    private final Path path;                                  // null for in-memory
    private final LinkedHashMap<Integer, Game> games = new LinkedHashMap<>();
    private final Map<Integer, GameData> data = new HashMap<>();
    /** zobrist -> packed (gameId << 16 | ply) entries, first occurrence per game. */
    private final Map<Long, long[]> positionIndex = new HashMap<>();
    private int nextId = 1;
    private boolean dirty;
    private boolean saveOnClose = true;

    private Database(Path path) {
        this.path = path;
    }

    // ------------------------------------------------------------------
    // Opening and creating
    // ------------------------------------------------------------------

    /**
     * Open a database file (plain PGN), creating an empty database if the
     * file does not exist yet. Unreadable games are skipped with warnings
     * available via the returned database's last import report.
     */
    public static Database open(Path pgnPath) throws IOException {
        Database db = new Database(pgnPath);
        if (Files.exists(pgnPath)) {
            String text = Files.readString(pgnPath, StandardCharsets.UTF_8);
            db.importPgn(text);
            db.dirty = false; // freshly loaded state matches the file
        }
        return db;
    }

    /** A database that lives only in memory ({@link #save()} requires saveAs). */
    public static Database inMemory() {
        return new Database(null);
    }

    public Path path() {
        return path;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public int gameCount() {
        return games.size();
    }

    /** All games in insertion order (unmodifiable view). */
    public Collection<Game> games() {
        return Collections.unmodifiableCollection(games.values());
    }

    public Game game(int id) {
        return games.get(id);
    }

    /** Derived data for a game; null if the id is unknown. */
    public GameData data(int id) {
        return data.get(id);
    }

    /**
     * Packed (gameId &lt;&lt; 16 | ply) entries for every game containing this
     * position on its mainline (first occurrence per game). Never null.
     */
    public long[] positionEntries(long zobrist) {
        long[] entries = positionIndex.get(zobrist);
        return entries != null ? entries : new long[0];
    }

    public static int entryGameId(long entry) {
        return (int) (entry >>> 16);
    }

    public static int entryPly(long entry) {
        return (int) (entry & 0xFFFF);
    }

    // ------------------------------------------------------------------
    // Query entry points
    // ------------------------------------------------------------------

    /** Start a fluent search. */
    public Search search() {
        return new Search(this);
    }

    /** The opening explorer for this database. */
    public OpeningExplorer explorer() {
        return new OpeningExplorer(this);
    }

    /** Player dossiers and head-to-head reports. */
    public PlayerStats players() {
        return new PlayerStats(this);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Add a validated game. The game's moves are replayed; illegal games
     * are rejected.
     *
     * @return the assigned game id
     * @throws IllegalArgumentException if the game's mainline does not replay
     */
    public int add(Game game) {
        GameData gameData = GameData.build(game);
        int id = nextId++;
        game.setId(id);
        autoFillOpeningTags(game, gameData);
        games.put(id, game);
        data.put(id, gameData);
        indexPositions(id, gameData);
        dirty = true;
        return id;
    }

    /**
     * Import games from PGN text. Exact duplicates (same players and same
     * moves) of games already in the database are skipped.
     */
    public ImportReport importPgn(String pgnText) {
        PgnReader.ParseResult parsed = new PgnReader().readString(pgnText);
        List<String> warnings = new ArrayList<>(parsed.warnings());
        int added = 0;
        int duplicates = 0;
        for (Game game : parsed.games()) {
            if (isDuplicate(game)) {
                duplicates++;
                continue;
            }
            try {
                add(game);
                added++;
            } catch (IllegalArgumentException e) {
                warnings.add("skipped unreplayable game (" + game + "): " + e.getMessage());
            }
        }
        return new ImportReport(added, duplicates, warnings);
    }

    public ImportReport importFile(Path pgnFile) throws IOException {
        return importPgn(Files.readString(pgnFile, StandardCharsets.UTF_8));
    }

    /**
     * An import candidate is a duplicate when an existing game has the same
     * move sequence and the same players.
     */
    private boolean isDuplicate(Game candidate) {
        long seqHash = GameData.moveSequenceHash(candidate.mainlineSans());
        if (candidate.plyCount() == 0) {
            return false; // never dedupe empty games
        }
        for (Map.Entry<Integer, GameData> entry : data.entrySet()) {
            if (entry.getValue().moveSeqHash() != seqHash) continue;
            Game existing = games.get(entry.getKey());
            if (existing.white().equalsIgnoreCase(candidate.white())
                    && existing.black().equalsIgnoreCase(candidate.black())
                    && existing.mainlineSans().equals(candidate.mainlineSans())) {
                return true;
            }
        }
        return false;
    }

    /** Fill in missing ECO/Opening tags from the book classification. */
    private static void autoFillOpeningTags(Game game, GameData gameData) {
        if (gameData.eco() != null && game.tag("ECO") == null) {
            game.setTag("ECO", gameData.eco());
        }
        if (gameData.openingName() != null && game.tag("Opening") == null) {
            game.setTag("Opening", gameData.openingName());
        }
    }

    /** Delete a game. Returns true if it existed. */
    public boolean delete(int id) {
        Game removed = games.remove(id);
        if (removed == null) return false;
        GameData removedData = data.remove(id);
        unindexPositions(id, removedData);
        dirty = true;
        return true;
    }

    /**
     * Set (or with null, remove) a tag on a game.
     *
     * @return true if the game exists
     */
    public boolean setTag(int id, String key, String value) {
        Game game = games.get(id);
        if (game == null) return false;
        game.setTag(key, value);
        dirty = true;
        return true;
    }

    // ------------------------------------------------------------------
    // Position index maintenance
    // ------------------------------------------------------------------

    private void indexPositions(int gameId, GameData gameData) {
        long[] hashes = gameData.hashes();
        for (int ply = 0; ply < hashes.length; ply++) {
            long hash = hashes[ply];
            if (firstOccurrenceInGame(hashes, ply)) {
                long packed = ((long) gameId << 16) | ply;
                positionIndex.merge(hash, new long[]{packed}, Database::concat);
            }
        }
    }

    private static boolean firstOccurrenceInGame(long[] hashes, int ply) {
        for (int i = 0; i < ply; i++) {
            if (hashes[i] == hashes[ply]) return false;
        }
        return true;
    }

    private static long[] concat(long[] a, long[] b) {
        long[] merged = new long[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    private void unindexPositions(int gameId, GameData gameData) {
        for (long hash : gameData.hashes()) {
            long[] entries = positionIndex.get(hash);
            if (entries == null) continue;
            int keep = 0;
            for (long entry : entries) {
                if (entryGameId(entry) != gameId) keep++;
            }
            if (keep == 0) {
                positionIndex.remove(hash);
            } else if (keep != entries.length) {
                long[] filtered = new long[keep];
                int i = 0;
                for (long entry : entries) {
                    if (entryGameId(entry) != gameId) filtered[i++] = entry;
                }
                positionIndex.put(hash, filtered);
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public boolean isDirty() {
        return dirty;
    }

    /** Whether {@link #close()} saves a dirty database (default true). */
    public void setSaveOnClose(boolean saveOnClose) {
        this.saveOnClose = saveOnClose;
    }

    /**
     * Atomically rewrite the database file (write to a temp file in the
     * same directory, then move into place).
     */
    public void save() throws IOException {
        if (path == null) {
            throw new IllegalStateException("In-memory database: use saveAs(path)");
        }
        saveAs(path);
        dirty = false;
    }

    public void saveAs(Path target) throws IOException {
        String pgn = new PgnWriter().write(new ArrayList<>(games.values()));
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, ".orion-", ".pgn.tmp");
        try {
            Files.writeString(temp, pgn, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void close() throws IOException {
        if (saveOnClose && dirty && path != null) {
            save();
        }
    }
}
