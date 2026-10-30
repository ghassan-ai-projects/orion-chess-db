package com.oriondb.db;

import java.util.List;

/**
 * Outcome of an import.
 *
 * @param read       games found in the source
 * @param added      games added
 * @param duplicates games skipped as duplicates
 * @param ids        ids of added games, in source order
 * @param errors     games rejected as invalid (at most {@link #MAX_ERRORS} listed)
 * @param invalid    total number of invalid games
 */
public record ImportResult(int read, int added, int duplicates, List<Integer> ids, List<Error> errors, int invalid) {
    public static final int MAX_ERRORS = 200;

    /** A rejected game: 1-based index in the source, first line, players, and the reason. */
    public record Error(int game, long line, String white, String black, String message) {
        @Override
        public String toString() {
            return "game " + game + " (line " + line + ", " + white + " - " + black + "): " + message;
        }
    }
}
