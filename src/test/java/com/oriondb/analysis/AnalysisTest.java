package com.oriondb.analysis;

import com.oriondb.chess.Position;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {

    /** Motifs found on the last move of a line played from {@code fen}. */
    private static Set<Motif> lastMove(String fen, String moves) {
        Game g = Game.fromMoves(Position.fromFen(fen), moves);
        List<GameNode> line = g.mainline();
        Position[] pos = new Position[line.size() + 1];
        int[] mv = new int[line.size()];
        pos[0] = g.startPosition();
        for (int i = 0; i < mv.length; i++) {
            mv[i] = line.get(i).move();
            pos[i + 1] = line.get(i).position();
        }
        return MotifDetector.detect(pos, mv).stream().filter(h -> h.ply() == mv.length)
            .map(MotifDetector.Hit::motif).collect(Collectors.toSet());
    }

    @Test
    void forkPinSkewer() {
        assertTrue(lastMove("4k3/8/8/1q3r2/8/8/3N4/4K3 w - - 0 1", "Ne4").isEmpty());
        assertTrue(lastMove("4k3/8/8/1q3r2/8/8/4N3/4K3 w - - 0 1", "Nd4").contains(Motif.FORK));
        assertTrue(lastMove("4k3/8/2n5/8/8/8/8/4KB2 w - - 0 1", "Bb5").contains(Motif.PIN));
        assertTrue(lastMove("8/8/8/q2k4/8/8/8/6KR w - - 0 1", "Rh5+").contains(Motif.SKEWER));
    }

    @Test
    void checksAndMates() {
        assertTrue(lastMove("6rk/6pp/8/6N1/8/8/8/K7 w - - 0 1", "Nf7#").containsAll(Set.of(Motif.CHECKMATE, Motif.SMOTHERED_MATE)));
        assertTrue(lastMove("6k1/5ppp/8/8/8/8/8/K3R3 w - - 0 1", "Re8#").containsAll(Set.of(Motif.CHECKMATE, Motif.BACK_RANK_MATE)));
        assertTrue(lastMove("4k3/8/8/8/4B3/8/4R3/K7 w - - 0 1", "Bd5").contains(Motif.DISCOVERED_CHECK));
        assertTrue(lastMove("4k3/8/8/8/4N3/8/4R3/K7 w - - 0 1", "Nd6+").containsAll(Set.of(Motif.DOUBLE_CHECK, Motif.DISCOVERED_CHECK)));
        assertTrue(lastMove("7k/8/6K1/8/8/8/8/5Q2 w - - 0 1", "Qf7").contains(Motif.STALEMATE));
    }

    @Test
    void specialMovesAndSacrifice() {
        assertTrue(lastMove("4k3/1P6/8/8/8/8/8/4K3 w - - 0 1", "b8=N").containsAll(Set.of(Motif.PROMOTION, Motif.UNDERPROMOTION)));
        assertTrue(lastMove("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "exd6").contains(Motif.EN_PASSANT));
        // Greek gift: Bxh7+ Kxh7 and White stays a piece down for a while.
        Game g = Game.fromMoves(Position.fromFen("r1bq1rk1/pppn1ppp/4p3/3pP3/1b1P4/2NB1N2/PPP2PPP/R1BQK2R w KQ - 0 8"),
            "Bxh7+ Kxh7 Ng5+ Kg8 Qh5 Re8");
        List<GameNode> line = g.mainline();
        Position[] pos = new Position[line.size() + 1];
        int[] mv = new int[line.size()];
        pos[0] = g.startPosition();
        for (int i = 0; i < mv.length; i++) {
            mv[i] = line.get(i).move();
            pos[i + 1] = line.get(i).position();
        }
        assertTrue(MotifDetector.detect(pos, mv).contains(new MotifDetector.Hit(1, Motif.SACRIFICE)));
    }

    @Test
    void pawnStructures() {
        Position iqp = Position.fromFen("r1bq1rk1/pp2bppp/2n1pn2/8/3P4/2NB1N2/PP3PPP/R1BQ1RK1 w - - 0 10");
        assertTrue(PawnFeature.ISOLATED_QUEEN_PAWN.present(iqp, 0));
        assertFalse(PawnFeature.ISOLATED_QUEEN_PAWN.present(iqp, 1));
        Position carlsbad = Position.fromFen("r1bq1rk1/pp1nbppp/2p2n2/3p4/3P4/2NBPN2/PP3PPP/R2QK2R w KQ - 0 9");
        assertTrue(PawnFeature.CARLSBAD.present(carlsbad, 0));
        Position passer = Position.fromFen("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1");
        assertTrue(PawnFeature.PASSED_PAWN.present(passer, 0));
        assertEquals(List.of("passed_pawn(white)"), PawnFeature.describe(PawnFeature.mask(passer)));
    }
}
