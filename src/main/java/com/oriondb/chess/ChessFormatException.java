package com.oriondb.chess;

/** Malformed FEN, unparseable or illegal move notation. */
public class ChessFormatException extends IllegalArgumentException {
    public ChessFormatException(String message) {
        super(message);
    }
}
