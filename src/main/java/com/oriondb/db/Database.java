package com.oriondb.db;

import com.oriondb.model.Game;
import com.oriondb.model.GameTags;
import com.oriondb.pgn.PgnReader;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.search.OpeningExplorer;
import com.oriondb.search.PlayerStats;
import com.oriondb.search.Search;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * computed once and kept. Public operations are safe across threads; query
 * helpers hold a consistent read lock while writes are serialized.
 */
public final class Database implements AutoCloseable {

    /** Portable PGN tag used to keep game ids stable across database sessions. */
    public static final String ID_TAG = "OrionDBId";
    private static final Pattern NEXT_ID_HEADER =
        Pattern.compile("(?m)^% OrionDB NextId ([1-9][0-9]*)\\s*$");

    /** Machine-readable reason an individual source game was rejected. */
    public record ImportError(
        int gameNumber,
        String white,
        String black,
        String code,
        String message
    ) {}

    /** Outcome of a PGN import. */
    public record ImportReport(
        int gamesAdded,
        int duplicatesSkipped,
        int gamesRejected,
        List<String> warnings,
        List<ImportError> errors
    ) {
        public ImportReport(int gamesAdded, int duplicatesSkipped, List<String> warnings) {
            this(gamesAdded, duplicatesSkipped, 0, warnings, List.of());
        }

        public ImportReport {
            if (gamesAdded < 0 || duplicatesSkipped < 0 || gamesRejected < 0) {
                throw new IllegalArgumentException("Import counts must not be negative");
            }
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        }
    }

    /** Outcome of setting or updating categorical tags on one or more games. */
    public record TagUpdateReport(
        int gamesUpdated,
        int gamesUnchanged,
        List<Integer> missingGameIds
    ) {
        public TagUpdateReport {
            if (gamesUpdated < 0 || gamesUnchanged < 0) {
                throw new IllegalArgumentException("Tag update counts must not be negative");
            }
            missingGameIds = List.copyOf(
                Objects.requireNonNull(missingGameIds, "missingGameIds"));
        }
    }

    /** Save/open conflict caused by another process changing the backing file. */
    public static final class FileConflictException extends IOException {
        private final Path path;

        private FileConflictException(Path path, String message) {
            super(message + ": " + path);
            this.path = path;
        }

        public Path path() {
            return path;
        }
    }

    private final Path path;                                  // null for in-memory
    private final LinkedHashMap<Integer, Game> games = new LinkedHashMap<>();
    private final Map<Integer, GameData> data = new HashMap<>();
    /** zobrist -> packed (gameId << 16 | ply) entries, first occurrence per game. */
    private final Map<Long, EntryList> positionIndex = new HashMap<>();
    /** Move-sequence hash -> candidate ids for exact duplicate checks. */
    private final Map<Long, List<Integer>> duplicateIndex = new HashMap<>();
    private int nextId = 1;
    private boolean dirty;
    private boolean saveOnClose = true;
    private ImportReport lastImportReport =
        new ImportReport(0, 0, 0, List.of(), List.of());
    /** SHA-256 of the file state loaded or last saved; null means it did not exist. */
    private String persistedFingerprint;

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
        Path normalized = Objects.requireNonNull(pgnPath, "pgnPath")
            .toAbsolutePath().normalize();
        Database db = new Database(normalized);
        if (Files.exists(normalized)) {
            String beforeRead = fingerprint(normalized);
            String pgn = PgnReader.readFileText(normalized);
            String afterRead = fingerprint(normalized);
            if (!Objects.equals(beforeRead, afterRead)) {
                throw new FileConflictException(
                    normalized, "Database changed while it was being opened");
            }
            db.importPgn(pgn);
            int persistedNextId = readNextId(pgn);
            if (persistedNextId > db.nextId) {
                db.nextId = persistedNextId;
            }
            db.dirty = false; // freshly loaded state matches the file
            db.persistedFingerprint = afterRead;
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

    public synchronized int gameCount() {
        return games.size();
    }

    /** All read-only games in insertion order, as a stable collection snapshot. */
    public synchronized Collection<Game> games() {
        return List.copyOf(games.values());
    }

    /** A read-only stored game, or null when the id is unknown. */
    public synchronized Game game(int id) {
        return games.get(id);
    }

    /** Derived data for a game; null if the id is unknown. */
    public synchronized GameData data(int id) {
        return data.get(id);
    }

    /** Report from the most recent import, including the import performed by open. */
    public synchronized ImportReport lastImportReport() {
        return lastImportReport;
    }

    /** Distinct categorical tags and the number of games carrying each one. */
    public synchronized Map<String, Integer> gameTagCounts() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Game game : games.values()) {
            for (String tag : game.gameTags()) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /**
     * Packed (gameId &lt;&lt; 16 | ply) entries for every game containing this
     * position on its mainline (first occurrence per game). Never null.
     */
    public synchronized long[] positionEntries(long zobrist) {
        EntryList entries = positionIndex.get(zobrist);
        return entries != null ? entries.toArray() : new long[0];
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
    public synchronized int add(Game game) {
        Game stored = Objects.requireNonNull(game, "game").copy();
        return add(stored, GameData.build(stored), null);
    }

    private int add(Game game, GameData gameData, List<String> warnings) {
        int id = allocateId(game.tag(ID_TAG), warnings);
        game.setId(id);
        game.setTag(ID_TAG, Integer.toString(id));
        autoFillOpeningTags(game, gameData);
        game.makeReadOnly();
        games.put(id, game);
        data.put(id, gameData);
        indexPositions(id, gameData);
        indexDuplicate(id, gameData);
        dirty = true;
        return id;
    }

    /**
     * Import games from PGN text. Exact duplicates (same players and same
     * moves) of games already in the database are skipped. Derived data is
     * computed on all cores.
     */
    public synchronized ImportReport importPgn(String pgnText) {
        PgnReader.ParseResult parsed =
            new PgnReader(PgnReader.ValidationMode.STRICT).readString(pgnText);
        List<String> warnings = new ArrayList<>(parsed.warnings());
        List<ImportError> errors = new ArrayList<>();
        for (PgnReader.RejectedGame rejected : parsed.rejectedGames()) {
            String white = rejected.tags().getOrDefault("White", "?");
            String black = rejected.tags().getOrDefault("Black", "?");
            for (PgnReader.ParseError error : rejected.errors()) {
                errors.add(new ImportError(
                    rejected.gameNumber(), white, black, error.code(), error.message()));
            }
        }

        // The replay/analysis work is independent per game: parallelize it,
        // preserving encounter order, then insert sequentially.
        record Built(int gameNumber, Game game, GameData data, String error) {}
        List<Built> built = parsed.parsedGames().parallelStream()
            .map(parsedGame -> {
                Game game = parsedGame.game();
                try {
                    return new Built(
                        parsedGame.gameNumber(), game, GameData.build(game), null);
                } catch (IllegalArgumentException e) {
                    return new Built(parsedGame.gameNumber(), game, null, e.getMessage());
                }
            })
            .toList();

        int added = 0;
        int duplicates = 0;
        int rejected = parsed.rejectedGames().size();
        for (Built b : built) {
            if (b.error() != null) {
                rejected++;
                String message = "game " + b.gameNumber() + ": skipped unreplayable game ("
                    + b.game() + "): " + b.error();
                warnings.add(message);
                errors.add(new ImportError(
                    b.gameNumber(), b.game().white(), b.game().black(),
                    "REPLAY_FAILED", b.error()));
                continue;
            }
            if (isDuplicate(b.game())) {
                duplicates++;
                continue;
            }
            add(b.game().copy(), b.data(), warnings);
            added++;
        }
        lastImportReport = new ImportReport(
            added, duplicates, rejected, warnings, errors);
        return lastImportReport;
    }

    public synchronized ImportReport importFile(Path pgnFile) throws IOException {
        return importPgn(PgnReader.readFileText(pgnFile));
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
        for (int id : duplicateIndex.getOrDefault(seqHash, List.of())) {
            Game existing = games.get(id);
            if (existing.white().equalsIgnoreCase(candidate.white())
                    && existing.black().equalsIgnoreCase(candidate.black())
                    && existing.mainlineSans().equals(candidate.mainlineSans())) {
                return true;
            }
        }
        return false;
    }

    private int allocateId(String requested, List<String> warnings) {
        if (requested != null) {
            try {
                int id = Integer.parseInt(requested);
                if (id > 0 && !games.containsKey(id)) {
                    if (id >= nextId && id < Integer.MAX_VALUE) {
                        nextId = id + 1;
                    }
                    return id;
                }
                warnId(warnings, "ignored unavailable " + ID_TAG + " '" + requested + "'");
            } catch (NumberFormatException e) {
                warnId(warnings, "ignored invalid " + ID_TAG + " '" + requested + "'");
            }
        }
        if (nextId <= 0) nextId = 1;
        int firstCandidate = nextId;
        do {
            int candidate = nextId;
            nextId = nextId == Integer.MAX_VALUE ? 1 : nextId + 1;
            if (!games.containsKey(candidate)) {
                return candidate;
            }
        } while (nextId != firstCandidate);
        throw new IllegalStateException("No game ids are available");
    }

    private static void warnId(List<String> warnings, String warning) {
        if (warnings != null) warnings.add(warning);
    }

    private void indexDuplicate(int gameId, GameData gameData) {
        duplicateIndex.computeIfAbsent(gameData.moveSeqHash, ignored -> new ArrayList<>())
            .add(gameId);
    }

    private void unindexDuplicate(int gameId, GameData gameData) {
        List<Integer> ids = duplicateIndex.get(gameData.moveSeqHash);
        if (ids == null) return;
        ids.remove(Integer.valueOf(gameId));
        if (ids.isEmpty()) {
            duplicateIndex.remove(gameData.moveSeqHash);
        }
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
    public synchronized boolean delete(int id) {
        Game removed = games.remove(id);
        if (removed == null) return false;
        GameData removedData = data.remove(id);
        unindexPositions(id, removedData);
        unindexDuplicate(id, removedData);
        dirty = true;
        return true;
    }

    /**
     * Atomically replace a game while preserving its database id.
     *
     * The replacement is defensively copied, validated, re-indexed, and stored
     * read-only. If validation fails, the existing game is left untouched.
     *
     * @return true if the game exists
     */
    public synchronized boolean replace(int id, Game replacement) {
        Objects.requireNonNull(replacement, "replacement");
        Game current = games.get(id);
        if (current == null) return false;

        Game stored = replacement.copy();
        stored.setId(id);
        stored.setTag(ID_TAG, Integer.toString(id));
        GameData replacementData = GameData.build(stored);
        autoFillOpeningTags(stored, replacementData);
        stored.makeReadOnly();

        GameData currentData = data.get(id);
        unindexPositions(id, currentData);
        unindexDuplicate(id, currentData);
        games.put(id, stored);
        data.put(id, replacementData);
        indexPositions(id, replacementData);
        indexDuplicate(id, replacementData);
        dirty = true;
        return true;
    }

    /**
     * Set (or with null, remove) a tag on a game.
     *
     * @return true if the game exists
     */
    public synchronized boolean setTag(int id, String key, String value) {
        Game game = games.get(id);
        if (game == null) return false;
        if (ID_TAG.equalsIgnoreCase(key)) {
            throw new IllegalArgumentException(ID_TAG + " is managed by the database");
        }
        if (GameTags.PGN_TAG.equalsIgnoreCase(key)) {
            key = GameTags.PGN_TAG;
            value = GameTags.format(GameTags.parse(value));
        }
        Game updated = game.copy();
        updated.setTag(key, value);
        if (Objects.equals(game.tag(key), value)) return true;
        if ("FEN".equals(key)) {
            return replace(id, updated);
        }
        updated.setId(id);
        updated.setTag(ID_TAG, Integer.toString(id));
        updated.makeReadOnly();
        games.put(id, updated);
        dirty = true;
        return true;
    }

    /** Replace all categorical tags on one game. */
    public synchronized boolean setGameTags(int id, Collection<String> tags) {
        TagUpdateReport report = setGameTags(List.of(id), tags);
        return report.missingGameIds().isEmpty();
    }

    /** Convenience overload for replacing all categorical tags on one game. */
    public synchronized boolean setGameTags(int id, String... tags) {
        return setGameTags(id, List.of(tags));
    }

    /** Add categorical tags to one game. */
    public synchronized boolean addGameTags(int id, String... tags) {
        return updateGameTags(id, List.of(tags), List.of());
    }

    /** Remove categorical tags from one game. */
    public synchronized boolean removeGameTags(int id, String... tags) {
        return updateGameTags(id, List.of(), List.of(tags));
    }

    /** Add categorical tags to multiple games. */
    public synchronized TagUpdateReport addGameTags(
            Collection<Integer> ids, Collection<String> tags) {
        return updateGameTags(ids, tags, List.of());
    }

    /** Remove categorical tags from multiple games. */
    public synchronized TagUpdateReport removeGameTags(
            Collection<Integer> ids, Collection<String> tags) {
        return updateGameTags(ids, List.of(), tags);
    }

    /** Replace all categorical tags on multiple games. */
    public synchronized TagUpdateReport setGameTags(
            Collection<Integer> ids, Collection<String> tags) {
        Set<String> replacement = GameTags.normalizeAll(tags);
        return changeGameTags(ids, replacement, Set.of(), Set.of());
    }

    /**
     * Add and remove categorical tags on one game. Additions win when a tag
     * appears in both collections.
     */
    public synchronized boolean updateGameTags(
            int id, Collection<String> add, Collection<String> remove) {
        TagUpdateReport report = updateGameTags(List.of(id), add, remove);
        return report.missingGameIds().isEmpty();
    }

    /**
     * Add and remove categorical tags on multiple games in one serialized
     * operation. Missing ids are reported and do not block valid games.
     */
    public synchronized TagUpdateReport updateGameTags(
            Collection<Integer> ids, Collection<String> add, Collection<String> remove) {
        return changeGameTags(
            ids, null, GameTags.normalizeAll(add), GameTags.normalizeAll(remove));
    }

    private TagUpdateReport changeGameTags(
            Collection<Integer> ids,
            Set<String> replacement,
            Set<String> add,
            Set<String> remove) {
        Objects.requireNonNull(ids, "ids");
        Set<Integer> uniqueIds = new java.util.LinkedHashSet<>();
        for (Integer id : ids) {
            uniqueIds.add(Objects.requireNonNull(id, "game id"));
        }

        int updated = 0;
        int unchanged = 0;
        List<Integer> missing = new ArrayList<>();
        for (int id : uniqueIds) {
            Game game = games.get(id);
            if (game == null) {
                missing.add(id);
                continue;
            }
            Set<String> next = new HashSet<>(
                replacement != null ? replacement : game.gameTags());
            if (replacement == null) {
                next.removeAll(remove);
                next.addAll(add);
            }
            if (next.equals(game.gameTags())) {
                unchanged++;
                continue;
            }
            setTag(id, GameTags.PGN_TAG, GameTags.format(next));
            updated++;
        }
        return new TagUpdateReport(updated, unchanged, missing);
    }

    // ------------------------------------------------------------------
    // Position index maintenance
    // ------------------------------------------------------------------

    private void indexPositions(int gameId, GameData gameData) {
        long[] hashes = gameData.hashes;
        Set<Long> seen = new HashSet<>(hashes.length * 2);
        for (int ply = 0; ply < hashes.length; ply++) {
            long hash = hashes[ply];
            if (seen.add(hash)) {
                long packed = ((long) gameId << 16) | ply;
                positionIndex.computeIfAbsent(hash, ignored -> new EntryList()).add(packed);
            }
        }
    }

    private void unindexPositions(int gameId, GameData gameData) {
        Set<Long> seen = new HashSet<>(gameData.hashes.length * 2);
        for (long hash : gameData.hashes) {
            if (!seen.add(hash)) continue;
            EntryList entries = positionIndex.get(hash);
            if (entries == null) continue;
            entries.removeGame(gameId);
            if (entries.isEmpty()) {
                positionIndex.remove(hash);
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Whether {@link #close()} saves a dirty database (default true). */
    public synchronized void setSaveOnClose(boolean saveOnClose) {
        this.saveOnClose = saveOnClose;
    }

    /**
     * Atomically rewrite the database file (write to a temp file in the
     * same directory, then move into place).
     */
    public synchronized void save() throws IOException {
        if (path == null) {
            throw new IllegalStateException("In-memory database: use saveAs(path)");
        }
        String currentFingerprint = fingerprint(path);
        if (!Objects.equals(persistedFingerprint, currentFingerprint)) {
            throw new FileConflictException(
                path, "Database file changed outside this process; reopen before saving");
        }
        writeAtomically(path);
        persistedFingerprint = fingerprint(path);
        dirty = false;
    }

    public synchronized void saveAs(Path target) throws IOException {
        Path normalized = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (path != null && path.equals(normalized)) {
            save();
            return;
        }
        writeAtomically(normalized);
    }

    private void writeAtomically(Path target) throws IOException {
        String pgn = "% OrionDB NextId " + nextId + "\n"
            + new PgnWriter().write(new ArrayList<>(games.values()));
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, ".orion-", ".pgn.tmp");
        try {
            Files.writeString(temp, pgn, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
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

    private static int readNextId(String pgn) {
        Matcher matcher = NEXT_ID_HEADER.matcher(pgn);
        if (!matcher.find()) return 1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (saveOnClose && dirty && path != null) {
            save();
        }
    }

    private static String fingerprint(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Small primitive append list; avoids copying popular position buckets on every import. */
    private static final class EntryList {
        private long[] values = new long[4];
        private int size;

        void add(long value) {
            if (size == values.length) {
                long[] grown = new long[values.length * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        void removeGame(int gameId) {
            int write = 0;
            for (int read = 0; read < size; read++) {
                if (entryGameId(values[read]) != gameId) {
                    values[write++] = values[read];
                }
            }
            size = write;
        }

        boolean isEmpty() {
            return size == 0;
        }

        long[] toArray() {
            long[] copy = new long[size];
            System.arraycopy(values, 0, copy, 0, size);
            return copy;
        }
    }
}
