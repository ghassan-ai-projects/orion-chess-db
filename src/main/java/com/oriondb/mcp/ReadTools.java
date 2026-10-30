package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oriondb.analysis.Motif;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.eco.EcoBook;
import com.oriondb.stats.DatabaseStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** db_stats, get_game, show_position, list_labels. */
final class ReadTools {
    private ReadTools() {}

    static List<Tool> tools(Context ctx) {
        return List.of(
            Tool.read("db_stats", "Database overview",
                "Overview of the database: file, game count, date range, results, average rating and length, annotated "
                    + "games, top players/events/openings, labels, and games that failed to parse (with reasons).",
                Schema.object().integer("top", "How many top players/events/openings to list (default 10).", 1, 100),
                a -> stats(ctx, a)),
            Tool.read("get_game", "Get game",
                "One game by id: headers, labels, opening, mainline in numbered SAN, detected tactical motifs (with move), "
                    + "final FEN, annotation flags and the full PGN (with comments and variations). Give 'ply' to also get "
                    + "the board and FEN after that many half-moves of the mainline.",
                Schema.object()
                    .integer("id", "Game id.", 1, Integer.MAX_VALUE)
                    .integer("ply", "Also show the position after this many mainline half-moves (0 = start).", 0, 10000)
                    .bool("include_pgn", "Include the PGN text (default true).")
                    .required("id"),
                a -> game(ctx, a)),
            Tool.read("show_position", "Show position",
                "Board diagram, FEN, side to move, legal moves (SAN), check/checkmate/stalemate, the book opening name, "
                    + "and how many games in the database reach the position. Give 'fen', or 'moves' (SAN from the start or "
                    + "from 'fen'), or 'game_id' + 'ply'. With nothing given, shows the start position.",
                Inputs.positionSchema().bool("flip", "Show the board from Black's side."),
                a -> position(ctx, a)),
            Tool.read("list_labels", "List labels",
                "All labels (user-defined game categories) with the number of games carrying each.",
                Schema.object(), a -> labels(ctx)));
    }

    private static ToolResult stats(Context ctx, Args a) {
        Snapshot snap = ctx.snap();
        DatabaseStats.Stats s = DatabaseStats.compute(snap, a.integer("top", 10, 1, 100));
        StringBuilder sb = new StringBuilder();
        String path = ctx.db().path() == null ? "(in memory)" : ctx.db().path().toAbsolutePath().toString();
        sb.append("Database: ").append(path).append(ctx.db().isReadOnly() ? " (read-only)" : "").append('\n');
        sb.append("Games: ").append(s.games()).append(s.invalid() > 0 ? " (" + s.invalid() + " unparseable)" : "")
            .append(", indexed positions: ").append(s.indexedPositions()).append('\n');
        if (s.firstYear() > 0) sb.append("Years: ").append(s.firstYear()).append("-").append(s.lastYear()).append('\n');
        sb.append("Results: 1-0 ").append(s.whiteWins()).append(", draws ").append(s.draws()).append(", 0-1 ")
            .append(s.blackWins()).append(", unfinished ").append(s.unfinished()).append('\n');
        sb.append("Average length: ").append(s.averagePlies()).append(" plies");
        if (s.averageElo() > 0) sb.append(", average rating: ").append(s.averageElo());
        sb.append('\n').append("Annotated: ").append(s.annotated()).append(", with evals: ").append(s.withEvals())
            .append(", Chess960: ").append(s.chess960()).append('\n');
        appendCounts(sb, "Top players", s.players());
        appendCounts(sb, "Top events", s.events());
        appendCounts(sb, "Top openings", s.openings());
        appendCounts(sb, "Labels", s.labels());
        List<GameRecord> invalid = ctx.db().invalidGames();
        JsonArray bad = new JsonArray();
        if (!invalid.isEmpty()) {
            sb.append("Unparseable games (kept verbatim in the file):\n");
            for (GameRecord r : invalid.subList(0, Math.min(5, invalid.size()))) {
                sb.append("  #").append(r.id()).append(' ').append(r.white()).append(" - ").append(r.black())
                    .append(": ").append(r.error()).append('\n');
                bad.add(Json.obj("id", r.id(), "white", r.white(), "black", r.black(), "error", r.error()));
            }
            if (invalid.size() > 5) sb.append("  ... and ").append(invalid.size() - 5).append(" more\n");
        }
        JsonObject json = Json.of(s).getAsJsonObject();
        json.addProperty("path", path);
        json.add("invalid_games", bad);
        return ToolResult.of(sb.toString().strip(), json);
    }

    private static void appendCounts(StringBuilder sb, String title, List<DatabaseStats.Count> counts) {
        if (counts.isEmpty()) return;
        sb.append(title).append(": ")
            .append(String.join(", ", counts.stream().map(c -> c.name() + " (" + c.games() + ")").toList())).append('\n');
    }

    // ------------------------------------------------------------ get_game

    private static ToolResult game(Context ctx, Args a) {
        Snapshot snap = ctx.snap();
        int id = a.requireInt("id");
        GameRecord r = snap.find(id);
        if (r == null) throw new ToolException("no game with id " + id + " (use search_games to find ids)");
        boolean includePgn = a.bool("include_pgn", true);
        if (!r.isValid()) {
            String text = "Game #" + id + " could not be parsed: " + r.error() + "\nIt is kept verbatim in the file:\n\n" + snap.pgn(id);
            return ToolResult.of(text, Json.obj("id", id, "valid", false, "error", r.error(), "pgn", snap.pgn(id)));
        }
        Position[] positions = r.positions();
        int[] moves = r.mainline();
        StringBuilder sb = new StringBuilder("Game #").append(id).append('\n');
        for (Map.Entry<String, String> e : r.tags().entrySet()) {
            sb.append('[').append(e.getKey()).append(" \"").append(e.getValue()).append("\"]\n");
        }
        if (!r.labels().isEmpty()) sb.append("Labels: ").append(String.join(", ", r.labels())).append('\n');
        if (r.eco() != null || r.opening() != null) {
            sb.append("Opening: ").append(r.eco() == null ? "" : r.eco() + " ").append(r.opening() == null ? "" : r.opening()).append('\n');
        }
        String mainline = Format.numberedMoves(r.startPosition(), moves, moves.length);
        sb.append("Mainline (").append(r.plyCount()).append(" plies): ").append(mainline.isEmpty() ? "(no moves)" : mainline + " " + r.resultText()).append('\n');

        JsonArray motifs = new JsonArray();
        List<String> motifLines = new ArrayList<>();
        Map<Integer, List<String>> byPly = new java.util.TreeMap<>();
        for (int[] hit : r.motifHits()) byPly.computeIfAbsent(hit[0], k -> new ArrayList<>()).add(Motif.values()[hit[1]].key());
        for (Map.Entry<Integer, List<String>> e : byPly.entrySet()) {
            int ply = e.getKey();
            String move = ply >= 1 && ply <= moves.length ? Format.moveLabel(positions[ply - 1], moves[ply - 1]) : "";
            motifs.add(Json.obj("ply", ply, "move", move, "motifs", e.getValue()));
            motifLines.add(move + " " + String.join("+", e.getValue()));
        }
        if (!motifLines.isEmpty()) sb.append("Motifs: ").append(String.join("; ", motifLines)).append('\n');

        List<String> flags = flags(r);
        if (!flags.isEmpty()) sb.append("Flags: ").append(String.join(", ", flags)).append('\n');
        Position end = positions[positions.length - 1];
        sb.append("Final FEN: ").append(end.fen()).append('\n');

        JsonObject json = Json.obj("id", id, "valid", true, "tags", r.tags(), "labels", r.labels(), "eco", r.eco(),
            "opening", r.opening(), "result", r.resultText(), "plies", r.plyCount(), "mainline", mainline,
            "motifs", motifs, "flags", flags, "final_fen", end.fen());
        if (a.has("ply")) {
            int ply = a.integer("ply", 0, 0, 10000);
            if (ply > r.plyCount()) throw new ToolException("game " + id + " has only " + r.plyCount() + " plies (asked for ply " + ply + ")");
            Position p = positions[ply];
            sb.append("\nPosition after ply ").append(ply)
                .append(ply > 0 ? " (" + Format.moveLabel(positions[ply - 1], moves[ply - 1]) + ")" : " (start)")
                .append(", ").append(Format.sideName(p.sideToMove())).append(" to move:\n")
                .append(p.diagram(false)).append("\nFEN: ").append(p.fen()).append('\n');
            json.add("position", Json.obj("ply", ply, "fen", p.fen(), "diagram", p.diagram(false),
                "side_to_move", Format.sideName(p.sideToMove())));
        }
        if (includePgn) {
            String pgn = snap.pgn(id).strip();
            sb.append("\nPGN:\n").append(pgn);
            json.addProperty("pgn", pgn);
        }
        return ToolResult.of(sb.toString().strip(), json);
    }

    static List<String> flags(GameRecord r) {
        List<String> out = new ArrayList<>();
        if (r.has(GameRecord.HAS_COMMENTS)) out.add("comments");
        if (r.has(GameRecord.HAS_VARIATIONS)) out.add("variations");
        if (r.has(GameRecord.HAS_NAGS)) out.add("nags");
        if (r.has(GameRecord.HAS_EVALS)) out.add("evals");
        if (r.has(GameRecord.HAS_CLOCKS)) out.add("clocks");
        if (r.has(GameRecord.SETUP)) out.add("setup_position");
        if (r.has(GameRecord.CHESS960)) out.add("chess960");
        return out;
    }

    // ------------------------------------------------------------ show_position

    private static ToolResult position(Context ctx, Args a) {
        Snapshot snap = ctx.snap();
        Inputs.PositionInput in = Inputs.position(a, snap);
        Position p = in.position();
        List<String> legal = new ArrayList<>();
        for (int m : p.legalMoves()) legal.add(San.format(p, m));
        legal.sort(null);
        String status = p.isCheckmate() ? "checkmate" : p.isStalemate() ? "stalemate" : p.isCheck() ? "check"
            : p.isInsufficientMaterial() ? "insufficient material" : "normal";
        EcoBook.Opening opening = EcoBook.get().lookup(p);
        if (opening == null && !in.moves().isEmpty()) {
            Position[] line = new Position[in.moves().size() + 1];
            line[0] = in.start();
            for (int i = 0; i < in.moves().size(); i++) line[i + 1] = line[i].play(in.moves().get(i));
            opening = EcoBook.get().classify(line);
        }
        long games = snap.positionHits(p).stream().map(h -> h.gameId()).distinct().count();
        boolean flip = a.bool("flip", false);
        StringBuilder sb = new StringBuilder();
        sb.append("Position: ").append(in.description()).append('\n');
        sb.append(p.diagram(flip)).append('\n');
        sb.append("FEN: ").append(p.fen()).append('\n');
        sb.append(p.sideToMove() == 0 ? "White" : "Black").append(" to move");
        if (!status.equals("normal")) sb.append(" — ").append(status);
        sb.append('\n');
        sb.append("Legal moves (").append(legal.size()).append("): ").append(String.join(" ", legal)).append('\n');
        if (opening != null) sb.append("Opening: ").append(opening.eco()).append(' ').append(opening.name()).append('\n');
        sb.append("Games in database reaching this position: ").append(games);
        JsonObject json = Json.obj("fen", p.fen(), "side_to_move", Format.sideName(p.sideToMove()), "status", status,
            "legal_moves", legal, "diagram", p.diagram(flip), "games_in_db", games,
            "opening", opening == null ? null : Json.obj("eco", opening.eco(), "name", opening.name()));
        return ToolResult.of(sb.toString(), json);
    }

    // ------------------------------------------------------------ labels

    private static ToolResult labels(Context ctx) {
        Map<String, Integer> labels = DatabaseStats.labels(ctx.snap());
        if (labels.isEmpty()) return ToolResult.of("No labels yet. Add some with label_games.", Json.obj("labels", labels));
        StringBuilder sb = new StringBuilder(labels.size() + " labels:");
        labels.forEach((k, v) -> sb.append("\n  ").append(k).append(": ").append(v));
        return ToolResult.of(sb.toString(), Json.obj("labels", labels));
    }
}
