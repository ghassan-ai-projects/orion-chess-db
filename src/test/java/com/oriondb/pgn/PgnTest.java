package com.oriondb.pgn;

import com.oriondb.model.Game;
import com.oriondb.model.MoveNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgnTest {

    private static final String ANNOTATED = """
        [Event "Test Match"]
        [Site "Somewhere"]
        [Date "2023.05.15"]
        [Round "3"]
        [White "Alpha, Alice"]
        [Black "Beta, Bob"]
        [Result "1-0"]
        [WhiteElo "2400"]
        [BlackElo "2350"]
        [ECO "C50"]

        {A test game.} 1. e4 e5 2. Nf3 {developing} Nc6 3. Bc4 $1 Bc5
        (3... Nf6 {the Two Knights} 4. Ng5 (4. d3 Be7) 4... d5 5. exd5) 4. c3 Nf6 5. d4
        exd4 6. cxd4 Bb4+ 7. Nc3 Nxe4 8. O-O Bxc3 9. d5 $14 1-0
        """;

    @Test
    void parsesTagsAndMoves() {
        PgnReader.ParseResult result = new PgnReader().readString(ANNOTATED);
        assertEquals(0, result.warnings().size(), () -> result.warnings().toString());
        assertEquals(1, result.games().size());
        Game game = result.games().get(0);
        assertEquals("Alpha, Alice", game.white());
        assertEquals("Beta, Bob", game.black());
        assertEquals("1-0", game.result());
        assertEquals(2400, game.whiteElo());
        assertEquals("C50", game.eco());
        assertEquals(17, game.plyCount());
        assertEquals("A test game.", game.gameComment());
        assertEquals(List.of("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3", "Nf6", "d4",
            "exd4", "cxd4", "Bb4+", "Nc3", "Nxe4", "O-O", "Bxc3", "d5"),
            game.mainlineSans());
    }

    @Test
    void preservesAnnotations() {
        Game game = new PgnReader().readString(ANNOTATED).games().get(0);
        // Move 2. Nf3 has a comment
        MoveNode nf3 = game.root().next().next();
        assertEquals("Nf3", nf3.san());
        assertEquals("developing", nf3.comment());
        // 3. Bc4 has NAG $1
        MoveNode bc4 = nf3.next().next();
        assertEquals("Bc4", bc4.san());
        assertEquals(List.of(1), bc4.nags());
        // 3... Bc5 has a variation starting 3... Nf6 with a nested variation
        MoveNode bc5 = bc4.next();
        assertEquals(1, bc5.variations().size());
        MoveNode nf6 = bc5.variations().get(0);
        assertEquals("Nf6", nf6.san());
        assertEquals("the Two Knights", nf6.comment());
        MoveNode ng5 = nf6.next();
        assertEquals("Ng5", ng5.san());
        assertEquals(1, ng5.variations().size());
        assertEquals("d3", ng5.variations().get(0).san());
    }

    @Test
    void roundTripPreservesEverything() {
        PgnReader reader = new PgnReader();
        Game original = reader.readString(ANNOTATED).games().get(0);
        String written = new PgnWriter().write(original);
        PgnReader.ParseResult reparsed = reader.readString(written);
        assertEquals(0, reparsed.warnings().size(), () -> reparsed.warnings().toString());
        Game copy = reparsed.games().get(0);

        assertEquals(original.tags(), copy.tags());
        assertEquals(original.gameComment(), copy.gameComment());
        assertTreesEqual(original.root(), copy.root());
    }

    private void assertTreesEqual(MoveNode a, MoveNode b) {
        while (a != null || b != null) {
            assertNotNull(a, "tree A ended early");
            assertNotNull(b, "tree B ended early");
            assertEquals(a.san(), b.san());
            assertEquals(a.comment(), b.comment());
            assertEquals(a.commentBefore(), b.commentBefore());
            assertEquals(a.nags(), b.nags());
            assertEquals(a.variations().size(), b.variations().size(), "variations of " + a.san());
            for (int i = 0; i < a.variations().size(); i++) {
                assertTreesEqual(a.variations().get(i), b.variations().get(i));
            }
            a = a.next();
            b = b.next();
        }
    }

    @Test
    void multipleGames() {
        String pgn = """
            [White "One"]
            [Result "1-0"]

            1. e4 1-0

            [White "Two"]
            [Result "0-1"]

            1. d4 d5 0-1
            """;
        List<Game> games = new PgnReader().readString(pgn).games();
        assertEquals(2, games.size());
        assertEquals("One", games.get(0).white());
        assertEquals("Two", games.get(1).white());
        assertEquals(List.of("d4", "d5"), games.get(1).mainlineSans());
    }

    @Test
    void gluedMoveNumbersAndZeroCastling() {
        String pgn = """
            [Result "*"]

            1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6 4.Qxf7# *
            """;
        Game game = new PgnReader().readString(pgn).games().get(0);
        assertEquals(List.of("e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"), game.mainlineSans());

        String zeros = "[Result \"*\"]\n\n1. e4 e5 2. Nf3 Nf6 3. Bc4 Bc5 4.0-0 0-0 *";
        Game castled = new PgnReader().readString(zeros).games().get(0);
        assertEquals("O-O", castled.mainlineSans().get(6));
        assertEquals("O-O", castled.mainlineSans().get(7));
    }

    @Test
    void illegalMainlineMoveTruncatesWithWarning() {
        // 2. Nf6 is illegal for White: game keeps the legal prefix only.
        String broken = """
            [White "Bad"]
            [Result "*"]

            1. e4 e5 2. Nf6 d5 *
            """;
        PgnReader.ParseResult result = new PgnReader().readString(broken);
        assertEquals(1, result.games().size());
        assertEquals(List.of("e4", "e5"), result.games().get(0).mainlineSans());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().get(0).contains("Nf6"));
    }

    @Test
    void strictModeRejectsIllegalGameAndContinues() {
        String pgn = """
            [White "Bad"]
            [Black "Move"]
            [Result "*"]

            1. e4 e5 2. Nf6 *

            [White "Good"]
            [Black "Game"]
            [Result "*"]

            1. d4 d5 *
            """;
        PgnReader.ParseResult result =
            new PgnReader(PgnReader.ValidationMode.STRICT).readString(pgn);

        assertEquals(1, result.games().size());
        assertEquals("Good", result.games().get(0).white());
        assertEquals(2, result.parsedGames().get(0).gameNumber());
        assertEquals(1, result.rejectedGames().size());
        PgnReader.RejectedGame rejected = result.rejectedGames().get(0);
        assertEquals(1, rejected.gameNumber());
        assertEquals("Bad", rejected.tags().get("White"));
        assertEquals("ILLEGAL_MAINLINE_MOVE", rejected.errors().get(0).code());
    }

    @Test
    void strictModeRejectsInvalidFen() {
        PgnReader.ParseResult result =
            new PgnReader(PgnReader.ValidationMode.STRICT).readString("""
                [White "Bad FEN"]
                [Result "*"]
                [SetUp "1"]
                [FEN "not a fen"]

                1. e4 *
                """);

        assertTrue(result.games().isEmpty());
        assertEquals("INVALID_FEN",
            result.rejectedGames().get(0).errors().get(0).code());
    }

    @Test
    void illegalVariationDropsOnlyVariation() {
        String pgn = """
            [Result "*"]

            1. e4 e5 (1... Qh4 2. Nxh4) 2. Nf3 *
            """;
        // The variation replaces 1...e5, so it starts after 1.e4 where
        // 1...Qh4 is illegal (the e7 pawn blocks the d8-h4 diagonal).
        PgnReader.ParseResult result = new PgnReader().readString(pgn);
        Game game = result.games().get(0);
        assertEquals(List.of("e4", "e5", "Nf3"), game.mainlineSans());
        assertEquals(0, game.root().next().variations().size(), "illegal variation dropped");
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void fenSetupGames() {
        String pgn = """
            [White "Endgame"]
            [Result "1-0"]
            [SetUp "1"]
            [FEN "4k3/8/4K3/4P3/8/8/8/8 w - - 0 60"]

            60. Kd6 Kd8 61. e6 Ke8 62. e7 Kf7 63. Kd7 1-0
            """;
        PgnReader.ParseResult result = new PgnReader().readString(pgn);
        assertEquals(0, result.warnings().size(), () -> result.warnings().toString());
        Game game = result.games().get(0);
        assertEquals(7, game.plyCount());
        // Round trip keeps FEN numbering
        String written = new PgnWriter().write(game);
        assertTrue(written.contains("60. Kd6"), written);
        Game again = new PgnReader().readString(written).games().get(0);
        assertEquals(game.mainlineSans(), again.mainlineSans());
    }

    @Test
    void escapeLinesAndSemicolonComments() {
        String pgn = """
            %this whole line is ignored
            [Result "*"]

            1. e4 ; king's pawn
            e5 2. Nf3 *
            """;
        PgnReader.ParseResult result = new PgnReader().readString(pgn);
        Game game = result.games().get(0);
        assertEquals(List.of("e4", "e5", "Nf3"), game.mainlineSans());
        assertEquals("king's pawn", game.root().comment());
    }

    @Test
    void missingTerminatorBeforeNextGame() {
        String pgn = """
            [White "First"]
            [Result "*"]

            1. e4 e5

            [White "Second"]
            [Result "*"]

            1. d4 *
            """;
        PgnReader.ParseResult result = new PgnReader().readString(pgn);
        assertEquals(2, result.games().size());
        assertEquals(List.of("e4", "e5"), result.games().get(0).mainlineSans());
        assertEquals(List.of("d4"), result.games().get(1).mainlineSans());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no result terminator")));
    }

    @Test
    void nagsRoundTrip() {
        String pgn = "[Result \"*\"]\n\n1. e4! e5?? 2. Nf3 $22 $138 *";
        Game game = new PgnReader().readString(pgn).games().get(0);
        assertEquals(List.of(1), game.root().nags());
        assertEquals(List.of(4), game.root().next().nags());
        assertEquals(List.of(22, 138), game.root().next().next().nags());

        String written = new PgnWriter().write(game);
        Game again = new PgnReader().readString(written).games().get(0);
        assertEquals(List.of(1), again.root().nags());
        assertEquals(List.of(4), again.root().next().nags());
        assertEquals(List.of(22, 138), again.root().next().next().nags());
    }

    @Test
    void tagValueEscapes() {
        String pgn = "[Event \"The \\\"Great\\\" Open\"]\n[Result \"*\"]\n\n1. e4 *";
        Game game = new PgnReader().readString(pgn).games().get(0);
        assertEquals("The \"Great\" Open", game.event());
        String written = new PgnWriter().write(game);
        Game again = new PgnReader().readString(written).games().get(0);
        assertEquals("The \"Great\" Open", again.event());
    }
}
