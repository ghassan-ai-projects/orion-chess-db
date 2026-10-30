package com.oriondb.db;

import com.oriondb.analysis.Material;
import com.oriondb.analysis.Motif;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.query.Query;
import com.oriondb.query.QueryParser;
import com.oriondb.query.SearchResult;
import com.oriondb.query.Searcher;
import com.oriondb.stats.DatabaseStats;
import com.oriondb.stats.Duplicates;
import com.oriondb.stats.HeadToHead;
import com.oriondb.stats.OpeningExplorer;
import com.oriondb.stats.PlayerReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    static final String GAMES = """
        [Event "Club"]
        [Site "Here"]
        [Date "2020.03.01"]
        [Round "1"]
        [White "Carlsen, Magnus"]
        [Black "Doe, John"]
        [Result "1-0"]
        [WhiteElo "2850"]
        [BlackElo "2100"]

        1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 {Najdorf} 6. Be3 e5 1-0

        [Event "Club"]
        [Date "2021.05.01"]
        [White "Doe, John"]
        [Black "Carlsen, Magnus"]
        [Result "0-1"]
        [WhiteElo "2150"]
        [BlackElo "2860"]

        1. Nf3 Nf6 2. d4 d6 3. e4 c5 (3... e5) 4. Nc3 cxd4 5. Nxd4 a6 0-1

        [Event "Legal"]
        [White "Legal"]
        [Black "Saint Brie"]
        [Result "1-0"]
        [Date "1750.??.??"]

        1. e4 e5 2. Nf3 d6 3. Bc4 Bg4 4. Nc3 g6 5. Nxe5 Bxd1 6. Bxf7+ Ke7 7. Nd5# 1-0

        [Event "Broken"]
        [White "X"]
        [Black "Y"]
        [Result "*"]

        1. e4 e5 2. Ke3 *

        [Event "Endgame"]
        [White "A"]
        [Black "B"]
        [Result "1/2-1/2"]
        [SetUp "1"]
        [FEN "8/8/4k3/8/3PK3/8/8/7r w - - 0 60"]

        60. d5+ Kd6 61. Kd4 Rh4+ 1/2-1/2
        """;

    private static Path write(Path dir, String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void openParsesKeepsInvalidAndAssignsIds(@TempDir Path dir) throws Exception {
        Path file = write(dir, "db.pgn", GAMES);
        try (Database db = Database.open(file)) {
            assertEquals(5, db.size());
            assertEquals(1, db.invalidGames().size());
            assertTrue(db.invalidGames().get(0).error().contains("Ke3"));
            Snapshot s = db.snapshot();
            assertEquals(List.of(1, 2, 3, 4, 5), s.records().stream().map(GameRecord::id).toList());
            assertEquals("B90", s.get(1).eco());
            assertTrue(s.get(1).opening().startsWith("Sicilian Defense: Najdorf"), s.get(1).opening());
            assertEquals("B90", s.get(2).eco(), "transposition-aware ECO");
            assertTrue(s.get(3).hasMotif(Motif.CHECKMATE));
            assertTrue(s.get(5).has(GameRecord.SETUP));
            assertFalse(db.isDirty());
        }
        assertTrue(Files.exists(dir.resolve("db.pgn.oidx")), "cache written");
        assertEquals(GAMES, Files.readString(file), "opening never rewrites the PGN");
    }

    @Test
    void cacheReopenAndSaveRoundTrip(@TempDir Path dir) throws Exception {
        Path file = write(dir, "db.pgn", GAMES);
        try (Database db = Database.open(file)) {
            db.update(b -> b.updateLabels(1, List.of("Prep", "sicilian"), List.of()));
            db.setTag(2, "Annotator", "Me");
            db.save();
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("[OrionId \"1\"]"));
        assertTrue(saved.contains("[OrionLabels \"prep,sicilian\"]"));
        assertTrue(saved.contains("[Annotator \"Me\"]"));
        assertTrue(saved.contains("1. e4 e5 2. Ke3 *"), "invalid game preserved verbatim");
        assertTrue(Files.exists(dir.resolve("db.pgn.bak")));
        assertEquals(GAMES, Files.readString(dir.resolve("db.pgn.bak")));

        for (boolean useCache : new boolean[] {true, false}) {
            try (Database db = Database.open(file, new Database.Options(true, false, useCache, false))) {
                Snapshot s = db.snapshot();
                assertEquals(5, s.size());
                assertEquals(List.of("prep", "sicilian"), s.get(1).labels());
                assertEquals("Me", s.get(2).tag("Annotator"));
                assertEquals(1, Searcher.search(s, new Query().withLabel("prep")).total());
                assertEquals("Najdorf", db.game(1).nodeAtPly(10).comment());
            }
        }
    }

    @Test
    void batchesAreAtomic(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(write(dir, "db.pgn", GAMES))) {
            assertThrows(DatabaseException.class, () -> db.update(b -> {
                b.delete(1);
                b.delete(999);
            }));
            assertEquals(5, db.size());
            assertFalse(db.isDirty());
            db.delete(List.of(1, 3));
            assertEquals(3, db.size());
            assertNull(db.snapshot().find(1));
            assertEquals(0, Searcher.search(db.snapshot(), new Query().position(OpeningExplorer.positionAfter("e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6"))).total() - 1);
        }
    }

    @Test
    void addReplaceAndPositionOverlay(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(write(dir, "db.pgn", GAMES))) {
            Game g = Game.fromMoves("d4 d5 c4 e6 Nc3 Nf6");
            g.tags().set("White", "New");
            int id = db.add(g);
            assertEquals(6, id);
            var ruyLike = OpeningExplorer.positionAfter("d4 Nf6 c4 e6 Nc3 d5");
            assertEquals(List.of(6), Searcher.ids(db.snapshot(), new Query().position(ruyLike)));

            Game edit = db.game(id);
            GameNode last = edit.end();
            last.setComment("Queen's Gambit Declined");
            last.play("Bg5");
            edit.find("d4 d5").play("Nf3");
            db.replace(id, edit);
            Game reread = db.game(id);
            assertEquals(7, reread.plyCount());
            assertTrue(reread.hasVariations());
            assertTrue(db.record(id).has(GameRecord.HAS_COMMENTS));
            assertEquals("New", db.record(id).white());
            db.save();
        }
    }

    @Test
    void importDedupesAndReportsErrors(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(dir.resolve("new.pgn"))) {
            ImportResult r = db.importText(GAMES + "\n\n" + GAMES, ImportOptions.DEFAULT.withLabels(List.of("Imported")));
            assertEquals(10, r.read());
            assertEquals(4, r.added());
            assertEquals(4, r.duplicates());
            assertEquals(2, r.invalid());
            assertTrue(r.errors().get(0).message().contains("Ke3"));
            assertTrue(db.record(r.ids().get(0)).hasLabel("imported"));
            assertThrows(ImportException.class, () -> db.importText(GAMES, ImportOptions.DEFAULT.withAtomic(true).withSkipDuplicates(false)));
            assertEquals(4, db.size());
            db.save();
        }
        try (Database db = Database.open(dir.resolve("new.pgn"))) {
            assertEquals(4, db.size());
        }
    }

    @Test
    void detectsExternalModificationAndLocks(@TempDir Path dir) throws Exception {
        Path file = write(dir, "db.pgn", GAMES);
        try (Database db = Database.open(file)) {
            assertThrows(DatabaseException.class, () -> Database.open(file));
            try (Database ro = Database.open(file, Database.Options.READ_ONLY)) {
                assertEquals(5, ro.size());
                assertThrows(DatabaseException.class, () -> ro.delete(List.of(1)));
            }
            db.delete(List.of(1));
            Files.writeString(file, GAMES + "\n");
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
            DatabaseException e = assertThrows(DatabaseException.class, db::save);
            assertTrue(e.getMessage().contains("modified by another program"));
        }
    }

    @Test
    void searchLanguage(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(write(dir, "db.pgn", GAMES))) {
            Snapshot s = db.snapshot();
            assertEquals(Set.of(1, 2), ids(s, "player:\"magnus carlsen\""));
            assertEquals(Set.of(2), ids(s, "player:carlsen as:black outcome:win"));
            assertEquals(Set.of(1, 2), ids(s, "eco:B90-B99 elo:2800"));
            assertEquals(Set.of(2), ids(s, "year:2021 variations:yes"));
            assertEquals(Set.of(1), ids(s, "line:\"1. e4 c5\""));
            assertEquals(Set.of(3), ids(s, "motif:checkmate motifby:white"));
            assertEquals(Set.of(), ids(s, "motif:checkmate motifby:black"));
            assertEquals(Set.of(3), ids(s, "date:<=1800"));
            assertEquals(Set.of(5), ids(s, "material:\"KR vs KP\" pieces:<=4"));
            assertEquals(Set.of(5), ids(s, "pattern:\"Kd4 kd6 Pd5\""));
            assertEquals(Set.of(1, 2), ids(s, "pattern:\"pa6 Nd4 w\""));
            assertEquals(Set.of(1), ids(s, "comment:najdorf"));
            assertEquals(Set.of(1, 2), ids(s, "club"));
            assertEquals(Set.of(3), ids(s, "result:1-0 nolabel:x event:legal"));
            SearchResult r = Searcher.search(s, QueryParser.parse("sort:-date limit:1"));
            assertEquals(4, r.total());
            assertEquals(2, r.matches().get(0).record().id());
            assertThrows(IllegalArgumentException.class, () -> QueryParser.parse("bogus:1"));
        }
    }

    private static Set<Integer> ids(Snapshot s, String q) {
        return Set.copyOf(Searcher.ids(s, QueryParser.parse(q)));
    }

    @Test
    void explorerPlayersAndDuplicates(@TempDir Path dir) throws Exception {
        try (Database db = Database.open(write(dir, "db.pgn", GAMES + "\n\n" + GAMES.substring(0, GAMES.indexOf("[Event \"Club\"]\n[Date")).replace("[Round \"1\"]", "[Round \"2\"]")))) {
            Snapshot s = db.snapshot();
            OpeningExplorer.Result start = OpeningExplorer.explore(s, OpeningExplorer.positionAfter(""), null, 3);
            assertEquals(4, start.games(), "games starting from the initial position");
            assertEquals("e4", start.moves().get(0).san());
            assertEquals(3, start.moves().get(0).games());

            OpeningExplorer.Result najdorf = OpeningExplorer.explore(s,
                OpeningExplorer.positionAfter("e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6"), null, 3);
            assertEquals(3, najdorf.games());
            assertEquals(1, najdorf.endedHere());
            assertEquals("Be3", najdorf.moves().get(0).san());

            PlayerReport.Report rep = PlayerReport.build(s, "carlsen", 5);
            assertEquals(3, rep.total().games());
            assertEquals(100.0, rep.total().percent());
            assertEquals(2860, rep.peakElo());
            assertEquals(2, rep.asWhite().games());

            HeadToHead.Result h = HeadToHead.compare(s, "Carlsen", "Doe");
            assertEquals(3, h.total().wins());
            assertEquals(1, h.aAsBlack().games());

            List<Duplicates.Group> dups = Duplicates.find(s, true, 0);
            assertEquals(1, dups.size());
            assertEquals(2, dups.get(0).ids().size());

            DatabaseStats.Stats st = DatabaseStats.compute(s, 5);
            assertEquals(6, st.games());
            assertEquals(1, st.invalid());
            assertEquals(1750, st.firstYear());
        }
    }

    @Test
    void materialSignatures() {
        long sig = Material.parse("KRP vs KR");
        assertEquals("KRP vs KR", Material.format(sig));
        assertEquals(sig, Material.parse("KRPKR"));
        assertEquals("KR vs KRP", Material.format(Material.mirror(sig)));
        assertEquals(5, Material.pieceCount(sig));
    }

    @Test
    void inMemoryDatabase() {
        try (Database db = Database.inMemory()) {
            int id = db.add(Game.fromMoves("e4 e5"));
            db.save();
            assertEquals(1, id);
            assertTrue(db.pgn(id).contains("1. e4 e5"));
        }
    }
}
