package com.oriondb.db;

import com.oriondb.game.Game;
import com.oriondb.pgn.PgnException;
import com.oriondb.pgn.PgnParser;
import com.oriondb.pgn.PgnSplitter;
import com.oriondb.pgn.PgnText;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A chess database stored as a plain PGN file.
 *
 * <p>The PGN file is the only source of truth; a {@code .oidx} sidecar caches
 * the parsed game records so reopening is fast, and is rebuilt whenever the
 * PGN changes underneath it. Games that fail to parse are kept verbatim (and
 * reported) rather than dropped.
 *
 * <p>Reads go through immutable {@link Snapshot}s and never block. Changes are
 * made in {@link Batch}es that apply atomically. {@link #save()} is
 * incremental: it appends the changed games to a {@code .journal} write-ahead
 * log (fsynced), so its cost is proportional to the change, not the database.
 * The journal is folded into the PGN by {@link #compact()}, automatically when
 * it grows large, and on {@link #close()} by default, so other programs see a
 * current PGN once the application exits. Each game carries a stable id in its
 * {@code OrionId} tag.
 */
public final class Database implements AutoCloseable {

    /**
     * @param readOnly  never write the PGN, cache or lock file
     * @param create    create the file if it does not exist
     * @param useCache  read and write the {@code .oidx} sidecar
     * @param autoSave  save after every committed batch
     * @param compactOnClose fold the journal into the PGN when closing
     */
    public record Options(boolean readOnly, boolean create, boolean useCache, boolean autoSave, boolean compactOnClose) {
        public static final Options DEFAULT = new Options(false, true, true, false, true);
        public static final Options READ_ONLY = new Options(true, false, true, false, false);
        /** Writable, but the file must already exist. */
        public static final Options EXISTING = new Options(false, false, true, false, true);

        public Options(boolean readOnly, boolean create, boolean useCache, boolean autoSave) {
            this(readOnly, create, useCache, autoSave, !readOnly);
        }

        public Options withAutoSave(boolean b) {
            return new Options(readOnly, create, useCache, b, compactOnClose);
        }

        public Options withReadOnly(boolean b) {
            return new Options(b, b ? false : create, useCache, autoSave, !b && compactOnClose);
        }

        public Options withCompactOnClose(boolean b) {
            return new Options(readOnly, create, useCache, autoSave, b);
        }
    }

    /** Journal size that triggers compaction: max(this, a quarter of the PGN). */
    private static final long COMPACT_MIN_BYTES = 8L << 20;

    private record State(List<GameRecord> records, Map<Integer, GameRecord> byId, int nextId,
                         Positions positions, long version, boolean dirty, Map<String, Object> derived) {
        State(List<GameRecord> records, Map<Integer, GameRecord> byId, int nextId, Positions positions,
              long version, boolean dirty) {
            this(records, byId, nextId, positions, version, dirty, new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    private final Storage storage;     // null for in-memory databases
    private final Options options;
    private final Object writeLock = new Object();
    private volatile State state;
    private volatile boolean closed;
    private Journal journal;                          // open for appending (writable databases), or null
    private volatile boolean journalHasChanges;       // committed changes exist that the PGN lacks
    private final java.util.Set<Integer> unsaved = new java.util.HashSet<>();  // ids changed since the last save
    private final java.util.List<DatabaseListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final System.Logger LOG = System.getLogger(Database.class.getName());

    private Database(Storage storage, Options options) {
        this.storage = storage;
        this.options = options;
    }

    /** Opens (creating if needed) a PGN database with default options. */
    public static Database open(Path pgn) {
        return open(pgn, Options.DEFAULT);
    }

    public static Database open(Path pgn, Options options) {
        Storage storage = Storage.open(pgn, options.readOnly(), options.create());
        Database db = new Database(storage, options);
        try {
            db.load();
        } catch (RuntimeException e) {
            storage.close();
            throw e;
        }
        return db;
    }

    /** A database that lives only in memory ({@link #save()} is a no-op). */
    public static Database inMemory() {
        Database db = new Database(null, new Options(false, true, false, false));
        db.state = new State(List.of(), Map.of(), 1, Positions.empty(), 0, false);
        return db;
    }

    private void load() {
        List<GameRecord> records = null;
        boolean fromCache = false;
        if (options.useCache()) {
            records = IndexCache.load(storage.cacheFile(), storage.size(), storage.mtimeMillis());
            fromCache = records != null;
        }
        if (records == null) records = scan();
        Map<Integer, GameRecord> byId = new HashMap<>(records.size() * 2);
        int maxId = 0;
        for (GameRecord r : records) {
            byId.put(r.id, r);
            maxId = Math.max(maxId, r.id);
        }
        if (!fromCache && options.useCache() && !options.readOnly()) {
            IndexCache.write(storage.cacheFile(), storage.size(), storage.mtimeMillis(), records);
        }
        // Replay saved-but-not-compacted changes on top of the PGN.
        Journal.Replay replay = Journal.read(Journal.pathFor(storage.file()), storage.size(), storage.mtimeMillis());
        int nextId = maxId + 1;
        if (replay != null && !replay.entries().isEmpty()) {
            java.util.LinkedHashMap<Integer, GameRecord> merged = new java.util.LinkedHashMap<>();
            for (GameRecord r : records) merged.put(r.id, r);
            for (Journal.Entry e : replay.entries()) {
                if (e.text() == null) {
                    merged.remove(e.id());
                } else {
                    byte[] bytes = e.text().getBytes(StandardCharsets.UTF_8);
                    GameRecord r = Ingest.parse(new com.oriondb.pgn.RawGame(0, 0, bytes, 1)).record();
                    merged.put(e.id(), r.withId(e.id(), new Source.Text(e.text())));
                }
            }
            records = new ArrayList<>(merged.values());
            byId = new HashMap<>(merged);
            nextId = Math.max(nextId, replay.nextId());
            for (GameRecord r : records) nextId = Math.max(nextId, r.id + 1);
        }
        journalHasChanges = replay != null && replay.commits() > 0;
        if (replay != null && !options.readOnly()) {
            journal = Journal.open(Journal.pathFor(storage.file()), storage.size(), storage.mtimeMillis(), replay.validLength());
        }
        state = new State(List.copyOf(records), byId, nextId, Positions.build(records), 0, false);
    }

    /** Parses the whole PGN file. Ids come from OrionId tags; missing or duplicate ones get fresh ids. */
    private List<GameRecord> scan() {
        record Scanned(GameRecord record, int tagId, boolean toolTagsMatch) {}
        List<Scanned> scanned = new ArrayList<>();
        try (InputStream in = Files.newInputStream(storage.file()); PgnSplitter splitter = new PgnSplitter(in)) {
            Ingest.parseAll(splitter, p -> {
                Source span = new Source.FileSpan(p.raw().offset(), p.raw().bytes().length, false);
                boolean match = p.tagId() > 0 && RecordBuilder.toolTagsCurrent(p.tags(), p.tagId(), p.record().labels);
                scanned.add(new Scanned(p.record().withSource(span), p.tagId(), match));
            });
        } catch (IOException | UncheckedIOException e) {
            throw new DatabaseException("cannot read " + storage.file() + ": " + e.getMessage(), e);
        }
        int next = 1;
        for (Scanned s : scanned) next = Math.max(next, s.tagId() + 1);
        Set<Integer> used = new HashSet<>();
        List<GameRecord> records = new ArrayList<>(scanned.size());
        for (Scanned s : scanned) {
            boolean keep = s.tagId() > 0 && used.add(s.tagId());
            int id = keep ? s.tagId() : next++;
            Source.FileSpan span = (Source.FileSpan) s.record().source;
            boolean stale = !(keep && s.toolTagsMatch());
            records.add(s.record().withId(id, new Source.FileSpan(span.offset(), span.length(), stale)));
        }
        return records;
    }

    // ------------------------------------------------------------ reads

    /** A consistent view for searching; cheap to take. */
    public Snapshot snapshot() {
        State s = requireOpen();
        return new Snapshot(this, s.records, java.util.Collections.unmodifiableMap(s.byId), s.positions, s.version, s.derived);
    }

    public int size() {
        return requireOpen().records.size();
    }

    public GameRecord record(int id) {
        return snapshot().get(id);
    }

    public Game game(int id) {
        return parse(record(id));
    }

    public String pgn(int id) {
        return textOf(record(id));
    }

    /** Games that could not be parsed (kept verbatim in the file). */
    public List<GameRecord> invalidGames() {
        return requireOpen().records.stream().filter(r -> !r.isValid()).toList();
    }

    /** The database file, or null for an in-memory database. */
    public Path path() {
        return storage == null ? null : storage.file();
    }

    /** The disposable index cache next to the PGN ({@code <file>.oidx}), or null in memory. */
    public Path cacheFile() {
        return storage == null ? null : storage.cacheFile();
    }

    public boolean isReadOnly() {
        return options.readOnly();
    }

    /** True if there are committed changes not yet saved. */
    public boolean isDirty() {
        return requireOpen().dirty;
    }

    // ------------------------------------------------------------ writes

    /**
     * Applies changes atomically: either everything in {@code body} is
     * committed or (if it throws) nothing is. Saves afterwards when
     * auto-save is on.
     */
    public <R> R batch(Function<Batch, R> body) {
        R result;
        Change change = null;
        long savedVersion = -1;
        synchronized (writeLock) {
            State s = requireOpen();
            if (options.readOnly()) throw new DatabaseException("database is open read-only");
            Batch b = new Batch(this, s.records, s.byId, s.nextId);
            result = body.apply(b);
            if (b.hasChanges()) {
                List<GameRecord> records = List.copyOf(b.resultRecords());
                Positions positions = s.positions.apply(b.removedIds(), b.addedRecords(), records);
                state = new State(records, b.resultById(), b.nextId(), positions, s.version + 1, true);
                change = b.change(s.version + 1);
                unsaved.addAll(change.added());
                unsaved.addAll(change.updated());
                unsaved.addAll(change.deleted());
                if (options.autoSave()) {
                    try {
                        saveLocked();
                    } catch (RuntimeException e) {
                        state = s; // keep memory identical to what is on disk
                        throw e;
                    }
                    savedVersion = s.version + 1;
                }
            }
        }
        Change committed = change;
        if (committed != null && !committed.isEmpty()) notify(l -> l.committed(committed));
        if (savedVersion >= 0) {
            long v = savedVersion;
            notify(l -> l.saved(v));
        }
        return result;
    }

    /** Registers a listener for commits and saves. */
    public void addListener(DatabaseListener listener) {
        listeners.add(java.util.Objects.requireNonNull(listener));
    }

    public void removeListener(DatabaseListener listener) {
        listeners.remove(listener);
    }

    private void notify(Consumer<DatabaseListener> event) {
        for (DatabaseListener l : listeners) {
            try {
                event.accept(l);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "database listener failed", e);
            }
        }
    }

    public void update(Consumer<Batch> body) {
        batch(b -> {
            body.accept(b);
            return null;
        });
    }

    public int add(Game game) {
        return batch(b -> b.add(game));
    }

    public void replace(int id, Game game) {
        update(b -> b.replace(id, game));
    }

    public void delete(Collection<Integer> ids) {
        update(b -> ids.forEach(b::delete));
    }

    public void setTag(int id, String name, String value) {
        update(b -> b.setTag(id, name, value));
    }

    public ImportResult importFile(Path file, ImportOptions options) {
        return batch(b -> b.importFile(file, options));
    }

    public ImportResult importText(String pgn, ImportOptions options) {
        return batch(b -> b.importText(pgn, options));
    }

    /**
     * Makes all committed changes durable. Appends the changed games to the
     * journal (fsynced) when that is small; otherwise, or when the journal has
     * grown past a quarter of the PGN (min 8 MB), compacts instead. Fails
     * without writing if another program modified the PGN.
     */
    public void save() {
        long version;
        synchronized (writeLock) {
            if (!requireOpen().dirty) return;
            version = state.version;
            saveLocked();
        }
        notify(l -> l.saved(version));
    }

    /**
     * Rewrites the PGN with every change folded in (temp file, fsync, previous
     * generation kept as {@code .bak}, atomic rename), refreshes the index
     * cache and removes the journal. Unchanged games are copied byte-for-byte.
     */
    public void compact() {
        long version;
        synchronized (writeLock) {
            State s = requireOpen();
            if (options.readOnly()) throw new DatabaseException("database is open read-only");
            if (!s.dirty && !hasJournal()) return;
            version = s.version;
            compactLocked();
        }
        notify(l -> l.saved(version));
    }

    /** True if saved changes live in the journal and are not yet in the PGN itself. */
    public boolean hasJournal() {
        return journalHasChanges;
    }

    /** Caller holds the write lock. */
    private void saveLocked() {
        State s = state;
        if (!s.dirty) return;
        if (storage == null) {
            state = new State(s.records, s.byId, s.nextId, s.positions, s.version, false, s.derived);
            unsaved.clear();
            return;
        }
        if (options.readOnly()) throw new DatabaseException("database is open read-only");
        List<Journal.Entry> entries = new ArrayList<>(unsaved.size());
        long bytes = 0;
        for (int id : new java.util.TreeSet<>(unsaved)) {
            GameRecord r = s.byId.get(id);
            String text = r == null ? null : textOf(r);
            entries.add(new Journal.Entry(id, text));
            bytes += text == null ? 13 : text.length() + 13;
        }
        long current = journal == null ? 0 : journal.size();
        if (current + bytes > Math.max(COMPACT_MIN_BYTES, storage.size() / 4)) {
            compactLocked();
            return;
        }
        storage.checkUnchanged();
        if (journal == null) {
            journal = Journal.open(Journal.pathFor(storage.file()), storage.size(), storage.mtimeMillis(), 0);
        }
        journal.append(entries, s.nextId);
        journalHasChanges = true;
        unsaved.clear();
        state = new State(s.records, s.byId, s.nextId, s.positions, s.version, false, s.derived);
    }

    /** Full rewrite of the PGN; caller holds the write lock. */
    private void compactLocked() {
        State s = state;
        if (storage == null) {
            saveLocked();
            return;
        }
        List<Storage.GameBytes> out = new ArrayList<>(s.records.size());
        for (GameRecord r : s.records) out.add(() -> bytesForSave(r));
        List<long[]> spans = storage.save(out);
        List<GameRecord> saved = new ArrayList<>(s.records.size());
        Map<Integer, GameRecord> byId = new HashMap<>(s.records.size() * 2);
        for (int i = 0; i < spans.size(); i++) {
            GameRecord r = s.records.get(i).withSource(new Source.FileSpan(spans.get(i)[0], (int) spans.get(i)[1], false));
            saved.add(r);
            byId.put(r.id, r);
        }
        state = new State(List.copyOf(saved), byId, s.nextId, s.positions, s.version, false, s.derived);
        unsaved.clear();
        if (journal != null) {
            journal.delete();
            journal = null;
        } else {
            try {
                Files.deleteIfExists(Journal.pathFor(storage.file()));
            } catch (IOException e) {
                throw new DatabaseException("cannot remove the journal: " + e.getMessage(), e);
            }
        }
        journalHasChanges = false;
        if (options.useCache()) IndexCache.write(storage.cacheFile(), storage.size(), storage.mtimeMillis(), saved);
    }

    private byte[] bytesForSave(GameRecord r) {
        if (r.source instanceof Source.FileSpan span && !span.stale()) return storage.read(span.offset(), span.length());
        return textOf(r).getBytes(StandardCharsets.UTF_8);
    }

    /** Saves when auto-save is on (otherwise unsaved changes are discarded) and releases the file lock. */
    @Override
    public void close() {
        synchronized (writeLock) {
            if (closed) return;
            try {
                if (options.autoSave() && state.dirty) save();
                if (options.compactOnClose() && !options.readOnly() && hasJournal()) compactLocked();
            } finally {
                closed = true;
                if (journal != null) journal.close();
                if (storage != null) storage.close();
            }
        }
    }

    // ------------------------------------------------------------ text

    /** Current PGN text of a record, with up-to-date id and label tags. */
    String textOf(GameRecord r) {
        return switch (r.source) {
            case Source.Text t -> t.text();
            case Source.FileSpan span -> {
                String text = PgnText.decode(storage.read(span.offset(), span.length()));
                yield span.stale() ? withToolTags(text, r.id, r.labels) : text;
            }
        };
    }

    Game parse(GameRecord r) {
        if (!r.isValid()) throw new DatabaseException("game " + r.id + " is not valid PGN: " + r.error());
        try {
            return PgnParser.parseGame(textOf(r));
        } catch (PgnException e) {
            throw new DatabaseException("game " + r.id + " no longer parses: " + e.getMessage(), e);
        }
    }

    /** Sets the tool tags (id, labels) in a game's text and removes their legacy spellings. */
    static String withToolTags(String text, int id, String[] labels) {
        String t = PgnText.setTag(text, RecordBuilder.LEGACY_ID_TAG, null);
        t = PgnText.setTag(t, RecordBuilder.LEGACY_LABELS_TAG, null);
        t = PgnText.setTag(t, RecordBuilder.ID_TAG, String.valueOf(id));
        return PgnText.setTag(t, RecordBuilder.LABELS_TAG, labels.length == 0 ? null : String.join(",", labels));
    }

    private State requireOpen() {
        if (closed) throw new DatabaseException("database is closed");
        return state;
    }
}
