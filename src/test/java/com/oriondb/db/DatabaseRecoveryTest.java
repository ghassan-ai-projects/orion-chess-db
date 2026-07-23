package com.oriondb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRecoveryTest {

    private static final String GAME = """
        [White "Alpha"]
        [Black "Beta"]
        [Result "*"]

        1. e4 e5 *
        """;

    @TempDir
    Path tempDir;

    @Test
    void eachSaveKeepsThePreviousDatabaseGeneration() throws Exception {
        Path file = tempDir.resolve("games.pgn");
        Files.writeString(file, GAME);

        try (Database db = Database.open(file)) {
            int id = db.games().iterator().next().id();
            db.setTag(id, "Annotator", "first");
            db.save();

            Path backup = db.backupPath();
            assertTrue(Files.exists(backup));
            assertEquals(GAME, Files.readString(backup));

            String firstSavedGeneration = Files.readString(file);
            db.setTag(id, "Annotator", "second");
            db.save();
            assertEquals(firstSavedGeneration, Files.readString(backup));
        }
    }

    @Test
    void backupsCanBeDisabledForCallerManagedPersistence() throws Exception {
        Path file = tempDir.resolve("caller-managed.pgn");
        Files.writeString(file, GAME);

        try (Database db = Database.open(file)) {
            db.setBackupsEnabled(false);
            db.setTag(db.games().iterator().next().id(), "Annotator", "external backup");
            db.save();
            assertFalse(Files.exists(db.backupPath()));
        }
    }

    @Test
    void optionalProcessLockIsExclusiveAndReleasedOnClose() throws Exception {
        Path file = tempDir.resolve("locked.pgn");

        try (Database ignored =
                 Database.open(file, Database.OpenOptions.locked())) {
            Database.DatabaseLockException error = assertThrows(
                Database.DatabaseLockException.class,
                () -> Database.open(file, Database.OpenOptions.locked()));
            assertEquals(file.toAbsolutePath().resolveSibling("locked.pgn.lock"),
                error.lockPath());
        }

        try (Database ignored =
                 Database.open(file, Database.OpenOptions.locked())) {
            assertTrue(Files.exists(ignored.lockPath()));
        }
    }
}
