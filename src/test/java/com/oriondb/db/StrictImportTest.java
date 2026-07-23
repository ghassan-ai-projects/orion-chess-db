package com.oriondb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictImportTest {

    @Test
    void databaseRejectsWholeInvalidGamesWithStructuredErrors() {
        Database db = Database.inMemory();
        Database.ImportReport report = db.importPgn("""
            [White "Bad"]
            [Black "Move"]
            [Result "*"]

            1. e4 e5 2. Nf6 *

            [White "Good"]
            [Black "Game"]
            [Result "*"]

            1. d4 d5 *
            """);

        assertEquals(1, report.gamesAdded());
        assertEquals(1, report.gamesRejected());
        assertEquals(1, db.gameCount());
        assertEquals("Good", db.games().iterator().next().white());
        Database.ImportError error = report.errors().get(0);
        assertEquals(1, error.gameNumber());
        assertEquals("Bad", error.white());
        assertEquals("Move", error.black());
        assertEquals("ILLEGAL_MAINLINE_MOVE", error.code());
        assertThrows(UnsupportedOperationException.class,
            () -> report.errors().add(error));
    }
}
