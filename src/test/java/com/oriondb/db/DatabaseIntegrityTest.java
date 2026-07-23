package com.oriondb.db;

import com.oriondb.model.Game;
import com.oriondb.pgn.PgnReader;
import com.oriondb.search.Search;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseIntegrityTest {

    private static final String TWO_GAMES = """
        [White "Alpha"]
        [Black "Beta"]
        [Result "1-0"]

        1. e4 e5 1-0

        [White "Gamma"]
        [Black "Delta"]
        [Result "0-1"]

        1. d4 d5 0-1
        """;

    @Test
    void storedGamesAreReadOnlyAndAddDoesNotTakeOwnership() {
        Database db = Database.inMemory();
        Game input = game("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            1. e4 *
            """);

        int id = db.add(input);
        Game stored = db.game(id);

        assertEquals(-1, input.id());
        assertNull(input.tag(Database.ID_TAG));
        assertTrue(stored.isReadOnly());
        assertTrue(stored.root().isReadOnly());
        assertThrows(UnsupportedOperationException.class,
            () -> stored.setTag("Event", "corrupt"));
        assertThrows(UnsupportedOperationException.class,
            () -> stored.root().setComment("corrupt"));

        input.setTag("White", "Changed outside the database");
        assertEquals("Alpha", db.game(id).white());
    }

    @Test
    void replacePreservesIdAndRebuildsIndexes() {
        Database db = Database.inMemory();
        int id = db.add(game("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            1. e4 *
            """));
        long e4 = Search.positionAfter("e4").zobrist();
        long d4 = Search.positionAfter("d4").zobrist();
        assertEquals(1, db.positionEntries(e4).length);

        Game replacement = game("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            1. d4 *
            """);
        assertTrue(db.replace(id, replacement));

        assertEquals(id, db.game(id).id());
        assertEquals(Integer.toString(id), db.game(id).tag(Database.ID_TAG));
        assertEquals(0, db.positionEntries(e4).length);
        assertEquals(1, db.positionEntries(d4).length);
    }

    @Test
    void failedReplacementLeavesExistingGameAndIndexesUntouched() {
        Database db = Database.inMemory();
        int id = db.add(game("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            1. e4 *
            """));
        long e4 = Search.positionAfter("e4").zobrist();
        Game invalid = db.game(id).copy();
        invalid.setTag("FEN", "not a FEN");

        assertThrows(IllegalArgumentException.class, () -> db.replace(id, invalid));
        assertEquals("e4", db.game(id).mainlineSans().get(0));
        assertEquals(1, db.positionEntries(e4).length);
    }

    @Test
    void idsRemainStableAfterDeleteSaveAndReopen() throws Exception {
        Path file = Files.createTempFile("orion-stable-ids", ".pgn");
        Files.delete(file);
        try {
            int retainedId;
            int deletedId;
            try (Database db = Database.open(file)) {
                db.importPgn(TWO_GAMES);
                retainedId = db.search().player("Alpha").find().get(0).id();
                deletedId = db.search().player("Gamma").find().get(0).id();
                assertTrue(db.delete(deletedId));
                db.save();
            }

            try (Database reopened = Database.open(file)) {
                assertEquals(1, reopened.gameCount());
                assertNotNull(reopened.game(retainedId));
                assertEquals("Alpha", reopened.game(retainedId).white());
                assertEquals(Integer.toString(retainedId),
                    reopened.game(retainedId).tag(Database.ID_TAG));
                int newId = reopened.add(game("""
                    [White "Epsilon"]
                    [Black "Zeta"]
                    [Result "*"]

                    1. c4 *
                    """));
                assertNotEquals(deletedId, newId, "deleted ids must not be reused");
                reopened.setSaveOnClose(false);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void collidingImportedIdsAreReassignedAndReported() {
        Database db = Database.inMemory();
        Database.ImportReport report = db.importPgn("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]
            [OrionDBId "7"]

            1. e4 *

            [White "Gamma"]
            [Black "Delta"]
            [Result "*"]
            [OrionDBId "7"]

            1. d4 *
            """);

        assertEquals(2, report.gamesAdded());
        assertNotNull(db.game(7));
        int otherId = db.search().player("Gamma").find().get(0).id();
        assertNotEquals(7, otherId);
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("unavailable OrionDBId")));
        assertThrows(UnsupportedOperationException.class,
            () -> report.warnings().add("mutate"));
        assertEquals(report, db.lastImportReport());
    }

    @Test
    void indexAndDerivedArraysAreDefensiveCopies() {
        Database db = Database.inMemory();
        int id = db.add(game("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            1. e4 *
            """));
        long e4 = Search.positionAfter("e4").zobrist();

        long[] entries = db.positionEntries(e4);
        entries[0] = 0;
        assertEquals(id, Database.entryGameId(db.positionEntries(e4)[0]));

        long originalFinalHash = db.data(id).finalHash();
        long[] hashes = db.data(id).hashes();
        hashes[hashes.length - 1] = 0;
        assertEquals(originalFinalHash, db.data(id).finalHash());
    }

    @Test
    void saveRejectsExternalFileChanges() throws Exception {
        Path file = Files.createTempFile("orion-save-conflict", ".pgn");
        Files.writeString(file, TWO_GAMES);
        Database db = Database.open(file);
        try {
            db.setTag(db.games().iterator().next().id(), "Annotator", "Orion");
            Files.writeString(file, TWO_GAMES + "\n% external edit\n");

            Database.FileConflictException error =
                assertThrows(Database.FileConflictException.class, db::save);
            assertTrue(error.getMessage().contains("changed outside this process"));
            assertEquals(file.toAbsolutePath().normalize(), error.path());
            assertTrue(Files.readString(file).contains("% external edit"));
        } finally {
            db.setSaveOnClose(false);
            db.close();
            Files.deleteIfExists(file);
        }
    }

    @Test
    void managedIdAndInvalidTagsCannotBeChanged() {
        Database db = Database.inMemory();
        int id = db.add(game("""
            [White "Alpha"]
            [Black "Beta"]
            [Result "*"]

            *
            """));

        assertThrows(IllegalArgumentException.class,
            () -> db.setTag(id, Database.ID_TAG, "99"));
        assertThrows(IllegalArgumentException.class,
            () -> db.setTag(id, "bad tag", "value"));
        assertThrows(IllegalArgumentException.class,
            () -> db.setTag(id, "Annotator", "line one\nline two"));
    }

    private static Game game(String pgn) {
        PgnReader.ParseResult parsed = new PgnReader().readString(pgn);
        assertEquals(1, parsed.games().size(), () -> parsed.warnings().toString());
        return parsed.games().get(0);
    }
}
