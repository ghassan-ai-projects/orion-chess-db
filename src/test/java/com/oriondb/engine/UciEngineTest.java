package com.oriondb.engine;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.chess.Uci;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UciEngineTest {
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

    @Test
    void handshake() {
        assertFalse(engine.name().isEmpty());
        assertTrue(engine.hasOption("Hash"));
        assertTrue(engine.isAlive());
    }

    @Test
    void findsMateInOne() throws Exception {
        Position p = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1");
        Analysis a = engine.analyse(p, Limit.depth(8), 1);
        assertEquals("Ra8#", San.format(p, a.bestMove()));
        assertEquals(Score.mate(1), a.score());
        assertEquals(List.of("Ra8#"), a.best().san());
        assertEquals(1, a.lines().size());
    }

    @Test
    void multiPvReturnsDistinctLines() throws Exception {
        Analysis a = engine.analyse(Position.start(), Limit.depth(10), 3);
        assertEquals(3, a.lines().size());
        Set<Integer> first = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            PvLine l = a.lines().get(i);
            assertEquals(i + 1, l.multipv());
            assertTrue(l.moves().length > 0);
            assertEquals(l.moves().length, l.san().size());
            first.add(l.firstMove());
        }
        assertEquals(3, first.size());
        assertEquals(a.bestMove(), a.lines().get(0).firstMove());
        assertTrue(a.lines().get(0).score().compareTo(a.lines().get(2).score()) >= 0);
        // Back to a single line afterwards
        assertEquals(1, engine.analyse(Position.start(), Limit.depth(6), 1).lines().size());
    }

    @Test
    void analysesAfterMoveHistory() throws Exception {
        Position start = Position.start();
        int e4 = San.parse(start, "e4");
        int e5 = San.parse(start.play(e4), "e5");
        Analysis a = engine.analyse(start, List.of(e4, e5), Limit.movetime(50), 1);
        assertEquals(start.play(e4).play(e5), a.position());
        assertEquals(0, a.position().sideToMove());
        assertNotEquals(Move.NONE, a.bestMove());
        assertTrue(a.position().isLegal(a.bestMove()));
    }

    @Test
    void terminalPositionsSkipTheEngine() throws Exception {
        Position mated = Position.fromFen("R5k1/5ppp/8/8/8/8/8/6K1 b - - 1 1");
        Analysis a = engine.analyse(mated, Limit.depth(10), 1);
        assertEquals(Score.mated(), a.score());
        assertEquals(Move.NONE, a.bestMove());
        Position stalemate = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        assertEquals(Score.cp(0), engine.analyse(stalemate, Limit.depth(10), 1).score());
    }

    @Test
    void chess960() throws Exception {
        // Castling rights with rooks on b- and g-files, king on f-file.
        Position p = Position.fromFen("1r3kr1/pppppppp/8/8/8/8/PPPPPPPP/1R3KR1 w GBgb - 0 1");
        assertTrue(p.hasChess960Castling());
        Analysis a = engine.analyse(p, Limit.depth(8), 2);
        assertEquals(2, a.lines().size());
        assertTrue(p.isLegal(a.bestMove()));
        // History with a 960 castling move is sent king-takes-rook
        Position q = Position.fromFen("1r3kr1/pppppppp/8/8/8/8/PPPPPP1P/1R3KR1 w GBgb - 0 1");
        int castle = San.parse(q, "O-O");
        assertTrue(Move.isCastle(castle));
        Analysis b = engine.analyse(q, List.of(castle), Limit.movetime(50), 1, true);
        assertEquals(q.play(castle), b.position());
        assertTrue(b.position().isLegal(b.bestMove()));
        // Standard chess still works after switching back
        Position s = Position.start();
        Analysis c = engine.analyse(s, List.of(Uci.parse(s, "e2e4")), Limit.depth(6), 1);
        assertTrue(c.position().isLegal(c.bestMove()));
    }

    @Test
    void deadEngineFailsCleanly() throws Exception {
        UciEngine e = new UciEngine(EngineConfig.detect().get().withThreads(1).withHash(16));
        e.close();
        assertFalse(e.isAlive());
        EngineException ex = assertThrows(EngineException.class, () -> e.analyse(Position.start(), Limit.depth(5), 1));
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    void notAnEngine() {
        // A program that exits immediately instead of answering "uciok"
        EngineConfig bogus = new EngineConfig(Path.of("/usr/bin/true"), 1, 16, Map.of());
        assumeTrue(bogus.executable().toFile().canExecute());
        EngineException ex = assertThrows(EngineException.class, () -> new UciEngine(bogus));
        assertTrue(ex.getMessage().contains("exited") || ex.getMessage().contains("timed out"), ex.getMessage());
        assertThrows(EngineException.class, () -> new UciEngine(EngineConfig.of(Path.of("/nonexistent/engine"))));
    }
}
