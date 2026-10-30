package com.oriondb.db;

/**
 * Observes a {@link Database}. Callbacks run synchronously on the thread that
 * committed or saved, after the database lock is released, so a listener may
 * read snapshots (and even start new batches). Exceptions thrown by a listener
 * are logged and do not affect the database or other listeners.
 */
public interface DatabaseListener {
    /** A batch was committed in memory (not necessarily saved yet). */
    default void committed(Change change) {}

    /** Everything up to {@code version} is now on disk. */
    default void saved(long version) {}
}
