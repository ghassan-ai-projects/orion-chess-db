package com.oriondb.db;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * File operations for a PGN database: a process lock, positional reads of
 * game text, and crash-safe saves (write temp file, fsync, keep the previous
 * generation as {@code .bak}, atomic rename). Detects edits made to the file
 * by other programs since it was last read or written.
 */
final class Storage implements Closeable {
    private static final byte[] SEPARATOR = "\n\n".getBytes(StandardCharsets.US_ASCII);

    private final Path file;
    private final boolean readOnly;
    private FileChannel lockChannel;
    private FileLock lock;
    private FileChannel reader;
    private long knownSize;
    private FileTime knownMtime;

    private Storage(Path file, boolean readOnly) {
        this.file = file;
        this.readOnly = readOnly;
    }

    static Storage open(Path file, boolean readOnly, boolean create) {
        Storage s = new Storage(file.toAbsolutePath().normalize(), readOnly);
        try {
            if (!Files.exists(s.file)) {
                if (readOnly || !create) throw new DatabaseException("no such database file: " + file);
                Files.createDirectories(s.file.toAbsolutePath().getParent());
                Files.write(s.file, new byte[0], StandardOpenOption.CREATE_NEW);
            }
            if (!readOnly) s.acquireLock();
            s.refresh();
            return s;
        } catch (IOException e) {
            s.closeQuietly();
            throw new DatabaseException("cannot open " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            s.closeQuietly();
            throw e;
        }
    }

    Path file() {
        return file;
    }

    Path cacheFile() {
        return file.resolveSibling(file.getFileName() + ".oidx");
    }

    Path backupFile() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    long size() {
        return knownSize;
    }

    long mtimeMillis() {
        return knownMtime.toMillis();
    }

    private void acquireLock() throws IOException {
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            throw new DatabaseException(file + " is open for writing in another process (" + lockFile.getFileName()
                + " is locked); open it read-only or close the other program");
        }
    }

    private void refresh() throws IOException {
        if (reader != null) reader.close();
        reader = FileChannel.open(file, StandardOpenOption.READ);
        knownSize = Files.size(file);
        knownMtime = Files.getLastModifiedTime(file);
    }

    /** Fails if another program changed the file since we last read or wrote it. */
    void checkUnchanged() {
        try {
            if (!Files.exists(file)) throw new DatabaseException(file + " was deleted by another program");
            if (Files.size(file) != knownSize || !Files.getLastModifiedTime(file).equals(knownMtime)) {
                throw new DatabaseException(file + " was modified by another program since it was opened; "
                    + "reopen the database to see those changes (nothing was saved)");
            }
        } catch (IOException e) {
            throw new DatabaseException("cannot check " + file + ": " + e.getMessage(), e);
        }
    }

    synchronized byte[] read(long offset, int length) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(length);
            while (buf.hasRemaining()) {
                int n = reader.read(buf, offset + buf.position());
                if (n < 0) throw new DatabaseException("unexpected end of " + file + "; reopen the database");
            }
            return buf.array();
        } catch (IOException e) {
            throw new DatabaseException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** One game to write: raw bytes. */
    interface GameBytes {
        byte[] bytes();
    }

    /**
     * Writes all games and atomically replaces the file.
     *
     * @return the new byte span of each game, in order
     */
    synchronized List<long[]> save(List<GameBytes> games) {
        if (readOnly) throw new DatabaseException("database is open read-only");
        checkUnchanged();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        List<long[]> spans = new ArrayList<>(games.size());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
                 OutputStream out = new BufferedOutputStream(fos, 1 << 16)) {
                long pos = 0;
                for (GameBytes g : games) {
                    byte[] b = g.bytes();
                    if (pos > 0) {
                        out.write(SEPARATOR);
                        pos += SEPARATOR.length;
                    }
                    out.write(b);
                    spans.add(new long[] {pos, b.length});
                    pos += b.length;
                }
                if (pos > 0) out.write('\n');
                out.flush();
                fos.getChannel().force(true);
            }
            Path bak = backupFile();
            Files.deleteIfExists(bak);
            try {
                Files.createLink(bak, file);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            refresh();
            return spans;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new DatabaseException("saving " + file + " failed (the original is untouched): " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {
            // closing
        }
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
            if (lock != null) Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".lock"));
        } catch (IOException ignored) {
            // closing
        }
        reader = null;
        lock = null;
        lockChannel = null;
    }
}
