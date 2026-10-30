package com.oriondb.db;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code .oidx} sidecar: every game record serialized so a database
 * reopens without re-parsing the PGN. Only trusted when the PGN file's size
 * and modification time match what was recorded; otherwise it is rebuilt.
 * Deleting it is always safe.
 */
final class IndexCache {
    private static final long MAGIC = 0x4F52494F4E494458L; // "ORIONIDX"
    /** Bump whenever record contents or analysis (motifs, structures, ECO) change. */
    private static final int VERSION = 4;

    private IndexCache() {}

    /** Loads cached records, or null if the cache is missing, stale or unreadable. */
    static List<GameRecord> load(Path cache, long pgnSize, long pgnMtime) {
        if (!Files.isRegularFile(cache)) return null;
        try (InputStream raw = Files.newInputStream(cache);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
            if (in.readLong() != MAGIC || in.readInt() != VERSION) return null;
            if (in.readLong() != pgnSize || in.readLong() != pgnMtime) return null;
            int n = in.readInt();
            Reader r = new Reader(in);
            List<GameRecord> records = new ArrayList<>(n);
            for (int i = 0; i < n; i++) records.add(r.record());
            if (in.readLong() != MAGIC) return null;
            return records;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes the cache atomically; all records must be saved ({@link Source.FileSpan}). Failures are ignored. */
    static void write(Path cache, long pgnSize, long pgnMtime, List<GameRecord> records) {
        Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
        try {
            try (OutputStream raw = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
                out.writeLong(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(pgnSize);
                out.writeLong(pgnMtime);
                out.writeInt(records.size());
                Writer w = new Writer(out);
                for (GameRecord r : records) w.record(r);
                out.writeLong(MAGIC);
            }
            Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // the cache is optional
            }
        }
    }

    static void delete(Path cache) {
        try {
            Files.deleteIfExists(cache);
        } catch (IOException ignored) {
            // the cache is optional
        }
    }

    private static final class Writer {
        private final DataOutputStream out;
        private final Map<String, Integer> seen = new HashMap<>();

        Writer(DataOutputStream out) {
            this.out = out;
        }

        void record(GameRecord r) throws IOException {
            if (!(r.source instanceof Source.FileSpan span)) throw new IllegalStateException("unsaved record " + r.id);
            out.writeInt(r.id);
            out.writeInt(r.tags.length);
            for (String t : r.tags) str(t);
            str(r.bookEco);
            str(r.bookOpening);
            out.writeInt(r.flags);
            str(r.startFen);
            out.writeInt(r.moves.length);
            for (short m : r.moves) out.writeShort(m);
            out.writeInt(r.materials.length);
            for (long m : r.materials) out.writeLong(m);
            out.writeInt(r.motifMask);
            out.writeInt(r.motifs.length);
            for (int m : r.motifs) out.writeInt(m);
            out.writeInt(r.pawnMask);
            out.writeInt(r.labels.length);
            for (String l : r.labels) str(l);
            str(r.comments);
            str(r.error);
            out.writeLong(span.offset());
            out.writeInt(span.length());
            out.writeBoolean(span.stale());
        }

        /** Strings are written once; repeats are back-references. */
        void str(String s) throws IOException {
            if (s == null) {
                out.writeInt(-1);
                return;
            }
            Integer ref = seen.get(s);
            if (ref != null) {
                out.writeInt(ref);
                return;
            }
            seen.put(s, seen.size());
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(-2);
            out.writeInt(b.length);
            out.write(b);
        }
    }

    private static final class Reader {
        private final DataInputStream in;
        private final List<String> strings = new ArrayList<>();

        Reader(DataInputStream in) {
            this.in = in;
        }

        GameRecord record() throws IOException {
            int id = in.readInt();
            String[] tags = new String[in.readInt()];
            for (int i = 0; i < tags.length; i++) tags[i] = str();
            String bookEco = str(), bookOpening = str();
            int flags = in.readInt();
            String startFen = str();
            short[] moves = new short[in.readInt()];
            for (int i = 0; i < moves.length; i++) moves[i] = in.readShort();
            long[] materials = new long[in.readInt()];
            for (int i = 0; i < materials.length; i++) materials[i] = in.readLong();
            int motifMask = in.readInt();
            int[] motifs = new int[in.readInt()];
            for (int i = 0; i < motifs.length; i++) motifs[i] = in.readInt();
            int pawnMask = in.readInt();
            String[] labels = new String[in.readInt()];
            for (int i = 0; i < labels.length; i++) labels[i] = str();
            String comments = str(), error = str();
            Source source = new Source.FileSpan(in.readLong(), in.readInt(), in.readBoolean());
            return RecordBuilder.assemble(id, tags, bookEco, bookOpening, flags, startFen, moves, materials,
                motifMask, motifs, pawnMask, labels, comments, error, source);
        }

        String str() throws IOException {
            int k = in.readInt();
            if (k == -1) return null;
            if (k >= 0) return strings.get(k);
            byte[] b = new byte[in.readInt()];
            in.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            if (s.length() <= 64) s = s.intern();
            strings.add(s);
            return s;
        }
    }
}
