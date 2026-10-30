package com.oriondb.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oriondb.chess.Position;
import com.oriondb.db.Database;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.eco.EcoBook;
import com.oriondb.query.Query;
import com.oriondb.query.Searcher;
import com.oriondb.stats.HeadToHead;
import com.oriondb.stats.OpeningExplorer;
import com.oriondb.stats.PlayerReport;
import com.oriondb.stats.Tally;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Statistical reports: explore, player, h2h. */
final class ReportCommands {
    private ReportCommands() {}

    /** JSON for reports: a Tally becomes its counts plus derived score and performance. */
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
        .registerTypeAdapter(Tally.class, (JsonSerializer<Tally>) (t, type, c) -> {
            JsonObject o = new JsonObject();
            o.addProperty("games", t.games());
            o.addProperty("wins", t.wins());
            o.addProperty("draws", t.draws());
            o.addProperty("losses", t.losses());
            o.addProperty("score", Math.round(t.percent() * 10) / 10.0);
            o.addProperty("averageOpponentElo", t.averageOpponentElo());
            o.addProperty("performance", t.performance());
            return o;
        }).create();

    static List<Command> commands() {
        return List.of(
            new Command("explore", "<db> [moves...] [--fen FEN] [--filter \"query\"] [--samples N] [--format table|tsv|json]",
                "Opening explorer: move statistics from a position",
                """
                Statistics for every move played from the position reached by the moves
                (from the start, or from --fen), over all games reaching it in any move order.
                Score is from the side to move's point of view; avg elo and perf are those of
                the players making the move.

                Options:
                  --fen FEN          start position (moves, if given, are played from it)
                  --filter "query"   only count games matching this query (see 'help search')
                  --samples N        example game ids per move (default 3, 0 to hide)
                  --format F         table (default), tsv, json

                Examples:
                  oriondb explore my.pgn
                  oriondb explore my.pgn e4 c5 Nf3 --filter "elo:2600 year:2015.."
                """,
                Args.Spec.of("fen=", "filter=", "samples=", "format="), ReportCommands::explore),
            new Command("player", "<db> <name> [--top N] [--format text|json]",
                "Player report: results by color/year, repertoire, opponents, best wins",
                """
                The name matches by tokens in any order, accent- and case-insensitively
                ("carlsen", "magnus carlsen" and "Carlsen, M" all match "Carlsen, Magnus").

                Options:
                  --top N     rows per section (default 10)
                  --format    text (default) or json
                """,
                Args.Spec.of("top=", "format="), ReportCommands::player),
            new Command("h2h", "<db> <playerA> <playerB> [--format text|json]",
                "Head-to-head score between two players",
                """
                Example:
                  oriondb h2h my.pgn kasparov karpov
                """,
                Args.Spec.of("format="), ReportCommands::h2h));
    }

    // ------------------------------------------------------------ explore

    static int explore(Args a, Context ctx) {
        String file = a.require(0, "database file");
        Fmt.Line line = Fmt.line(a.get("fen"), a.rest(1));
        int samples = a.intValue("samples", 3);
        if (samples < 0) throw new UsageException("--samples must be >= 0");
        String format = a.choice("format", Table.FORMATS);
        Query filterQuery = a.has("filter") ? Context.query(a.get("filter")) : null;
        try (Database db = ctx.openReadOnly(file)) {
            Snapshot snap = db.snapshot();
            Predicate<GameRecord> filter = null;
            if (filterQuery != null) {
                Set<Integer> ids = new HashSet<>(Searcher.ids(snap, filterQuery));
                filter = r -> ids.contains(r.id());
            }
            Position pos = line.end();
            OpeningExplorer.Result res = OpeningExplorer.explore(snap, pos, filter, Math.max(samples, 1));
            if (format.equals("json")) {
                ctx.out.println(GSON.toJson(res));
                return 0;
            }
            Table t = samples > 0
                ? new Table("move", "games", "+/=/-", "score", "avg elo", "perf", "last", "examples")
                : new Table("move", "games", "+/=/-", "score", "avg elo", "perf", "last");
            for (OpeningExplorer.MoveStats m : res.moves()) {
                String share = res.games() == 0 ? "" : String.format(java.util.Locale.ROOT, " (%.0f%%)", 100.0 * m.games() / res.games());
                Object[] cells = {m.san(), m.games() + share, m.whiteWins() + "/" + m.draws() + "/" + m.blackWins(),
                    m.whiteWins() + m.draws() + m.blackWins() == 0 ? "-" : Fmt.pct(m.score()),
                    Fmt.orDash(m.averageElo()), Fmt.orDash(m.performance()), Fmt.orDash(m.lastYear()),
                    GameCommands.join(m.topGames().stream().limit(samples).toList())};
                t.row(samples > 0 ? cells : java.util.Arrays.copyOf(cells, 7));
            }
            if (format.equals("tsv")) {
                t.print(ctx.out, "tsv");
                return 0;
            }
            PrintStream o = ctx.out;
            o.println("Position: " + (line.moves().isEmpty() ? (line.start().equals(Position.start()) ? "start" : "FEN") : line.san()));
            o.println("FEN:      " + res.fen());
            EcoBook.Opening op = res.opening();
            if (op == null && !line.moves().isEmpty() && line.start().equals(Position.start())) {
                op = EcoBook.get().classify(line.positions().toArray(Position[]::new));
            }
            if (op != null) o.println("Opening:  " + op.eco() + " " + op.name());
            o.println("Games:    " + res.games() + (res.games() == 0 ? "" : "   1-0 " + res.whiteWins() + "  draw " + res.draws()
                + "  0-1 " + res.blackWins() + (res.endedHere() > 0 ? "   (" + res.endedHere() + " ended here)" : ""))
                + (filterQuery != null ? "   [filtered: " + a.get("filter") + "]" : ""));
            if (!res.moves().isEmpty()) {
                o.println();
                t.print(o, "table");
                o.println("score: points for " + (pos.sideToMove() == 0 ? "White" : "Black") + " (side to move); +/=/- are 1-0/draw/0-1");
            }
            ctx.noteInvalid(db);
            return 0;
        }
    }

    // ------------------------------------------------------------ player

    static int player(Args a, Context ctx) {
        String file = a.require(0, "database file");
        List<String> nameParts = a.rest(1);
        if (nameParts.isEmpty()) throw new UsageException("missing player name");
        String name = String.join(" ", nameParts);
        int top = a.positiveInt("top", 10);
        String format = a.choice("format", "text", "json");
        try (Database db = ctx.openReadOnly(file)) {
            PlayerReport.Report r = PlayerReport.build(db.snapshot(), name, top);
            if (r.namesMatched().isEmpty()) {
                ctx.err.println("no games found for player '" + name + "'");
                return 1;
            }
            if (format.equals("json")) {
                ctx.out.println(GSON.toJson(r));
                return 0;
            }
            PrintStream o = ctx.out;
            o.println("Player:    " + name + "   (matched " + matched(r.namesMatched()) + ")");
            o.println("Overall:   " + Fmt.tally(r.total()));
            o.println("As White:  " + Fmt.tally(r.asWhite()));
            o.println("As Black:  " + Fmt.tally(r.asBlack()));
            o.println("Years:     " + Fmt.years(r.firstYear(), r.lastYear())
                + (r.peakElo() > 0 ? "   peak Elo " + r.peakElo() + ", latest " + r.latestElo() : ""));
            if (r.byYear().size() > 1) {
                Table t = new Table("year", "games", "+/=/-", "score", "perf");
                for (Map.Entry<Integer, Tally> e : r.byYear().entrySet()) tallyRow(t, e.getKey(), e.getValue());
                heading(o, "By year");
                t.print(o, "table");
            }
            repertoire(o, "Repertoire as White", r.whiteOpenings());
            repertoire(o, "Repertoire as Black", r.blackOpenings());
            if (!r.opponents().isEmpty()) {
                Table t = new Table("opponent", "games", "+/=/-", "score", "perf").maxWidth("opponent", 30);
                for (PlayerReport.Opponent op : r.opponents()) tallyRow(t, op.name(), op.tally());
                heading(o, "Most frequent opponents");
                t.print(o, "table");
            }
            refs(o, "Best wins", r.bestWins());
            refs(o, "Worst losses", r.worstLosses());
            refs(o, "Most recent games", r.recent());
            return 0;
        }
    }

    private static String matched(Map<String, Integer> names) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : names.entrySet()) {
            if (shown++ == 3) {
                sb.append(", +").append(names.size() - 3).append(" more");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    private static void tallyRow(Table t, Object key, Tally x) {
        t.row(key, x.games(), Fmt.wdl(x.wins(), x.draws(), x.losses()), Fmt.pct(x.percent()), Fmt.orDash(x.performance()));
    }

    private static void heading(PrintStream o, String title) {
        o.println();
        o.println(title + ":");
    }

    private static void repertoire(PrintStream o, String title, List<PlayerReport.OpeningLine> lines) {
        if (lines.isEmpty()) return;
        Table t = new Table("eco", "opening", "games", "+/=/-", "score", "perf").maxWidth("opening", 44);
        for (PlayerReport.OpeningLine l : lines) {
            Tally x = l.tally();
            t.row(l.eco(), Fmt.orDash(l.name()), x.games(), Fmt.wdl(x.wins(), x.draws(), x.losses()), Fmt.pct(x.percent()),
                Fmt.orDash(x.performance()));
        }
        heading(o, title);
        t.print(o, "table");
    }

    private static void refs(PrintStream o, String title, List<PlayerReport.GameRef> refs) {
        if (refs.isEmpty()) return;
        Table t = new Table("id", "color", "opponent", "result", "date").maxWidth("opponent", 34);
        for (PlayerReport.GameRef g : refs) t.row(g.id(), g.color(), Fmt.player(g.opponent(), g.opponentElo()), g.result(), g.date());
        heading(o, title);
        t.print(o, "table");
    }

    // ------------------------------------------------------------ h2h

    static int h2h(Args a, Context ctx) {
        String file = a.require(0, "database file");
        String pa = a.require(1, "first player");
        String pb = a.require(2, "second player");
        a.maxPositionals(3);
        String format = a.choice("format", "text", "json");
        try (Database db = ctx.openReadOnly(file)) {
            Snapshot snap = db.snapshot();
            HeadToHead.Result r = HeadToHead.compare(snap, pa, pb);
            if (format.equals("json")) {
                ctx.out.println(GSON.toJson(r));
                return 0;
            }
            PrintStream o = ctx.out;
            if (r.gameIds().isEmpty()) {
                o.println("no games between '" + pa + "' and '" + pb + "'");
                return 0;
            }
            o.println(pa + " vs " + pb + " (from " + pa + "'s side)");
            o.println("Overall:   " + Fmt.tally(r.total()));
            o.println("As White:  " + Fmt.tally(r.aAsWhite()));
            o.println("As Black:  " + Fmt.tally(r.aAsBlack()));
            if (r.byYear().size() > 1) {
                Table t = new Table("year", "games", "+/=/-", "score", "perf");
                for (Map.Entry<Integer, Tally> e : r.byYear().entrySet()) tallyRow(t, e.getKey(), e.getValue());
                heading(o, "By year");
                t.print(o, "table");
            }
            Table t = new Table("id", "white", "black", "result", "date", "eco", "event")
                .maxWidth("white", 28).maxWidth("black", 28).maxWidth("event", 30);
            for (int id : r.gameIds()) {
                GameRecord g = snap.get(id);
                t.row(id, Fmt.player(g.white(), g.whiteElo()), Fmt.player(g.black(), g.blackElo()), g.resultText(), g.dateText(),
                    Fmt.orDash(g.eco()), Fmt.orDash(g.event()));
            }
            heading(o, "Games");
            t.print(o, "table");
            return 0;
        }
    }
}
