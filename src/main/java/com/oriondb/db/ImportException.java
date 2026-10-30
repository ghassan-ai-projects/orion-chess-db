package com.oriondb.db;

/** An atomic import found invalid games; nothing was imported. */
public class ImportException extends DatabaseException {
    private final ImportResult result;

    public ImportException(ImportResult result) {
        super(result.invalid() + " invalid game(s); nothing imported. First: " + result.errors().get(0));
        this.result = result;
    }

    public ImportResult result() {
        return result;
    }
}
