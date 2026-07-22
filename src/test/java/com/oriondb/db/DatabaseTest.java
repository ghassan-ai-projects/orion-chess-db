package com.oriondb.db;

import com.oriondb.analysis.Motifs;
import com.oriondb.search.Duplicates;
import com.oriondb.search.OpeningExplorer;
import com.oriondb.search.PlayerStats;
import com.oriondb.search.Search;
import com.oriondb.model.Game;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseTest {

    /** The Opera Game, a Najdorf, an Italian, a scholar's mate, a Lucena
     *  bridge (FEN game), and an underpromotion fork (FEN game). */
    public static final String FIXTURE = """
        [Event "Paris Opera"]
        [Site "Paris FRA"]
        [Date "1858.11.02"]
        [White "Morphy, Paul"]
        [Black "Duke Karl / Count Isouard"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
        8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
        14. Rd1 Qe6 15. Bxd7+ Nxd7 {The famous finish begins.} 16. Qb8+ {A brilliant
        queen sacrifice!} Nxb8 17. Rd8# 1-0

        [Event "Superbowl Chess"]
        [Site "Linares"]
        [Date "1999.02.24"]
        [White "Kasparov, Garry"]
        [Black "Topalov, Veselin"]
        [Result "1/2-1/2"]
        [WhiteElo "2812"]
        [BlackElo "2700"]

        1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 6. Be3 e5 7. Nb3 Be6
        8. f3 Be7 9. Qd2 O-O 1/2-1/2

        [Event "Club Match"]
        [Site "Rome"]
        [Date "2021.07.15"]
        [White "Rossi, Mario"]
        [Black "Bianchi, Luigi"]
        [Result "0-1"]
        [WhiteElo "2200"]
        [BlackElo "2250"]

        1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. c3 Nf6 5. d3 d6 6. O-O O-O 0-1

        [Event "Club Match"]
        [Site "Rome"]
        [Date "2021.07.16"]
        [White "Rossi, Mario"]
        [Black "Verdi, Giuseppe"]
        [Result "1-0"]
        [WhiteElo "2200"]
        [BlackElo "1900"]

        1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0

        [Event "Endgame Study"]
        [Site "?"]
        [Date "2020.01.01"]
        [White "Lucena, Practice"]
        [Black "Defender, Rook"]
        [Result "1-0"]
        [SetUp "1"]
        [FEN "1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1"]

        1. Rd1+ Ke7 2. Rd4 Ra1 3. Kc7 Rc1+ 4. Kb6 Rb1+ 5. Kc6 Rc1+ 6. Kb5 Rb1+
        7. Rb4 Rxb4+ 8. Kxb4 Ke6 9. b8=Q 1-0

        [Event "Promotion Trick"]
        [Site "?"]
        [Date "2020.06.01"]
        [White "Under, Promo"]
        [Black "Forked, Victim"]
        [Result "1-0"]
        [SetUp "1"]
        [FEN "8/3q1P1k/8/8/8/8/8/K7 w - - 0 1"]

        1. f8=N+ Kh8 2. Nxd7 1-0
        """;

    static Database db;

    @BeforeAll
    static void load() {
        db = Database.inMemory();
        Database.ImportReport report = db.importPgn(FIXTURE);
        assertEquals(6, report.gamesAdded(), () -> report.warnings().toString());
        assertEquals(0, report.duplicatesSkipped());
    }

    // ------------------------------------------------------------------
    // Import and classification
    // ------------------------------------------------------------------

    @Test
    void ecoAutoClassification() {
        // The Opera Game has no ECO tag; the book supplies Philidor Defense.
        Game opera = db.search().player("Morphy").find().get(0);
        assertEquals("C41", opera.tag("ECO"));
        assertNotNull(opera.tag("Opening"));
        assertTrue(opera.tag("Opening").contains("Philidor"), opera.tag("Opening"));

        // The Najdorf is B90.
        Game najdorf = db.search().player("Kasparov").find().get(0);
        assertEquals("B90", najdorf.tag("ECO"));
    }

    @Test
    void duplicateImportSkipped() {
        Database fresh = Database.inMemory();
        fresh.importPgn(FIXTURE);
        Database.ImportReport again = fresh.importPgn(FIXTURE);
        assertEquals(0, again.gamesAdded());
        assertEquals(6, again.duplicatesSkipped());
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Test
    void playerSearchIsSubstringCaseInsensitive() {
        assertEquals(1, db.search().player("morphy").count());
        assertEquals(1, db.search().white("KASPAROV").count());
        assertEquals(2, db.search().white("Rossi").count());
        assertEquals(0, db.search().black("Rossi").count());
    }

    @Test
    void resultAndDateFilters() {
        assertEquals(4, db.search().result("white").count());
        assertEquals(1, db.search().result("draw").count());
        assertEquals(5, db.search().result("decisive").count());
        assertEquals(2, db.search().dateFrom("2021").count());
        assertEquals(1, db.search().dateFrom("1850").dateTo("1900").count());
    }

    @Test
    void ecoFilters() {
        assertEquals(1, db.search().eco("B90").count());
        assertEquals(1, db.search().eco("B90-B99").count());
        assertEquals(1, db.search().eco("C4").count());   // prefix: C41 Opera game
        assertEquals(0, db.search().eco("E").count());
    }

    @Test
    void eloFilters() {
        assertEquals(1, db.search().minElo(2600, Search.EloMode.BOTH).count());
        assertEquals(3, db.search().minElo(2200, Search.EloMode.EITHER).count());
        // Italian avg 2225 and scholar's mate avg 2050 both fall in range.
        assertEquals(2, db.search().minElo(2000).maxElo(2300)
            .eloMode(Search.EloMode.AVERAGE).count());
    }

    @Test
    void openingLineAndPositionSearch() {
        // Both 1.e4 e5 2.Nf3 games pass through the same position.
        assertEquals(2, db.search().openingLine("1. e4 e5 2. Nf3").count());
        assertEquals(4, db.search().openingLine("e4").count());

        // Exact FEN containment: the Najdorf tabiya after 5...a6.
        assertEquals(1, db.search()
            .containsPosition("rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6")
            .count());
    }

    @Test
    void materialAndEndgameSearch() {
        // The Lucena game starts as KRP vs KR.
        List<Game> lucena = db.search().material("KRP vs KR").find();
        assertEquals(1, lucena.size());
        assertEquals("Lucena, Practice", lucena.get(0).white());

        // Lucena (KQ vs K) and the underpromotion game (KN vs K) both
        // reach four or fewer pieces.
        assertEquals(2, db.search().maxPieces(4).count());
        assertEquals(2, db.search().motif("promotion").count());
        assertEquals(1, db.search().motif("underpromotion").count());
    }

    @Test
    void motifSearch() {
        // Two games end in checkmate: the Opera Game and the scholar's mate.
        assertEquals(2, db.search().motif("checkmate").count());
        // The underpromotion game features a knight fork.
        assertTrue(db.search().motif("fork").count() >= 1);
        List<Motifs.Moment> moments = Motifs.moments(
            db.search().player("Under, Promo").find().get(0));
        assertFalse(moments.isEmpty());
        assertTrue((moments.get(0).motifs() & Motifs.UNDERPROMOTION) != 0);
        assertTrue((moments.get(0).motifs() & Motifs.FORK) != 0);
    }

    @Test
    void commentSearch() {
        assertEquals(1, db.search().commentContains("queen sacrifice").count());
        assertEquals(1, db.search().annotated(true).count());
        assertEquals(5, db.search().annotated(false).count());
    }

    @Test
    void sortingAndPagination() {
        List<Game> byDate = db.search().sortBy(Search.Sort.DATE, false).find();
        assertEquals("Morphy, Paul", byDate.get(0).white());
        List<Game> newestFirst = db.search().sortBy(Search.Sort.DATE, true).limit(2).find();
        assertEquals(2, newestFirst.size());
        assertEquals("2021.07.16", newestFirst.get(0).sortableDate());
        assertEquals(1, db.search().sortBy(Search.Sort.DATE, true).offset(5).find().size());
    }

    // ------------------------------------------------------------------
    // Explorer
    // ------------------------------------------------------------------

    @Test
    void openingExplorer() {
        OpeningExplorer.Result root = db.explorer().explore("");
        assertEquals(4, root.totalGames()); // the two FEN games don't start here
        assertEquals(1, root.moves().size());
        assertEquals("e4", root.moves().get(0).san());
        assertEquals(4, root.moves().get(0).games());

        OpeningExplorer.Result afterE4 = db.explorer().explore("1. e4");
        assertEquals(2, afterE4.moves().size());
        assertEquals("e5", afterE4.moves().get(0).san());
        assertEquals(3, afterE4.moves().get(0).games());
        assertEquals("c5", afterE4.moves().get(1).san());

        OpeningExplorer.Result afterNf3 = db.explorer().explore("1. e4 e5 2. Nf3");
        assertEquals(2, afterNf3.totalGames());
        assertEquals("King's Knight Opening", afterNf3.openingName());
        // Continuations: 2...d6 (Morphy) and 2...Nc6 (Italian)
        assertEquals(2, afterNf3.moves().size());
    }

    @Test
    void explorerScoreAndElo() {
        OpeningExplorer.Result afterE4 = db.explorer().explore("1. e4");
        OpeningExplorer.MoveStat e5 = afterE4.moves().get(0);
        // e5 games: Opera 1-0, Italian 0-1, Scholar 1-0 => 2 white wins, 1 black.
        assertEquals(2, e5.whiteWins());
        assertEquals(1, e5.blackWins());
        assertEquals(0, e5.draws());
        // Score from Black's perspective (Black plays e5): 1/3.
        assertFalse(afterE4.whiteToMove());
        assertEquals(33.3, e5.scorePercent(), 0.1);
    }

    // ------------------------------------------------------------------
    // Player stats
    // ------------------------------------------------------------------

    @Test
    void playerReport() {
        PlayerStats.Report rossi = db.players().report("Rossi");
        assertNotNull(rossi);
        assertEquals("Rossi, Mario", rossi.name());
        assertEquals(2, rossi.totalGames());
        assertEquals(2, rossi.asWhite().games());
        assertEquals(1, rossi.asWhite().wins());
        assertEquals(1, rossi.asWhite().losses());
        assertEquals(50.0, rossi.asWhite().percent());
        assertNotNull(rossi.performance());
        assertEquals(2200, rossi.highestElo());
    }

    @Test
    void headToHead() {
        PlayerStats.HeadToHead h2h = db.players().headToHead("Rossi", "Bianchi");
        assertNotNull(h2h);
        assertEquals(1, h2h.overall().games());
        assertEquals(1, h2h.overall().losses());
        assertNull(db.players().headToHead("Rossi", "Morphy"));
    }

    // ------------------------------------------------------------------
    // Duplicates
    // ------------------------------------------------------------------

    @Test
    void duplicateDetection() {
        Database fresh = Database.inMemory();
        fresh.importPgn(FIXTURE);
        // Force-add a copy with different tags (bypasses import dedup).
        Game copy = new com.oriondb.pgn.PgnReader().readString("""
            [White "Somebody, Else"]
            [Black "Different, Guy"]
            [Result "1-0"]

            1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0
            """).games().get(0);
        fresh.add(copy);
        List<Duplicates.Group> groups = Duplicates.find(fresh);
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).gameIds().size());
        assertFalse(groups.get(0).samePlayers());
    }

    // ------------------------------------------------------------------
    // Mutation and persistence
    // ------------------------------------------------------------------

    @Test
    void addDeleteSaveReopen() throws Exception {
        Path file = Files.createTempFile("orion-dbtest", ".pgn");
        Files.delete(file); // Database.open creates it on save
        try {
            try (Database database = Database.open(file)) {
                database.importPgn(FIXTURE);
                assertTrue(database.isDirty());
                database.save();
                assertFalse(database.isDirty());
            }

            try (Database reopened = Database.open(file)) {
                reopened.setSaveOnClose(false);
                assertEquals(6, reopened.gameCount());
                // Auto-filled ECO survived the round trip.
                Game opera = reopened.search().player("Morphy").find().get(0);
                assertEquals("C41", opera.tag("ECO"));
                // Comments survived.
                assertEquals(1, reopened.search().commentContains("queen sacrifice").count());

                // Delete updates indexes.
                int scholarId = reopened.search().player("Verdi").find().get(0).id();
                assertTrue(reopened.delete(scholarId));
                assertEquals(5, reopened.gameCount());
                assertEquals(3, reopened.explorer().explore("").totalGames());
                assertEquals(1, reopened.search().motif("checkmate").count());

                // Tag edit
                int najdorfId = reopened.search().player("Kasparov").find().get(0).id();
                assertTrue(reopened.setTag(najdorfId, "Annotator", "Tester"));
                reopened.save();
            }

            try (Database again = Database.open(file)) {
                again.setSaveOnClose(false);
                assertEquals(5, again.gameCount());
                assertEquals("Tester",
                    again.search().player("Kasparov").find().get(0).tag("Annotator"));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
