package com.oriondb.db;

import com.oriondb.model.Game;
import com.oriondb.pgn.PgnReader;
import com.oriondb.search.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTransactionTest {

    private static final String TWO_GAMES = """
        [White "Alpha"]
        [Black "Beta"]
        [Result "*"]

        1. e4 e5 *

        [White "Gamma"]
        [Black "Delta"]
        [Result "*"]

        1. d4 d5 *
        """;

    @TempDir
    Path tempDir;

    @Test
    void failureRollsBackGamesIdsMetadataAndIndexes() {
        Database db = Database.inMemory();
        db.importPgn(TWO_GAMES);
        int alpha = db.search().player("Alpha").find().get(0).id();
        int gamma = db.search().player("Gamma").find().get(0).id();
        long e4 = Search.positionAfter("e4").zobrist();
        int[] allocated = new int[1];

        assertThrows(IntentionalFailure.class, () -> db.transaction(tx -> {
            assertTrue(tx.delete(alpha));
            assertTrue(tx.setTag(gamma, "Annotator", "rolled back"));
            assertTrue(tx.addGameTags(gamma, "rolled-back-tag"));
            allocated[0] = tx.add(game("""
                [White "Temporary"]
                [Black "Game"]
                [Result "*"]

                1. c4 *
                """));
            throw new IntentionalFailure();
        }));

        assertEquals(2, db.gameCount());
        assertEquals("Alpha", db.game(alpha).white());
        assertNull(db.game(gamma).tag("Annotator"));
        assertEquals(0, db.search().tag("rolled-back-tag").count());
        assertEquals(1, db.positionEntries(e4).length);
        assertEquals(allocated[0], db.add(game("""
            [White "Permanent"]
            [Black "Game"]
            [Result "*"]

            1. c4 *
            """)), "rolled-back ids must be available again");
    }

    @Test
    void successfulTransactionCommitsAllChanges() {
        Database db = Database.inMemory();
        db.importPgn(TWO_GAMES);
        int alpha = db.search().player("Alpha").find().get(0).id();
        int gamma = db.search().player("Gamma").find().get(0).id();

        int remaining = db.transaction(tx -> {
            tx.setTag(alpha, "Annotator", "Orion");
            tx.addGameTags(alpha, "study");
            tx.delete(gamma);
            return tx.gameCount();
        });

        assertEquals(1, remaining);
        assertEquals("Orion", db.game(alpha).tag("Annotator"));
        assertTrue(db.game(alpha).hasGameTag("study"));
        assertNull(db.game(gamma));
    }

    @Test
    void failedPersistentTransactionLeavesMemoryAndFileUnchanged() throws Exception {
        Path file = tempDir.resolve("transaction.pgn");
        Files.writeString(file, TWO_GAMES);

        try (Database db = Database.open(file)) {
            int alpha = db.search().player("Alpha").find().get(0).id();
            String before = Files.readString(file);

            assertThrows(IntentionalFailure.class, () -> db.transactionAndSave(tx -> {
                tx.setTag(alpha, "Annotator", "must not persist");
                throw new IntentionalFailure();
            }));

            assertNull(db.game(alpha).tag("Annotator"));
            assertEquals(before, Files.readString(file));
        }
    }

    @Test
    void successfulPersistentTransactionSavesOnce() throws Exception {
        Path file = tempDir.resolve("committed.pgn");
        Files.writeString(file, TWO_GAMES);
        String original = Files.readString(file);
        int alpha;

        try (Database db = Database.open(file)) {
            alpha = db.search().player("Alpha").find().get(0).id();
            int gamma = db.search().player("Gamma").find().get(0).id();
            db.transactionAndSave(tx -> {
                tx.setTag(alpha, "Annotator", "committed");
                tx.addGameTags(alpha, "study");
                tx.delete(gamma);
                return null;
            });
            assertEquals(original, Files.readString(db.backupPath()));
            assertNotEquals(original, Files.readString(file));
        }

        try (Database reopened = Database.open(file)) {
            assertEquals(1, reopened.gameCount());
            assertEquals("committed", reopened.game(alpha).tag("Annotator"));
            assertTrue(reopened.game(alpha).hasGameTag("study"));
        }
    }

    @Test
    void persistenceCannotEscapeAnInMemoryTransaction() {
        Database db = Database.inMemory();
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            try {
                tx.saveAs(tempDir.resolve("escaped.pgn"));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            return null;
        }));
    }

    private static Game game(String pgn) {
        PgnReader.ParseResult parsed =
            new PgnReader(PgnReader.ValidationMode.STRICT).readString(pgn);
        assertEquals(1, parsed.games().size());
        return parsed.games().get(0);
    }

    private static final class IntentionalFailure extends RuntimeException {}
}
