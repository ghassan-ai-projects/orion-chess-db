package com.oriondb.db;

import com.oriondb.game.Game;
import com.oriondb.pgn.PgnSplitter;
import com.oriondb.pgn.PgnText;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.pgn.RawGame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A set of changes applied atomically by {@link Database#batch}. If the
 * batch body throws, nothing is applied. Reads inside a batch see its own
 * earlier changes.
 */
public final class Batch {
    private static final Set<String> STRUCTURAL_TAGS = Set.of("FEN", "SetUp", "Variant");

    private final Database db;
    private final ArrayList<GameRecord> records;
    private final Map<Integer, GameRecord> byId;
    private final Map<Integer, GameRecord> original;
    private Map<Integer, Integer> slotOf;
    private int nextId;
    private final Set<Integer> removed = new HashSet<>();
    private final Map<Integer, GameRecord> added = new LinkedHashMap<>();
    private boolean deletions;
    private Set<String> dedupe;

    Batch(Database db, List<GameRecord> records, Map<Integer, GameRecord> byId, int nextId) {
        this.db = db;
        this.records = new ArrayList<>(records);
        this.byId = new HashMap<>(byId);
        this.original = byId;
        this.nextId = nextId;
    }

    // ------------------------------------------------------------ reads

    public GameRecord get(int id) {
        GameRecord r = byId.get(id);
        if (r == null) throw new DatabaseException("no game with id " + id);
        return r;
    }

    public boolean exists(int id) {
        return byId.containsKey(id);
    }

    public Game game(int id) {
        return db.parse(get(id));
    }

    public String pgn(int id) {
        return db.textOf(get(id));
    }

    // ------------------------------------------------------------ games

    /** Adds a game; returns its new id. */
    public int add(Game game) {
        return add(game, List.of());
    }

    public int add(Game game, Collection<String> labels) {
        int id = nextId++;
        TreeSet<String> all = new TreeSet<>(Arrays.asList(RecordBuilder.labelsFrom(game.tags().asMap())));
        all.addAll(Arrays.asList(RecordBuilder.normalizeLabels(labels)));
        String text = PgnWriter.write(game).strip();
        put(recordFromText(id, text, all.toArray(String[]::new)));
        return id;
    }

    /** Replaces a game's moves and annotations (and tags) with {@code game}; id and labels are kept. */
    public void replace(int id, Game game) {
        GameRecord old = get(id);
        String text = PgnWriter.write(game).strip();
        put(recordFromText(id, text, old.labels));
    }

    public void delete(int id) {
        get(id);
        Integer slot = slots().get(id);
        records.set(slot, null);
        byId.remove(id);
        removed.add(id);
        added.remove(id);
        deletions = true;
    }

    /**
     * Sets a PGN tag (null value removes it), editing the stored text in place.
     * FEN, SetUp and Variant change the moves' meaning: edit those with {@link #replace}.
     */
    public void setTag(int id, String name, String value) {
        if (RecordBuilder.isToolTag(name)) throw new IllegalArgumentException(name + " is managed by OrionDB; use labels");
        if (STRUCTURAL_TAGS.contains(name)) throw new IllegalArgumentException(name + " cannot be edited alone; replace the game instead");
        if (!com.oriondb.game.Tags.isValidName(name)) throw new IllegalArgumentException("invalid tag name '" + name + "'");
        GameRecord r = get(id);
        String text = PgnText.setTag(db.textOf(r), name, value);
        List<String> tags = new ArrayList<>(Arrays.asList(r.tags));
        boolean found = false;
        for (int i = 0; i < tags.size(); i += 2) {
            if (tags.get(i).equals(name)) {
                found = true;
                if (value == null) {
                    tags.remove(i);
                    tags.remove(i);
                } else {
                    tags.set(i + 1, value);
                }
                break;
            }
        }
        if (!found && value != null) {
            tags.add(name);
            tags.add(value);
        }
        put(r.withTags(tags.toArray(String[]::new), textSource(text, id, r.labels)));
    }

    // ------------------------------------------------------------ labels

    /** Adds and removes labels on one game; returns whether anything changed. */
    public boolean updateLabels(int id, Collection<String> add, Collection<String> remove) {
        GameRecord r = get(id);
        TreeSet<String> set = new TreeSet<>(Arrays.asList(r.labels));
        set.addAll(Arrays.asList(RecordBuilder.normalizeLabels(add)));
        set.removeAll(Arrays.asList(RecordBuilder.normalizeLabels(remove)));
        return setLabelsInternal(r, set.toArray(String[]::new));
    }

    /** Replaces all labels of one game; returns whether anything changed. */
    public boolean setLabels(int id, Collection<String> labels) {
        return setLabelsInternal(get(id), RecordBuilder.normalizeLabels(labels));
    }

    private boolean setLabelsInternal(GameRecord r, String[] labels) {
        if (Arrays.equals(r.labels, labels)) return false;
        put(r.withLabels(labels, textSource(db.textOf(r), r.id, labels)));
        return true;
    }

    /** Renames (or with an existing target, merges) a label everywhere; returns games changed. */
    public int renameLabel(String from, String to) {
        String f = RecordBuilder.normalizeLabel(from), t = RecordBuilder.normalizeLabel(to);
        if (f.isEmpty() || t.isEmpty()) throw new IllegalArgumentException("label names must not be empty");
        int changed = 0;
        for (GameRecord r : List.copyOf(byId.values())) {
            if (r.hasLabel(f) && updateLabels(r.id, List.of(t), List.of(f))) changed++;
        }
        return changed;
    }

    /** Removes a label from every game; returns games changed. */
    public int deleteLabel(String label) {
        String l = RecordBuilder.normalizeLabel(label);
        int changed = 0;
        for (GameRecord r : List.copyOf(byId.values())) {
            if (r.hasLabel(l) && updateLabels(r.id, List.of(), List.of(l))) changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------ import

    public ImportResult importFile(Path file, ImportOptions options) {
        try (InputStream in = Files.newInputStream(file)) {
            return importStream(in, options);
        } catch (IOException e) {
            throw new DatabaseException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public ImportResult importText(String pgn, ImportOptions options) {
        return importStream(new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8)), options);
    }

    private ImportResult importStream(InputStream in, ImportOptions options) {
        String[] extra = RecordBuilder.normalizeLabels(options.labels());
        List<Integer> ids = new ArrayList<>();
        List<ImportResult.Error> errors = new ArrayList<>();
        int[] counts = new int[3]; // read, duplicates, invalid
        try (PgnSplitter splitter = new PgnSplitter(in)) {
            Ingest.parseAll(splitter, p -> {
                counts[0]++;
                if (!p.valid()) {
                    counts[2]++;
                    if (errors.size() < ImportResult.MAX_ERRORS) errors.add(errorOf(p.raw(), p.record()));
                    return;
                }
                if (options.skipDuplicates() && !dedupeSet().add(dedupeKey(p.record()))) {
                    counts[1]++;
                    return;
                }
                if (options.atomic() && counts[2] > 0) return; // will be rejected anyway
                int id = nextId++;
                TreeSet<String> labels = new TreeSet<>(Arrays.asList(p.record().labels));
                labels.addAll(Arrays.asList(extra));
                String[] l = labels.toArray(String[]::new);
                put(p.record().withLabels(l, null).withId(id, textSource(p.text(), id, l)));
                ids.add(id);
            });
        } catch (IOException | UncheckedIOException e) {
            throw new DatabaseException("import failed: " + e.getMessage(), e);
        }
        ImportResult result = new ImportResult(counts[0], ids.size(), counts[1], ids, errors, counts[2]);
        if (options.atomic() && counts[2] > 0) throw new ImportException(result);
        return result;
    }

    private static ImportResult.Error errorOf(RawGame raw, GameRecord r) {
        return new ImportResult.Error(raw.index() + 1, raw.line(), r.white(), r.black(), r.error());
    }

    private Set<String> dedupeSet() {
        if (dedupe == null) {
            dedupe = new HashSet<>();
            for (GameRecord r : byId.values()) if (r.isValid()) dedupe.add(dedupeKey(r));
        }
        return dedupe;
    }

    static String dedupeKey(GameRecord r) {
        return r.movesFingerprint() + "|" + r.white().toLowerCase(Locale.ROOT).strip() + "|"
            + r.black().toLowerCase(Locale.ROOT).strip();
    }

    // ------------------------------------------------------------ internals

    private GameRecord recordFromText(int id, String text, String[] labels) {
        RawGame raw = new RawGame(0, 0, text.getBytes(StandardCharsets.UTF_8), 1);
        Ingest.Parsed p = Ingest.parse(raw);
        if (!p.valid()) throw new IllegalArgumentException("invalid game: " + p.record().error());
        return p.record().withLabels(labels, null).withId(id, textSource(text, id, labels));
    }

    private static Source textSource(String text, int id, String[] labels) {
        return new Source.Text(Database.withToolTags(text, id, labels));
    }

    private void put(GameRecord r) {
        GameRecord old = byId.put(r.id, r);
        if (old == null) {
            records.add(r);
            if (slotOf != null) slotOf.put(r.id, records.size() - 1);
        } else {
            records.set(slots().get(r.id), r);
            removed.add(r.id);
        }
        added.put(r.id, r);
        if (dedupe != null && r.isValid()) dedupe.add(dedupeKey(r));
    }

    private Map<Integer, Integer> slots() {
        if (slotOf == null) {
            slotOf = new HashMap<>(records.size() * 2);
            for (int i = 0; i < records.size(); i++) if (records.get(i) != null) slotOf.put(records.get(i).id, i);
        }
        return slotOf;
    }

    /** The net effect of this batch relative to the state it started from. */
    Change change(long version) {
        Set<Integer> add = new java.util.TreeSet<>(), upd = new java.util.TreeSet<>(), del = new java.util.TreeSet<>();
        for (int id : added.keySet()) (original.containsKey(id) ? upd : add).add(id);
        for (int id : removed) if (original.containsKey(id) && !byId.containsKey(id)) del.add(id);
        return new Change(version, Set.copyOf(add), Set.copyOf(upd), Set.copyOf(del));
    }

    boolean hasChanges() {
        return !removed.isEmpty() || !added.isEmpty();
    }

    List<GameRecord> resultRecords() {
        if (!deletions) return records;
        List<GameRecord> out = new ArrayList<>(records.size());
        for (GameRecord r : records) if (r != null) out.add(r);
        return out;
    }

    Map<Integer, GameRecord> resultById() {
        return byId;
    }

    int nextId() {
        return nextId;
    }

    Set<Integer> removedIds() {
        return removed;
    }

    Collection<GameRecord> addedRecords() {
        return added.values();
    }
}
