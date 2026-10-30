package com.oriondb.engine;

import java.io.IOException;

/** A UCI engine failed: it could not be started, timed out, died or broke the protocol. */
public class EngineException extends IOException {
    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
