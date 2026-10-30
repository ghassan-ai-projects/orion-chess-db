package com.oriondb.db;

/** A database operation failed: locked file, conflicting external edit, unknown game id, I/O failure. */
public class DatabaseException extends RuntimeException {
    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
