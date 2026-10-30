package com.oriondb.engine;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Annotates a game's mainline with one engine: evaluations ({@code [%eval]}),
 * move-quality NAGs and best-line variations, and returns per-move
 * assessments with accuracy statistics.
 *
 * <p>Each mainline position is analysed once: the evaluation after move N
 * is the evaluation before move N+1. Checkmate and stalemate positions are
 * scored without asking the engine. Moves are classified by the drop in the
 * mover's winning chances (lichess thresholds: 0.1 inaccuracy, 0.2 mistake,
 * 0.3 blunder), with lichess's special cases for allowing or missing a
 * forced mate. The engine's own choice is always {@link Classification#BEST}.
 */
public final class GameAnnotator {

    /** Receives progress: {@code done} of {@code total} positions analysed. */
    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    /**
     * What to analyse and which annotations to write.
     *
     * @param limit           search limit per position
     * @param white           assess White's moves
     * @param black           assess Black's moves
     * @param fromPly         first move to assess (1 = the game's first move)
     * @param toPly           last move to assess, inclusive
     * @param addEvalComments write {@code [%eval]} after each assessed move
     * @param addNags         write {@code ?!}, {@code ?}, {@code ??} for inaccuracies, mistakes and blunders
     * @param addVariations   add the engine's best line as a variation before mistakes and blunders
     * @param variationPlies  maximum length of an added variation
     * @param overwrite       replace existing evals and move NAGs (otherwise human annotations are kept)
     */
    public record Options(Limit limit, boolean white, boolean black, int fromPly, int toPly,
                          boolean addEvalComments, boolean addNags, boolean addVariations, int variationPlies,
                          boolean overwrite) {

        public Options {
            if (limit == null) throw new IllegalArgumentException("limit is required");
            if (fromPly < 1 || toPly < fromPly) throw new IllegalArgumentException("bad ply range " + fromPly + ".." + toPly);
            if (variationPlies < 1) throw new IllegalArgumentException("variationPlies must be >= 1");
        }

        /** Depth 14, both colors, whole game, evals + NAGs + 8-ply variations, no overwrite. */
        public static Options defaults() {
            return new Options(Limit.depth(14), true, true, 1, Integer.MAX_VALUE, true, true, true, 8, false);
        }

        public Options withLimit(Limit limit) {
            return new Options(limit, white, black, fromPly, toPly, addEvalComments, addNags, addVariations, variationPlies, overwrite);
        }

        public Options withColors(boolean white, boolean black) {
            return new Options(limit, white, black, fromPly, toPly, addEvalComments, addNags, addVariations, variationPlies, overwrite);
        }

        public Options withPlyRange(int fromPly, int toPly) {
            return new Options(limit, white, black, fromPly, toPly, addEvalComments, addNags, addVariations, variationPlies, overwrite);
        }

        public Options withEvalComments(boolean on) {
            return new Options(limit, white, black, fromPly, toPly, on, addNags, addVariations, variationPlies, overwrite);
        }

        public Options withNags(boolean on) {
            return new Options(limit, white, black, fromPly, toPly, addEvalComments, on, addVariations, variationPlies, overwrite);
        }

        public Options withVariations(boolean on, int maxPlies) {
            return new Options(limit, white, black, fromPly, toPly, addEvalComments, addNags, on, maxPlies, overwrite);
        }

        public Options withOverwrite(boolean on) {
            return new Options(limit, white, black, fromPly, toPly, addEvalComments, addNags, addVariations, variationPlies, on);
        }

        /** No annotations written: assessment only. */
        public Options reportOnly() {
            return new Options(limit, white, black, fromPly, toPly, false, false, false, variationPlies, overwrite);
        }
    }

    private final UciEngine engine;

    public GameAnnotator(UciEngine engine) {
        this.engine = engine;
    }

    public AnnotationReport annotate(Game game, Options options) throws EngineException {
        return annotate(game, options, null);
    }

    /**
     * Analyses and annotates {@code game} in place.
     *
     * @param progress optional callback, invoked after each analysed position
     */
    public AnnotationReport annotate(Game game, Options options, Progress progress) throws EngineException {
        List<GameNode> line = new ArrayList<>();
        line.add(game.root());
        line.addAll(game.mainline());
        int last = line.size() - 1;
        int[] moves = game.mainlineMoves();

        List<Integer> plies = new ArrayList<>();
        boolean[] need = new boolean[last + 1];
        for (int ply = options.fromPly(); ply <= Math.min(last, options.toPly()); ply++) {
            int mover = line.get(ply - 1).position().sideToMove();
            if (Move.isNull(moves[ply - 1]) || !(mover == 0 ? options.white() : options.black())) continue;
            plies.add(ply);
            need[ply - 1] = need[ply] = true;
        }
        int total = 0;
        for (boolean b : need) if (b) total++;

        boolean chess960 = game.isChess960();
        Analysis[] analyses = new Analysis[last + 1];
        if (total > 0) engine.newGame();
        int done = 0;
        for (int i = 0; i <= last; i++) {
            if (!need[i]) continue;
            List<Integer> history = Arrays.stream(moves, 0, i).boxed().toList();
            analyses[i] = engine.analyse(game.startPosition(), history, options.limit(), 1, chess960);
            if (progress != null) progress.update(++done, total);
        }

        List<MoveAssessment> out = new ArrayList<>();
        for (int ply : plies) {
            Analysis before = analyses[ply - 1], after = analyses[ply];
            if (before.score() == null || after.score() == null) continue;
            GameNode node = line.get(ply);
            Position prev = line.get(ply - 1).position();
            int color = prev.sideToMove();
            Score moverBefore = before.score();
            Score moverAfter = after.score().negate();
            int best = before.bestMove();
            Classification cls = classify(moverBefore, moverAfter, best != Move.NONE && best == moves[ply - 1]);
            int loss = Math.min(Score.MATE_CP, Math.max(0, moverBefore.clampedCentipawns() - moverAfter.clampedCentipawns()));
            double accuracy = moveAccuracy(moverBefore.winPercent(), moverAfter.winPercent());
            String bestSan = best == Move.NONE ? "" : San.format(prev, best);
            Score whiteBefore = moverBefore.forWhite(color), whiteAfter = moverAfter.forWhite(color);
            out.add(new MoveAssessment(ply, node.san(), color, whiteBefore, whiteAfter, bestSan, cls, loss, accuracy));
            write(node, line.get(ply - 1), cls, whiteAfter, before, options);
        }
        return AnnotationReport.of(out);
    }

    private static void write(GameNode node, GameNode parent, Classification cls, Score whiteAfter, Analysis before,
                              Options options) {
        if (options.addEvalComments() && !node.position().isCheckmate()
                && (options.overwrite() || node.command("eval") == null)) {
            node.setCommand("eval", whiteAfter.toPgnEval());
        }
        if (options.addNags() && cls.isError()
                && (options.overwrite() || Arrays.stream(node.nags()).noneMatch(Nag::isMoveAssessment))) {
            node.addNag(cls.nag());
        }
        PvLine pv = before.best();
        if (options.addVariations() && (cls == Classification.MISTAKE || cls == Classification.BLUNDER)
                && pv != null && pv.moves().length > 0) {
            int[] pvMoves = pv.moves();
            boolean exists = parent.children().stream().anyMatch(c -> c.move() == pvMoves[0]);
            if (!exists) {
                GameNode n = parent;
                for (int k = 0; k < Math.min(options.variationPlies(), pvMoves.length); k++) n = n.play(pvMoves[k]);
                Score white = pv.score().forWhite(parent.position().sideToMove());
                node.appendComment("Best: " + pv.san().get(0) + " (" + white + ")");
            }
        }
    }

    /**
     * Classifies a move from the mover's point of view.
     *
     * @param before     evaluation before the move (mover's view)
     * @param after      evaluation after the move (mover's view)
     * @param playedBest the move was the engine's first choice
     */
    static Classification classify(Score before, Score after, boolean playedBest) {
        if (playedBest) return Classification.BEST;
        boolean hadMate = before.isMate() && before.winningChances() > 0;
        boolean allowsMate = after.isMate() && after.winningChances() < 0;
        if (hadMate && allowsMate) return Classification.BLUNDER;
        if (hadMate && !after.isMate()) {
            // Lost a forced mate: how bad depends on what is left.
            int cp = after.centipawns();
            return cp > 999 ? Classification.INACCURACY : cp > 700 ? Classification.MISTAKE : Classification.BLUNDER;
        }
        if (!before.isMate() && allowsMate) {
            // Allowed a forced mate: less bad if the position was already lost.
            int cp = before.centipawns();
            return cp < -999 ? Classification.INACCURACY : cp < -700 ? Classification.MISTAKE : Classification.BLUNDER;
        }
        double drop = before.winningChances() - after.winningChances();
        if (drop >= 0.3) return Classification.BLUNDER;
        if (drop >= 0.2) return Classification.MISTAKE;
        if (drop >= 0.1) return Classification.INACCURACY;
        return Classification.GOOD;
    }

    /** Lichess move accuracy from win percentages (0..100) before and after, clamped to 0..100. */
    static double moveAccuracy(double winPercentBefore, double winPercentAfter) {
        double a = 103.1668 * Math.exp(-0.04354 * (winPercentBefore - winPercentAfter)) - 3.1669;
        return Math.max(0, Math.min(100, a));
    }
}
