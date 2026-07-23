package com.oriondb.db;

import com.oriondb.model.Game;
import com.oriondb.model.GameTags;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTagTest {

    @Test
    void tagsAreNormalizedDeduplicatedAndDeterministic() {
        assertEquals(Set.of("opening prep", "tactics"),
            GameTags.normalizeAll(List.of(" TACTICS ", "opening   prep", "tactics")));
        assertEquals("opening prep, tactics",
            GameTags.format(List.of("TACTICS", "Opening Prep")));
        assertEquals(Set.of("opening prep", "tactics"),
            GameTags.parse("Tactics, opening   prep, tactics"));

        assertThrows(IllegalArgumentException.class, () -> GameTags.normalize(" "));
        assertThrows(IllegalArgumentException.class, () -> GameTags.normalize("one,two"));
    }

    @Test
    void bulkUpdatesMultipleGamesAndReportsMissingIds() {
        Database db = database();
        List<Integer> ids = db.games().stream().map(Game::id).toList();
        GameData originalData = db.data(ids.get(0));

        Database.TagUpdateReport report = db.updateGameTags(
            List.of(ids.get(0), ids.get(1), ids.get(0), 999),
            List.of("Tactics", "Study"), List.of());

        assertEquals(2, report.gamesUpdated());
        assertEquals(0, report.gamesUnchanged());
        assertEquals(List.of(999), report.missingGameIds());
        assertEquals(Set.of("study", "tactics"), db.game(ids.get(0)).gameTags());
        assertSame(originalData, db.data(ids.get(0)),
            "metadata-only updates must not rebuild derived chess data");

        Database.TagUpdateReport second = db.updateGameTags(
            ids, List.of("favorite"), List.of("study"));
        assertEquals(2, second.gamesUpdated());
        assertEquals(Set.of("favorite", "tactics"), db.game(ids.get(0)).gameTags());
    }

    @Test
    void tagsCanBeReplacedOrCleared() {
        Database db = database();
        List<Integer> ids = db.games().stream().map(Game::id).toList();
        db.updateGameTags(ids, List.of("old"), List.of());

        assertTrue(db.setGameTags(ids.get(0), List.of("New", "Reviewed")));
        assertEquals(Set.of("new", "reviewed"), db.game(ids.get(0)).gameTags());

        Database.TagUpdateReport cleared = db.setGameTags(ids, List.of());
        assertEquals(2, cleared.gamesUpdated());
        assertTrue(db.game(ids.get(0)).gameTags().isEmpty());
        assertTrue(db.game(ids.get(1)).gameTags().isEmpty());
    }

    @Test
    void searchSupportsAllAnyAndExcludedTags() {
        Database db = database();
        List<Integer> ids = db.games().stream().map(Game::id).toList();
        db.setGameTags(ids.get(0), List.of("tactics", "study"));
        db.setGameTags(ids.get(1), List.of("tactics", "imported"));

        assertEquals(2, db.search().tag("TACTICS").count());
        assertEquals(1, db.search().tags("tactics", "study").count());
        assertEquals(2, db.search().anyTags("study", "imported").count());
        assertEquals(1, db.search().tag("tactics").withoutTag("imported").count());
        assertEquals(0, db.search().tag("missing").count());
        assertEquals(2, db.gameTagCounts().get("tactics"));
        assertEquals(1, db.gameTagCounts().get("study"));
    }

    @Test
    void invalidBulkUpdateIsAtomic() {
        Database db = database();
        List<Integer> ids = db.games().stream().map(Game::id).toList();

        assertThrows(IllegalArgumentException.class,
            () -> db.updateGameTags(ids, List.of("valid", "bad,tag"), List.of()));
        assertTrue(db.game(ids.get(0)).gameTags().isEmpty());
        assertTrue(db.game(ids.get(1)).gameTags().isEmpty());
    }

    @Test
    void tagsCanBeRenamedMergedAndDeletedAcrossTheDatabase() {
        Database db = database();
        List<Integer> ids = db.games().stream().map(Game::id).toList();
        db.addGameTags(ids.get(0), "to review");

        Database.TagUpdateReport renamed =
            db.renameGameTag("TO   REVIEW", "review queue");
        assertEquals(1, renamed.gamesUpdated());
        assertEquals(Set.of("review queue"), db.game(ids.get(0)).gameTags());
        assertTrue(db.game(ids.get(1)).gameTags().isEmpty(),
            "games without the source tag must not gain the destination");

        db.addGameTags(ids.get(0), "favorite");
        db.addGameTags(ids.get(1), "review queue");
        assertThrows(IllegalArgumentException.class,
            () -> db.renameGameTag("review queue", "favorite"));

        Database.TagUpdateReport merged =
            db.mergeGameTag("review queue", "favorite");
        assertEquals(2, merged.gamesUpdated());
        assertEquals(Set.of("favorite"), db.game(ids.get(0)).gameTags());
        assertEquals(Set.of("favorite"), db.game(ids.get(1)).gameTags());

        Database.TagUpdateReport deleted = db.deleteGameTag("FAVORITE");
        assertEquals(2, deleted.gamesUpdated());
        assertTrue(db.game(ids.get(0)).gameTags().isEmpty());
        assertTrue(db.game(ids.get(1)).gameTags().isEmpty());
    }

    @Test
    void searchResultsCanBeTaggedDirectly() {
        Database db = database();
        List<Integer> ids = db.games().stream().map(Game::id).toList();
        db.addGameTags(ids, List.of("inbox"));

        Database.TagUpdateReport selected =
            db.search().player("Alpha").addGameTags("reviewed");
        assertEquals(1, selected.gamesUpdated());
        assertTrue(db.game(ids.get(0)).hasGameTag("reviewed"));
        assertFalse(db.game(ids.get(1)).hasGameTag("reviewed"));

        Database.TagUpdateReport limited =
            db.search().tag("inbox").limit(1).addGameTags("first");
        assertEquals(1, limited.gamesUpdated());
        assertEquals(1, db.search().tag("first").count());

        Database.TagUpdateReport replaced =
            db.search().tag("reviewed").setGameTags(List.of("done"));
        assertEquals(1, replaced.gamesUpdated());
        assertEquals(Set.of("done"), db.game(ids.get(0)).gameTags());
    }

    @Test
    void tagsPersistInPortablePgnHeader() throws Exception {
        Path file = Files.createTempFile("orion-tags", ".pgn");
        Files.delete(file);
        try {
            int id;
            try (Database db = Database.open(file)) {
                db.importPgn(games());
                id = db.games().iterator().next().id();
                db.setGameTags(id, List.of("Opening Prep", "Tactics"));
                db.save();
            }
            assertTrue(Files.readString(file)
                .contains("[OrionTags \"opening prep, tactics\"]"));

            try (Database reopened = Database.open(file)) {
                reopened.setSaveOnClose(false);
                assertEquals(Set.of("opening prep", "tactics"),
                    reopened.game(id).gameTags());
                assertEquals(1, reopened.search().tag("opening prep").count());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Database database() {
        Database db = Database.inMemory();
        Database.ImportReport report = db.importPgn(games());
        assertEquals(2, report.gamesAdded(), () -> report.warnings().toString());
        return db;
    }

    private static String games() {
        return """
            [White "Alpha"]
            [Black "Beta"]
            [Result "1-0"]

            1. e4 e5 1-0

            [White "Gamma"]
            [Black "Delta"]
            [Result "0-1"]

            1. d4 d5 0-1
            """;
    }
}
