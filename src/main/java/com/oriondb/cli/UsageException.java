package com.oriondb.cli;

/** Bad command line (exit code 2). */
final class UsageException extends RuntimeException {
    UsageException(String message) {
        super(message);
    }
}
