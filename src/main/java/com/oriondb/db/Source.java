package com.oriondb.db;

/** Where a game's PGN text lives: a byte range of the database file, or in memory (unsaved). */
public sealed interface Source {
    /**
     * A byte range in the database file.
     *
     * @param stale whether the stored text lacks the current tool tags
     *              (id/labels) and must be rewritten on the next save
     */
    record FileSpan(long offset, int length, boolean stale) implements Source {}

    /** Text not yet written to disk. */
    record Text(String text) implements Source {}
}
