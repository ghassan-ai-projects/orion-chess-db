package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.query.Query;
import com.oriondb.query.Searcher;
import com.oriondb.stats.HeadToHead;
import com.oriondb.stats.OpeningExplorer;
import com.oriondb.stats.PlayerReport;
import com.oriondb.stats.Tally;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** opening_explorer, player_report, head_to_head. */
final class StatsTools {
    private StatsTools() {}

    static List<Tool> tools(Context ctx) {
        return List.of(
            Tool.read("opening_explorer", "Opening explorer",
                "Move statistics from a position across all games reaching it (in any move order): for each move the "
                    + "number of games, White wins/draws/Black wins, score % for the side to move, average rating of the "
                    + "players choosing it, their performance, last year played, and the strongest example game ids. "
                    + "Give 'moves' (SAN from the start), 'fen', or 'game_id' + 'ply'; optionally restrict games with "
                    + "'filter' (text query, e.g. 'player:carlsen as:white elo:2600').",
                Inputs.positionSchema()
                    .str("filter", "Text query restricting which games count (same language as search_games 'query').")
                    .integer("samples", "Example game ids per move (default 3).", 0, 20)
                    .integer("max_moves", "Maximum moves to list (default 30).", 1, 200),
                a -> explore(ctx, a)),
            Tool.read("player_report", "Player report",
                "A player's dossier: name variants matched, overall/White/Black score and performance, rating peak and "
                    + "latest, results by year, repertoire (openings by color), most frequent opponents, best wins, worst "
                    + "losses and recent games (with game ids). Names match by tokens, case- and accent-insensitive.",
                Schema.object()
                    .str("name", "Player name or part of it, e.g. 'carlsen' or 'Magnus Carlsen'.")
                    .integer("top", "Entries per list (default 10).", 1, 100)
                    .required("name"),
                a -> player(ctx, a)),
            Tool.read("head_to_head", "Head to head",
                "Lifetime score between two players from player_a's point of view: total, as White, as Black, by year, "
                    + "and the games (id rows, oldest first).",
                Schema.object()
                    .str("player_a", "First player name.")
                    .str("player_b", "Second player name.")
                    .integer("max_games", "Game rows to list (default 50).", 0, 500)
                    .required("player_a", "player_b"),
                a -> headToHead(ctx, a)));
    }

    private static ToolResult explore(Context ctx, Args a) {
        Snapshot snap = ctx.snap();
        Inputs.PositionInput in = Inputs.position(a, snap);
        Predicate<GameRecord> filter = null;
        if (a.has("filter")) {
            Query q = SearchTools.parseQuery(a.str("filter"));
            Set<Integer> ids = new HashSet<>(Searcher.ids(snap, q));
            filter = r -> ids.contains(r.id());
        }
        OpeningExplorer.Result res = OpeningExplorer.explore(snap, in.position(), filter, a.integer("samples", 3, 0, 20));
        int maxMoves = a.integer("max_moves", 30, 1, 200);
        StringBuilder sb = new StringBuilder("Position: ").append(in.description()).append('\n');
        sb.append("FEN: ").append(res.fen()).append('\n');
        if (res.opening() != null) sb.append("Opening: ").append(res.opening().eco()).append(' ').append(res.opening().name()).append('\n');
        sb.append(res.games()).append(" games").append(a.has("filter") ? " (filtered)" : "")
            .append(": 1-0 ").append(res.whiteWins()).append(", = ").append(res.draws()).append(", 0-1 ").append(res.blackWins());
        if (res.endedHere() > 0) sb.append(", ").append(res.endedHere()).append(" ended here");
        sb.append('\n');
        String mover = in.position().sideToMove() == 0 ? "White" : "Black";
        if (!res.moves().isEmpty()) {
            sb.append("move   games   1-0/=/0-1   score(").append(mover).append(")  avgElo  perf  last  examples\n");
        }
        JsonArray moves = new JsonArray();
        List<OpeningExplorer.MoveStats> list = res.moves();
        for (int i = 0; i < list.size(); i++) {
            OpeningExplorer.MoveStats m = list.get(i);
            moves.add(Json.obj("san", m.san(), "uci", m.uci(), "games", m.games(), "white_wins", m.whiteWins(),
                "draws", m.draws(), "black_wins", m.blackWins(), "score_pct", m.score(),
                "avg_elo", m.averageElo() > 0 ? m.averageElo() : null, "performance", m.performance() > 0 ? m.performance() : null,
                "last_year", m.lastYear() > 0 ? m.lastYear() : null, "examples", m.topGames()));
            if (i >= maxMoves) continue;
            sb.append(String.format(Locale.ROOT, "%-6s %5d   %d/%d/%d   %5.1f%%   %6s  %4s  %4s  %s%n", m.san(), m.games(),
                m.whiteWins(), m.draws(), m.blackWins(), m.score(), m.averageElo() > 0 ? m.averageElo() : "-",
                m.performance() > 0 ? m.performance() : "-", m.lastYear() > 0 ? m.lastYear() : "-",
                m.topGames().stream().map(x -> "#" + x).toList()));
        }
        if (list.size() > maxMoves) sb.append("(").append(list.size() - maxMoves).append(" more moves)\n");
        JsonObject json = Json.obj("fen", res.fen(), "games", res.games(), "white_wins", res.whiteWins(), "draws", res.draws(),
            "black_wins", res.blackWins(), "ended_here", res.endedHere(),
            "opening", res.opening() == null ? null : Json.obj("eco", res.opening().eco(), "name", res.opening().name()),
            "moves", moves);
        return ToolResult.of(sb.toString().strip(), json);
    }

    private static ToolResult player(Context ctx, Args a) {
        String name = a.requireStr("name");
        int top = a.integer("top", 10, 1, 100);
        PlayerReport.Report r = PlayerReport.build(ctx.snap(), name, top);
        if (r.namesMatched().isEmpty()) {
            return ToolResult.of("No games found for player '" + name + "'.", Json.obj("query", name, "games", 0));
        }
        StringBuilder sb = new StringBuilder("Player report: ").append(name).append('\n');
        sb.append("Names matched: ").append(r.namesMatched()).append('\n');
        sb.append("Total: ").append(Format.tallyText(r.total())).append('\n');
        sb.append("As White: ").append(Format.tallyText(r.asWhite())).append('\n');
        sb.append("As Black: ").append(Format.tallyText(r.asBlack())).append('\n');
        if (r.firstYear() > 0) sb.append("Years: ").append(r.firstYear()).append('-').append(r.lastYear()).append('\n');
        if (r.peakElo() > 0) sb.append("Rating: peak ").append(r.peakElo()).append(", latest ").append(r.latestElo()).append('\n');
        if (!r.byYear().isEmpty()) {
            sb.append("By year:");
            r.byYear().forEach((y, t) -> sb.append("\n  ").append(y).append(": ").append(Format.tallyText(t)));
            sb.append('\n');
        }
        appendOpenings(sb, "Openings as White", r.whiteOpenings());
        appendOpenings(sb, "Openings as Black", r.blackOpenings());
        if (!r.opponents().isEmpty()) {
            sb.append("Opponents:");
            for (PlayerReport.Opponent o : r.opponents()) sb.append("\n  ").append(o.name()).append(": ").append(Format.tallyText(o.tally()));
            sb.append('\n');
        }
        appendRefs(sb, "Best wins", r.bestWins());
        appendRefs(sb, "Worst losses", r.worstLosses());
        appendRefs(sb, "Recent games", r.recent());

        JsonObject byYear = new JsonObject();
        r.byYear().forEach((y, t) -> byYear.add(String.valueOf(y), Format.tally(t)));
        JsonObject json = Json.obj("query", name, "names_matched", r.namesMatched(), "total", Format.tally(r.total()),
            "as_white", Format.tally(r.asWhite()), "as_black", Format.tally(r.asBlack()),
            "first_year", r.firstYear(), "last_year", r.lastYear(), "peak_elo", r.peakElo(), "latest_elo", r.latestElo(),
            "by_year", byYear, "white_openings", openings(r.whiteOpenings()), "black_openings", openings(r.blackOpenings()),
            "opponents", Json.arr(r.opponents().stream().map(o -> Json.obj("name", o.name(), "result", Format.tally(o.tally()))).toList()),
            "best_wins", Json.of(r.bestWins()), "worst_losses", Json.of(r.worstLosses()), "recent", Json.of(r.recent()));
        return ToolResult.of(sb.toString().strip(), json);
    }

    private static void appendOpenings(StringBuilder sb, String title, List<PlayerReport.OpeningLine> lines) {
        if (lines.isEmpty()) return;
        sb.append(title).append(':');
        for (PlayerReport.OpeningLine l : lines) {
            sb.append("\n  ").append(l.eco()).append(l.name().isEmpty() ? "" : " " + l.name()).append(": ").append(Format.tallyText(l.tally()));
        }
        sb.append('\n');
    }

    private static JsonArray openings(List<PlayerReport.OpeningLine> lines) {
        return Json.arr(lines.stream().map(l -> Json.obj("eco", l.eco(), "name", l.name(), "result", Format.tally(l.tally()))).toList());
    }

    private static void appendRefs(StringBuilder sb, String title, List<PlayerReport.GameRef> refs) {
        if (refs.isEmpty()) return;
        sb.append(title).append(':');
        for (PlayerReport.GameRef g : refs) {
            sb.append("\n  #").append(g.id()).append(' ').append(g.result()).append(" as ").append(g.color()).append(" vs ")
                .append(Format.player(g.opponent(), g.opponentElo())).append(", ").append(g.date());
        }
        sb.append('\n');
    }

    private static ToolResult headToHead(Context ctx, Args a) {
        Snapshot snap = ctx.snap();
        String pa = a.requireStr("player_a"), pb = a.requireStr("player_b");
        HeadToHead.Result r = HeadToHead.compare(snap, pa, pb);
        int max = a.integer("max_games", 50, 0, 500);
        if (r.gameIds().isEmpty()) {
            return ToolResult.of("No games between '" + pa + "' and '" + pb + "'.", Json.obj("games", 0));
        }
        StringBuilder sb = new StringBuilder(pa).append(" vs ").append(pb).append(" (from ").append(pa).append("'s side)\n");
        sb.append("Total: ").append(Format.tallyText(r.total())).append('\n');
        sb.append("As White: ").append(Format.tallyText(r.aAsWhite())).append('\n');
        sb.append("As Black: ").append(Format.tallyText(r.aAsBlack())).append('\n');
        JsonObject byYear = new JsonObject();
        for (Map.Entry<Integer, Tally> e : r.byYear().entrySet()) {
            byYear.add(String.valueOf(e.getKey()), Format.tally(e.getValue()));
            sb.append("  ").append(e.getKey()).append(": ").append(Format.tallyText(e.getValue())).append('\n');
        }
        sb.append("Games (").append(r.gameIds().size()).append("):");
        JsonArray rows = new JsonArray();
        for (int id : r.gameIds().subList(0, Math.min(max, r.gameIds().size()))) {
            GameRecord g = snap.get(id);
            rows.add(Format.row(g, -1));
            sb.append("\n  ").append(Format.rowText(g, -1));
        }
        if (r.gameIds().size() > max) sb.append("\n  ... ").append(r.gameIds().size() - max).append(" more");
        JsonObject json = Json.obj("player_a", pa, "player_b", pb, "total", Format.tally(r.total()),
            "a_as_white", Format.tally(r.aAsWhite()), "a_as_black", Format.tally(r.aAsBlack()), "by_year", byYear,
            "game_ids", r.gameIds(), "games", rows);
        return ToolResult.of(sb.toString(), json);
    }
}
