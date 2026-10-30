package com.oriondb.pgn;

import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgnTest {

    static final String ANNOTATED = """
        [Event "Test \\"quoted\\" event"]
        [Site "Somewhere"]
        [Date "2024.01.02"]
        [Round "1"]
        [White "Alpha, A."]
        [Black "Beta, B."]
        [Result "1-0"]
        [Annotator "Me"]

        {Game comment} 1. e4 $1 {Best by test} e5 (1... c5 {Sicilian} 2. Nf3 (2. c3 d5) 2... d6)
        (; line comment
        {Before} 1... e6 $6) 2. Nf3 Nc6!? 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 6. Re1 b5 7. Bb3 d6
        8. c3 O-O 9. h3 {[%clk 0:10:00] [%eval 0.31]} 1-0
        """;

    @Test
    void parsesTreeAndAnnotations() throws Exception {
        PgnParser.Result r = PgnParser.parse(ANNOTATED);
        Game g = r.game();
        assertEquals("Test \"quoted\" event", g.tag("Event"));
        assertEquals("Game comment", g.root().comment());
        assertEquals(17, g.plyCount());
        GameNode e4 = g.root().next();
        assertArrayEquals(new int[] {Nag.GOOD}, e4.nags());
        assertEquals("Best by test", e4.comment());
        assertEquals(2, e4.variations().size());
        GameNode c5 = e4.variations().get(0);
        assertEquals("c5", c5.san());
        assertEquals("Sicilian", c5.comment());
        GameNode e6 = e4.variations().get(1);
        assertEquals("line comment Before", e6.startingComment());
        assertTrue(e6.hasNag(Nag.DUBIOUS));
        assertTrue(g.find("e4 e5 Nf3 Nc6").hasNag(Nag.INTERESTING));
        assertEquals("c3", g.find("e4 c5").variations().get(0).san());
        GameNode last = g.end();
        assertEquals("0:10:00", last.command("clk"));
        assertEquals("0.31", last.command("eval"));
    }

    @Test
    void roundTripIsStable() throws Exception {
        Game g = PgnParser.parseGame(ANNOTATED);
        String once = PgnWriter.write(g);
        String twice = PgnWriter.write(PgnParser.parseGame(once));
        assertEquals(once, twice);
        for (String line : once.split("\n")) assertTrue(line.length() <= 80, line);
        System.out.println(once);
        assertTrue(once.replace('\n', ' ').contains("(1... c5 {Sicilian} 2. Nf3 (2. c3 d5) 2... d6)"), once);
    }

    @Test
    void lenientSyntax() throws Exception {
        String pgn = "[White \"x]\n[Black \"y\"]\n\n1.e4 e5 2.Nf3 Nc6 3.Bb5 a6 4.Ba4 Nf6 5.0-0 Be7 6.Re1 b5 7.Bb3 d6 8.c3 0-0 *";
        PgnParser.Result r = PgnParser.parse(pgn);
        assertEquals("x", r.game().tag("White"));
        assertEquals(16, r.game().plyCount());
        assertEquals("*", r.game().result());
    }

    @Test
    void illegalMainlineMoveRejectsGame() {
        PgnException e = assertThrows(PgnException.class, () -> PgnParser.parseGame("1. e4 e5 2. Ke3 *"));
        assertTrue(e.getMessage().contains("Ke3"), e.getMessage());
    }

    @Test
    void illegalVariationMoveIsDropped() throws Exception {
        PgnParser.Result r = PgnParser.parse("1. e4 (1. d4 Nf6 2. Qxh7) e5 2. Nf3 *");
        assertEquals(3, r.game().plyCount());
        assertEquals("Nf6", r.game().root().variations().get(0).next().san());
        assertNull(r.game().root().variations().get(0).next().next());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void setUpAndChess960() throws Exception {
        String pgn = "[Variant \"Chess960\"]\n[SetUp \"1\"]\n[FEN \"4rkr1/pppppppp/8/8/8/8/PPPPPPPP/4RKR1 w GEge - 0 9\"]\n\n9. O-O O-O *";
        Game g = PgnParser.parseGame(pgn);
        assertTrue(g.isChess960());
        assertEquals("O-O", g.root().next().san());
        assertTrue(PgnWriter.write(g).contains("9. O-O"));
        assertThrows(PgnException.class, () -> PgnParser.parseGame("[Variant \"Crazyhouse\"]\n\n1. e4 *"));
    }

    @Test
    void splitterHandlesOffsetsCommentsAndRecovery() throws Exception {
        String pgn = "﻿[Event \"A\"]\n\n1. e4 {comment with\n[bracket line]} e5 1/2-1/2\n\n"
            + "[Event \"B\"]\n\n1. d4 {unclosed comment\n\n[Event \"C\"]\n\n1. c4 *\n"
            + "[Event \"D\"]\n[Site \"tags only\"]\n\n[Event \"E\"]\n\n1. Nf3 *";
        byte[] bytes = pgn.getBytes(StandardCharsets.UTF_8);
        List<RawGame> games = new ArrayList<>();
        try (PgnSplitter s = new PgnSplitter(new ByteArrayInputStream(bytes))) {
            s.forEachRemaining(games::add);
        }
        assertEquals(5, games.size());
        for (RawGame g : games) {
            String fromOffset = new String(bytes, (int) g.offset(), g.bytes().length, StandardCharsets.UTF_8);
            assertEquals(g.text(), fromOffset);
        }
        assertTrue(games.get(0).text().contains("[bracket line]"));
        assertTrue(games.get(2).text().startsWith("[Event \"C\"]"));
        assertEquals(2, PgnParser.parseGame(games.get(0).text()).plyCount());
    }

    @Test
    void latin1Fallback() {
        byte[] latin1 = "[White \"Müller\"]\n\n1. e4 *".getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
        assertEquals("Müller", PgnText.readTags(PgnText.decode(latin1)).get("White"));
    }

    @Test
    void textualTagEdits() {
        String text = "[Event \"x\"]\n[White \"a\"]\n\n1. e4 {keep   spacing} *";
        String edited = PgnText.setTag(text, "White", "b \"q\"");
        assertEquals("[Event \"x\"]\n[White \"b \\\"q\\\"\"]\n\n1. e4 {keep   spacing} *", edited);
        String added = PgnText.setTag(text, "OrionId", "7");
        assertEquals("[Event \"x\"]\n[White \"a\"]\n[OrionId \"7\"]\n\n1. e4 {keep   spacing} *", added);
        assertEquals("[Event \"x\"]\n\n1. e4 {keep   spacing} *", PgnText.setTag(text, "White", null));
    }

    @Test
    void editingOperations() throws Exception {
        Game g = Game.fromMoves("e4 e5 Nf3 Nc6");
        GameNode e5 = g.find("e4 e5");
        GameNode c5 = g.find("e4").play("c5");
        assertEquals(1, g.find("e4").variations().size());
        c5.promoteToMainline();
        assertEquals("1. e4 c5", g.mainlineSan());
        e5.promoteToMainline();
        c5.delete();
        assertFalse(g.hasVariations());
        g.find("e4 e5 Nf3").truncate();
        assertEquals(3, g.plyCount());
        GameNode n = g.end();
        n.setCommand("eval", "0.20");
        n.setComment(n.comment() + " nice");
        n.setCommand("eval", "0.25");
        assertEquals("[%eval 0.25] nice", n.comment());
        n.setCommand("eval", null);
        assertEquals("nice", n.comment());
        n.addNag(Nag.GOOD);
        n.addNag(Nag.MISTAKE);
        assertArrayEquals(new int[] {Nag.MISTAKE}, n.nags());
    }
}
