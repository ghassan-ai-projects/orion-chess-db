package com.oriondb.engine;

import com.oriondb.chess.Position;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GameAnnotatorTest {
    private static UciEngine engine;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(EngineConfig.detect().isPresent(), "no UCI engine installed");
        engine = new UciEngine(EngineConfig.detect().get().withThreads(1).withHash(16));
    }

    @AfterAll
    static void stop() {
        if (engine != null) engine.close();
    }

    private static GameAnnotator.Options fast() {
        return GameAnnotator.Options.defaults().withLimit(Limit.depth(10));
    }

    @Test
    void scholarsMateBlunder() throws Exception {
        Game g = Game.fromMoves("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#");
        List<int[]> progress = new ArrayList<>();
        AnnotationReport r = new GameAnnotator(engine).annotate(g, fast(), (done, total) -> progress.add(new int[] {done, total}));

        assertEquals(7, r.moves().size());
        // 8 positions (start .. final mate); the mate itself needs no engine but is still counted
        assertEquals(8, progress.size());
        assertArrayEquals(new int[] {8, 8}, progress.get(progress.size() - 1));

        MoveAssessment nf6 = r.moves().get(5);
        assertEquals("Nf6", nf6.san());
        assertEquals(6, nf6.ply());
        assertEquals(1, nf6.color());
        assertEquals(Classification.BLUNDER, nf6.classification());
        assertEquals(Score.mate(1), nf6.scoreAfter());
        assertFalse(nf6.bestMoveSan().isEmpty());

        MoveAssessment qxf7 = r.moves().get(6);
        assertEquals("Qxf7#", qxf7.san());
        assertEquals(Classification.BEST, qxf7.classification());
        assertEquals(Score.mating(), qxf7.scoreAfter());

        GameNode nf6Node = g.nodeAtPly(6);
        assertTrue(nf6Node.hasNag(Nag.BLUNDER));
        assertEquals("#1", nf6Node.command("eval"));
        assertTrue(nf6Node.plainComment().startsWith("Best: "), nf6Node.comment());
        assertEquals(1, nf6Node.parent().variations().size());
        assertEquals(nf6.bestMoveSan(), nf6Node.parent().variations().get(0).san());
        assertNull(g.nodeAtPly(7).command("eval"), "no eval on the mated position");
        assertNotNull(g.nodeAtPly(1).command("eval"));

        assertEquals(1, r.black().blunders());
        assertTrue(r.white().accuracy() > r.black().accuracy(), r.white() + " / " + r.black());
        assertTrue(r.black().averageCentipawnLoss() > r.white().averageCentipawnLoss());
        assertEquals(4, r.white().moves());
        assertEquals(3, r.black().moves());
    }

    @Test
    void keepsHumanAnnotationsUnlessOverwrite() throws Exception {
        Game g = Game.fromMoves("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#");
        GameNode nf6 = g.nodeAtPly(6);
        nf6.addNag(Nag.DUBIOUS);
        nf6.setCommand("eval", "0.00");
        GameAnnotator.Options opts = fast().withPlyRange(6, 6).withVariations(false, 4);
        AnnotationReport r = new GameAnnotator(engine).annotate(g, opts);
        assertEquals(1, r.moves().size());
        assertTrue(nf6.hasNag(Nag.DUBIOUS));
        assertFalse(nf6.hasNag(Nag.BLUNDER));
        assertEquals("0.00", nf6.command("eval"));
        assertNull(g.nodeAtPly(5).command("eval"), "outside the ply range");

        new GameAnnotator(engine).annotate(g, opts.withOverwrite(true));
        assertTrue(nf6.hasNag(Nag.BLUNDER));
        assertFalse(nf6.hasNag(Nag.DUBIOUS));
        assertEquals("#1", nf6.command("eval"));
        assertTrue(nf6.parent().variations().isEmpty());
    }

    @Test
    void colorFilterAndReportOnly() throws Exception {
        Game g = Game.fromMoves("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#");
        AnnotationReport r = new GameAnnotator(engine).annotate(g,
            fast().withColors(false, true).reportOnly().withLimit(Limit.movetime(50)));
        assertEquals(3, r.moves().size());
        assertTrue(r.moves().stream().allMatch(m -> m.color() == 1));
        assertEquals(0, r.white().moves());
        assertFalse(g.hasComments() || g.hasNags() || g.hasVariations());
        assertNull(g.nodeAtPly(6).comment());
    }

    @Test
    void chess960Game() throws Exception {
        Position start = Position.fromFen("1r3kr1/pppppppp/8/8/8/8/PPPPPP1P/1R3KR1 w GBgb - 0 1");
        Game g = Game.fromMoves(start, "O-O d5 h3");
        assertTrue(g.isChess960());
        AnnotationReport r = new GameAnnotator(engine).annotate(g, fast().withLimit(Limit.depth(8)));
        assertEquals(3, r.moves().size());
        assertEquals("O-O", r.moves().get(0).san());
    }
}
