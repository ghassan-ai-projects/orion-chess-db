package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oriondb.chess.Position;
import com.oriondb.db.GameRecord;
import com.oriondb.engine.Analysis;
import com.oriondb.engine.AnnotationReport;
import com.oriondb.engine.Classification;
import com.oriondb.engine.GameAnnotator;
import com.oriondb.engine.Limit;
import com.oriondb.engine.MoveAssessment;
import com.oriondb.engine.PvLine;
import com.oriondb.engine.Score;
import com.oriondb.engine.UciEngine;
import com.oriondb.game.Game;
import com.oriondb.pgn.PgnWriter;

import java.util.List;
import java.util.Locale;

/** analyze_position, annotate_game (UCI engine, started lazily). */
final class EngineTools {
    static final int MAX_DEPTH = 40, MAX_MOVETIME_MS = 30_000, DEFAULT_DEPTH_CAP_MS = 10_000;
    static final int ANNOTATE_BUDGET_MS = 120_000;

    private EngineTools() {}

    static List<Tool> tools(Context ctx) {
        return List.of(
            Tool.read("analyze_position", "Analyze position",
                "Engine (Stockfish/UCI) analysis of a position given by 'fen', 'moves' (SAN from the start or from 'fen'), "
                    + "or 'game_id' + 'ply'. Returns the best lines with scores (White's point of view: +1.00 = White a pawn "
                    + "up, #3 = White mates in 3) and principal variations in SAN. Defaults: depth 20 capped at 10 s; "
                    + "movetime_ms is capped at 30 s.",
                Inputs.positionSchema()
                    .integer("depth", "Search depth in plies (default 20).", 1, MAX_DEPTH)
                    .integer("movetime_ms", "Search time in milliseconds (max 30000).", 10, MAX_MOVETIME_MS)
                    .integer("multipv", "Number of best lines (default 3).", 1, 10),
                a -> analyze(ctx, a)),
            Tool.write("annotate_game", "Annotate game",
                "Run the engine over a game's mainline: per-side accuracy and average centipawn loss, inaccuracies/"
                    + "mistakes/blunders with the engine's better move, and annotations ([%eval] comments, ?!/?/?? NAGs, "
                    + "best-line variations). Preview by default; save: true writes the annotated game back. Each position "
                    + "gets at most movetime_ms (default 1000) and the whole run is capped at about 2 minutes.",
                Schema.object()
                    .integer("id", "Game id.", 1, Integer.MAX_VALUE)
                    .integer("depth", "Depth per position (default 14).", 1, MAX_DEPTH)
                    .integer("movetime_ms", "Maximum time per position in ms (default 1000; lowered automatically for long games).", 10, 10_000)
                    .bool("white", "Assess White's moves (default true).")
                    .bool("black", "Assess Black's moves (default true).")
                    .integer("from_ply", "First half-move to assess (default 1).", 1, 10000)
                    .integer("to_ply", "Last half-move to assess (default: end).", 1, 10000)
                    .bool("evals", "Write [%eval] comments (default true).")
                    .bool("nags", "Write ?!, ?, ?? NAGs (default true).")
                    .bool("variations", "Add the engine's line before mistakes and blunders (default true).")
                    .integer("variation_plies", "Maximum length of added variations (default 8).", 1, 40)
                    .bool("overwrite", "Replace existing evals and move NAGs (default false: human annotations are kept).")
                    .bool("save", "Write the annotated game back to the database (default false = preview).")
                    .bool("include_pgn", "Include the annotated PGN in the result (default: true when not saving).")
                    .required("id"),
                a -> annotate(ctx, a)));
    }

    // ------------------------------------------------------------ analyze_position

    private static ToolResult analyze(Context ctx, Args a) throws Exception {
        Inputs.PositionInput in = Inputs.position(a, ctx.snap());
        Position p = in.position();
        if (p.legalMoves().length == 0) {
            String what = p.isCheck() ? "checkmate: " + (p.sideToMove() == 0 ? "Black" : "White") + " has won" : "stalemate: draw";
            return ToolResult.of("Position: " + in.description() + "\nFEN: " + p.fen() + "\nNo legal moves (" + what + ").",
                Json.obj("fen", p.fen(), "terminal", p.isCheck() ? "checkmate" : "stalemate", "lines", new JsonArray()));
        }
        Limit limit = analyzeLimit(a);
        int multipv = Math.min(a.integer("multipv", 3, 1, 10), p.legalMoves().length);
        UciEngine engine = ctx.engine();
        long t0 = System.nanoTime();
        Analysis an = engine.analyse(in.start(), in.moves(), limit, multipv);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int stm = p.sideToMove();
        StringBuilder sb = new StringBuilder("Position: ").append(in.description()).append('\n');
        sb.append("FEN: ").append(p.fen()).append(" (").append(Format.sideName(stm)).append(" to move)\n");
        sb.append("Engine: ").append(engine.name()).append(", ").append(limit).append(", ").append(ms).append(" ms, ")
            .append(an.nodes()).append(" nodes. Scores from White's point of view.\n");
        JsonArray lines = new JsonArray();
        java.util.Set<Integer> firstMoves = new java.util.HashSet<>();
        int rank = 0;
        for (PvLine l : an.lines()) {
            // Lines reported at different depths can repeat a move when the search stops mid-iteration.
            if (l.moves().length > 0 && !firstMoves.add(l.firstMove())) continue;
            rank++;
            Score white = l.score().forWhite(stm);
            String pv = Format.numbered(p, l.san());
            sb.append(rank).append(". ").append(white).append("  (d").append(l.depth()).append(")  ").append(pv).append('\n');
            lines.add(Json.obj("rank", rank, "score", white.toString(), "score_side_to_move", l.score().toString(),
                "centipawns_white", white.isMate() ? null : white.centipawns(), "mate_white", white.isMate() ? white.mateIn() : null,
                "depth", l.depth(), "move", l.san().isEmpty() ? null : l.san().get(0), "pv", pv, "pv_san", l.san()));
        }
        return ToolResult.of(sb.toString().strip(), Json.obj("fen", p.fen(), "side_to_move", Format.sideName(stm),
            "engine", engine.name(), "limit", limit.toString(), "time_ms", ms, "nodes", an.nodes(), "lines", lines));
    }

    static Limit analyzeLimit(Args a) {
        Integer depth = a.has("depth") ? a.integer("depth", 20, 1, MAX_DEPTH) : null;
        Integer movetime = a.has("movetime_ms") ? a.integer("movetime_ms", 1000, 10, MAX_MOVETIME_MS) : null;
        if (depth == null && movetime == null) return new Limit(20, DEFAULT_DEPTH_CAP_MS, 0);
        if (movetime == null) return new Limit(depth, DEFAULT_DEPTH_CAP_MS, 0);
        return new Limit(depth == null ? 0 : depth, movetime, 0);
    }

    // ------------------------------------------------------------ annotate_game

    private static ToolResult annotate(Context ctx, Args a) throws Exception {
        int id = a.requireInt("id");
        GameRecord r = Inputs.game(ctx.snap(), id);
        Game game = ctx.snap().game(id);
        int plies = game.plyCount();
        if (plies == 0) throw new ToolException("game " + id + " has no moves to annotate");
        int from = a.integer("from_ply", 1, 1, 10000), to = Math.min(a.integer("to_ply", plies, 1, 10000), plies);
        if (from > to) throw new ToolException("from_ply (" + from + ") is after to_ply/the last ply (" + to + ")");
        int positions = to - from + 2;
        int perPosition = Math.max(20, Math.min(a.integer("movetime_ms", 1000, 10, 10_000), ANNOTATE_BUDGET_MS / positions));
        Limit limit = new Limit(a.integer("depth", 14, 1, MAX_DEPTH), perPosition, 0);
        GameAnnotator.Options opts = new GameAnnotator.Options(limit, a.bool("white", true), a.bool("black", true), from, to,
            a.bool("evals", true), a.bool("nags", true), a.bool("variations", true), a.integer("variation_plies", 8, 1, 40),
            a.bool("overwrite", false));
        if (!opts.white() && !opts.black()) throw new ToolException("nothing to assess: white and black are both false");

        UciEngine engine = ctx.engine();
        long t0 = System.nanoTime();
        AnnotationReport report = new GameAnnotator(engine).annotate(game, opts);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        boolean save = a.bool("save", false);
        if (save) ctx.mutate(b -> {
            b.replace(id, game);
            return null;
        });

        StringBuilder sb = new StringBuilder("Annotated game #").append(id).append(": ").append(Format.rowText(r, -1)).append('\n');
        sb.append("Engine: ").append(engine.name()).append(", ").append(limit).append(" per position, ").append(ms / 1000.0).append(" s\n");
        sb.append(report.white()).append('\n').append(report.black()).append('\n');
        JsonArray errors = new JsonArray(), all = new JsonArray();
        for (MoveAssessment m : report.moves()) {
            JsonObject o = Json.obj("ply", m.ply(), "move", label(m), "color", Format.sideName(m.color()),
                "class", m.classification().name().toLowerCase(Locale.ROOT), "eval_before", m.scoreBefore().toString(),
                "eval_after", m.scoreAfter().toString(), "best", m.bestMoveSan().isEmpty() ? null : m.bestMoveSan(),
                "cp_loss", m.centipawnLoss(), "accuracy", Format.round1(m.accuracy()));
            all.add(o);
            if (m.classification().isError()) errors.add(o);
        }
        if (!errors.isEmpty()) {
            sb.append("Errors:\n");
            for (MoveAssessment m : report.moves()) {
                if (!m.classification().isError()) continue;
                sb.append("  ").append(label(m)).append(' ').append(symbol(m.classification())).append("  ")
                    .append(m.scoreBefore()).append(" -> ").append(m.scoreAfter()).append(", best ").append(m.bestMoveSan())
                    .append(" (").append(m.classification().name().toLowerCase(Locale.ROOT)).append(")\n");
            }
        } else {
            sb.append("No inaccuracies, mistakes or blunders found.\n");
        }
        sb.append(save ? "Saved the annotations to game #" + id + "." : "Preview only: pass save: true to write the annotations.");
        String pgn = PgnWriter.write(game).strip();
        if (a.bool("include_pgn", !save)) sb.append("\n\nAnnotated PGN:\n").append(pgn);

        JsonObject json = Json.obj("id", id, "saved", save, "engine", engine.name(), "limit", limit.toString(), "time_ms", ms,
            "white", side(report.white()), "black", side(report.black()), "errors", errors, "moves", all);
        if (a.bool("include_pgn", !save)) json.addProperty("pgn", pgn);
        return ToolResult.of(sb.toString(), json);
    }

    private static String label(MoveAssessment m) {
        return m.moveNumber() + (m.color() == 0 ? ". " : "... ") + m.san();
    }

    private static String symbol(Classification c) {
        return switch (c) {
            case INACCURACY -> "?!";
            case MISTAKE -> "?";
            case BLUNDER -> "??";
            default -> "";
        };
    }

    private static JsonObject side(AnnotationReport.SideStats s) {
        return Json.obj("moves", s.moves(), "accuracy", Format.round1(s.accuracy()), "acpl", Format.round1(s.averageCentipawnLoss()),
            "inaccuracies", s.inaccuracies(), "mistakes", s.mistakes(), "blunders", s.blunders());
    }
}
