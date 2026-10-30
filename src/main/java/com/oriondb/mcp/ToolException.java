package com.oriondb.mcp;

/** A tool call that cannot be carried out: reported to the client as an {@code isError} result. */
final class ToolException extends RuntimeException {
    ToolException(String message) {
        super(message);
    }
}
