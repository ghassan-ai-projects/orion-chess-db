package com.oriondb.cli;

/** A runtime failure with a message for the user (exit code 1). */
final class CliException extends RuntimeException {
    CliException(String message) {
        super(message);
    }

    CliException(String message, Throwable cause) {
        super(message, cause);
    }
}
