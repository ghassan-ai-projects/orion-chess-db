package com.oriondb.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoreTest {

    @Test
    void formatting() {
        assertEquals("+0.35", Score.cp(35).toString());
        assertEquals("-1.20", Score.cp(-120).toString());
        assertEquals("0.00", Score.cp(0).toString());
        assertEquals("#3", Score.mate(3).toString());
        assertEquals("#-3", Score.mate(-3).toString());
        assertEquals("0.35", Score.cp(35).toPgnEval());
        assertEquals("-12.05", Score.cp(-1205).toPgnEval());
        assertEquals("#-3", Score.mate(-3).toPgnEval());
    }

    @Test
    void parsing() {
        assertEquals(Score.cp(35), Score.parse("0.35"));
        assertEquals(Score.cp(35), Score.parse("+0.35"));
        assertEquals(Score.cp(-120), Score.parse("-1.2"));
        assertEquals(Score.mate(3), Score.parse("#3"));
        assertEquals(Score.mate(3), Score.parse("#+3"));
        assertEquals(Score.mate(-3), Score.parse("#-3"));
        assertEquals(Score.mated(), Score.parse("#-0"));
        assertEquals(Score.mating(), Score.parse("#0"));
        for (String s : List.of("0.35", "-1.20", "#4", "#-2")) assertEquals(s, Score.parse(s).toPgnEval());
        assertThrows(IllegalArgumentException.class, () -> Score.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Score.parse("#"));
    }

    @Test
    void pointOfView() {
        assertEquals(Score.cp(35), Score.cp(35).forWhite(0));
        assertEquals(Score.cp(-35), Score.cp(35).forWhite(1));
        assertEquals(Score.mate(-2), Score.mate(2).forWhite(1));
        assertEquals(Score.mating(), Score.mated().forWhite(1));
        assertEquals(Score.mated(), Score.mated().negate().negate());
        assertEquals(Score.mated(), Score.mate(0));
    }

    @Test
    void winningChances() {
        assertEquals(0, Score.cp(0).winningChances(), 1e-12);
        assertEquals(2 / (1 + Math.exp(-0.00368208 * 100)) - 1, Score.cp(100).winningChances(), 1e-12);
        assertEquals(-Score.cp(250).winningChances(), Score.cp(-250).winningChances(), 1e-12);
        // Clamped at +-1000 centipawns
        assertEquals(Score.cp(1000).winningChances(), Score.cp(5000).winningChances(), 1e-12);
        assertEquals(1, Score.mate(7).winningChances());
        assertEquals(-1, Score.mate(-1).winningChances());
        assertEquals(-1, Score.mated().winningChances());
        assertEquals(1, Score.mating().winningChances());
        assertEquals(50, Score.cp(0).winPercent(), 1e-12);
    }

    @Test
    void ordering() {
        List<Score> ordered = List.of(Score.mated(), Score.mate(-1), Score.mate(-5), Score.cp(-300), Score.cp(0),
            Score.cp(80), Score.mate(9), Score.mate(2), Score.mating());
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i - 1).compareTo(ordered.get(i)) < 0, ordered.get(i - 1) + " < " + ordered.get(i));
        }
    }

    @Test
    void limits() {
        assertEquals("go depth 12", Limit.depth(12).goCommand());
        assertEquals("go movetime 50", Limit.movetime(50).goCommand());
        assertEquals("go depth 10 nodes 5000", Limit.depth(10).withNodes(5000).goCommand());
        assertThrows(IllegalArgumentException.class, () -> new Limit(0, 0, 0));
    }

    @Test
    void infoLineParsing() {
        UciEngine.Info i = UciEngine.Info.parse(
            "info depth 18 seldepth 25 multipv 2 score cp -31 wdl 20 900 80 nodes 123456 nps 999 hashfull 3 tbhits 0 time 250 pv e7e5 g1f3 b8c6");
        assertNotNull(i);
        assertEquals(18, i.depth());
        assertEquals(25, i.seldepth());
        assertEquals(2, i.multipv());
        assertEquals(Score.cp(-31), i.score());
        assertFalse(i.bound());
        assertEquals(123456, i.nodes());
        assertEquals(250, i.timeMs());
        assertEquals(List.of("e7e5", "g1f3", "b8c6"), i.pv());

        UciEngine.Info m = UciEngine.Info.parse("info depth 5 score mate -2 upperbound nodes 10 pv a1a2");
        assertEquals(Score.mate(-2), m.score());
        assertTrue(m.bound());
        assertEquals(1, m.multipv());

        assertEquals(Score.mated(), UciEngine.Info.parse("info depth 0 score mate 0").score());
        assertNull(UciEngine.Info.parse("info string NNUE evaluation using nn.nnue"));
        assertNull(UciEngine.Info.parse("info depth x score cp"));
        assertNull(UciEngine.Info.parse("info score cp"));
    }

    @Test
    void classification() {
        assertEquals(Classification.BEST, GameAnnotator.classify(Score.cp(50), Score.cp(-900), true));
        assertEquals(Classification.GOOD, GameAnnotator.classify(Score.cp(50), Score.cp(30), false));
        assertEquals(Classification.INACCURACY, GameAnnotator.classify(Score.cp(50), Score.cp(-25), false));
        assertEquals(Classification.MISTAKE, GameAnnotator.classify(Score.cp(50), Score.cp(-80), false));
        assertEquals(Classification.BLUNDER, GameAnnotator.classify(Score.cp(50), Score.cp(-300), false));
        // Allowing mate: blunder unless already lost
        assertEquals(Classification.BLUNDER, GameAnnotator.classify(Score.cp(-100), Score.mate(-1), false));
        assertEquals(Classification.MISTAKE, GameAnnotator.classify(Score.cp(-800), Score.mate(-4), false));
        assertEquals(Classification.INACCURACY, GameAnnotator.classify(Score.cp(-1500), Score.mate(-4), false));
        // Missing a mate
        assertEquals(Classification.BLUNDER, GameAnnotator.classify(Score.mate(2), Score.cp(150), false));
        assertEquals(Classification.INACCURACY, GameAnnotator.classify(Score.mate(2), Score.cp(1500), false));
        assertEquals(Classification.BLUNDER, GameAnnotator.classify(Score.mate(2), Score.mate(-3), false));
        // Slower mate, or delivering mate, is fine
        assertEquals(Classification.GOOD, GameAnnotator.classify(Score.mate(2), Score.mate(4), false));
        assertEquals(Classification.GOOD, GameAnnotator.classify(Score.mate(1), Score.mating(), false));
        // Already losing: no change in winning chances
        assertEquals(Classification.GOOD, GameAnnotator.classify(Score.mate(-3), Score.mate(-2), false));
    }

    @Test
    void accuracyFormula() {
        assertEquals(100, GameAnnotator.moveAccuracy(50, 50), 0.01);
        assertEquals(100, GameAnnotator.moveAccuracy(40, 60), 0.0);
        assertEquals(0, GameAnnotator.moveAccuracy(100, 0), 0.0);
        double a = GameAnnotator.moveAccuracy(60, 50);
        assertEquals(103.1668 * Math.exp(-0.4354) - 3.1669, a, 1e-9);
    }
}
