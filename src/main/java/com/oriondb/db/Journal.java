package com.oriondb.db;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The {@code <file>.pgn.journal} write-ahead log: saves append the games that
 * changed (full PGN text, or a deletion) instead of rewriting the whole PGN.
 *
 * <p>Layout: an 8-byte magic, version, and the size and mtime of the PGN the
 * journal applies to; then frames {@code [int length][byte type][payload][int crc]}.
 * PUT and DELETE frames become visible only when followed by a COMMIT frame,
 * so a save is atomic. A torn or corrupt tail (crash mid-write) is ignored and
 * truncated by the next writer.
 */
final class Journal implements Closeable {
    private static final long MAGIC = 0x4F52494F4E4A4E4CL; // "ORIONJNL"
    private static final int VERSION = 1;
    private static final int HEADER = 8 + 4 + 8 + 8;
    private static final byte PUT = 1, DELETE = 2, COMMIT = 3;

    /** One change: {@code text == null} deletes the game. */
    record Entry(int id, String text) {}

    /** Committed changes in order, the id counter at the last commit, and the valid byte length. */
    record Replay(List<Entry> entries, int nextId, long validLength, int commits) {}

    private final Path file;
    private final FileChannel channel;
    private long size;

    private Journal(Path file, FileChannel channel, long size) {
        this.file = file;
        this.channel = channel;
        this.size = size;
    }

    static Path pathFor(Path pgn) {
        return pgn.resolveSibling(pgn.getFileName() + ".journal");
    }

    /**
     * Reads committed changes, or returns null if there is no journal.
     *
     * @throws DatabaseException if the journal belongs to a different version of the PGN
     */
    static Replay read(Path file, long pgnSize, long pgnMtime) {
        if (!Files.isRegularFile(file)) return null;
        List<Entry> committed = new ArrayList<>(), pending = new ArrayList<>();
        int nextId = 0, commits = 0;
        long valid = 0;
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
            if (Files.size(file) < HEADER) return new Replay(List.of(), 0, 0, 0);
            if (in.readLong() != MAGIC || in.readInt() != VERSION) {
                throw new DatabaseException(file + " is not an OrionDB journal; move it away to open the database");
            }
            long size = in.readLong(), mtime = in.readLong();
            if (size != pgnSize || mtime != pgnMtime) {
                throw new DatabaseException(file.getFileName() + " holds saved changes for a different version of "
                    + file.getFileName().toString().replace(".journal", "") + " (the PGN was modified by another program). "
                    + "Move the journal away to open the file as it is now; its changes would be lost.");
            }
            valid = HEADER;
            long pos = HEADER;
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                if (length < 1 || length > (1 << 28)) break;
                byte[] body = new byte[length];
                try {
                    in.readFully(body);
                    int crc = in.readInt();
                    CRC32 c = new CRC32();
                    c.update(body);
                    if ((int) c.getValue() != crc) break;
                } catch (EOFException e) {
                    break;
                }
                pos += 4 + length + 4;
                ByteBuffer b = ByteBuffer.wrap(body);
                byte type = b.get();
                switch (type) {
                    case PUT -> {
                        int id = b.getInt();
                        pending.add(new Entry(id, new String(body, 5, length - 5, StandardCharsets.UTF_8)));
                    }
                    case DELETE -> pending.add(new Entry(b.getInt(), null));
                    case COMMIT -> {
                        nextId = b.getInt();
                        committed.addAll(pending);
                        pending.clear();
                        commits++;
                        valid = pos;
                    }
                    default -> { // unknown frame: treat as corruption
                        return new Replay(committed, nextId, valid, commits);
                    }
                }
            }
        } catch (IOException e) {
            throw new DatabaseException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return new Replay(committed, nextId, valid, commits);
    }

    /** Opens (creating if needed) for appending; anything past {@code validLength} is truncated. */
    static Journal open(Path file, long pgnSize, long pgnMtime, long validLength) {
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (validLength < HEADER) {
                ch.truncate(0);
                ByteBuffer h = ByteBuffer.allocate(HEADER).putLong(MAGIC).putInt(VERSION).putLong(pgnSize).putLong(pgnMtime).flip();
                while (h.hasRemaining()) ch.write(h, HEADER - h.remaining());
                ch.force(true);
                validLength = HEADER;
            } else {
                ch.truncate(validLength);
            }
            return new Journal(file, ch, validLength);
        } catch (IOException e) {
            throw new DatabaseException("cannot open " + file + ": " + e.getMessage(), e);
        }
    }

    /** Appends one atomic group of changes and fsyncs. */
    void append(List<Entry> entries, int nextId) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (Entry e : entries) {
                if (e.text() == null) {
                    frame(out, ByteBuffer.allocate(5).put(DELETE).putInt(e.id()).array());
                } else {
                    byte[] text = e.text().getBytes(StandardCharsets.UTF_8);
                    frame(out, ByteBuffer.allocate(5 + text.length).put(PUT).putInt(e.id()).put(text).array());
                }
            }
            frame(out, ByteBuffer.allocate(5).put(COMMIT).putInt(nextId).array());
            ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
            long at = size;
            while (buf.hasRemaining()) at += channel.write(buf, at);
            channel.force(false);
            size = at;
        } catch (IOException e) {
            // A partial write is ignored on replay (no COMMIT); later appends overwrite it.
            throw new DatabaseException("writing " + file + " failed: " + e.getMessage(), e);
        }
    }

    private static void frame(java.io.ByteArrayOutputStream out, byte[] body) {
        CRC32 c = new CRC32();
        c.update(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) c.getValue()).array());
    }

    long size() {
        return size;
    }

    /** Closes and removes the journal (after its changes were compacted into the PGN). */
    void delete() {
        close();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new DatabaseException("cannot remove " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
