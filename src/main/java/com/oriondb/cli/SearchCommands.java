package com.oriondb.cli;

import com.oriondb.db.Database;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.query.Query;
import com.oriondb.query.QueryParser;
import com.oriondb.query.SearchResult;
import com.oriondb.query.Searcher;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Searching and exporting games. */
final class SearchCommands {
    private SearchCommands() {}

    static List<Command> commands() {
        return List.of(
            new Command("search", "<db> [query terms...] [--format table|ids|pgn|json|tsv] [--limit N]",
                "Find games with the query language (players, ECO, Elo, positions, motifs...)",
                """
                Query terms are joined with spaces and parsed as one query (see below).
                Shows the first 50 matches unless --limit (or limit:N) says otherwise;
                --format ids and pgn print every match unless limited.

                Options:
                  --format F     table (default), ids (one per line), pgn, json, tsv
                  --limit N      show at most N games

                Examples:
                  oriondb search my.pgn player:carlsen eco:B90-B99 elo:2700 sort:-date
                  oriondb search my.pgn 'fen:"r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"'
                  oriondb search my.pgn material:KRPKR result:draw --format ids
                  oriondb search my.pgn motif:queen_sacrifice motifby:white year:1850..1900
                """,
                Args.Spec.of("format=", "limit="), SearchCommands::search),
            new Command("export", "<db> [query terms...] -o <out.pgn> [--no-comments] [--no-variations]",
                "Write games (all, or those matching a query) to a PGN file",
                """
                Options:
                  -o, --output F     output file ('-' for stdout); the database itself is refused
                  --no-comments      drop comments (including [%eval]/[%clk] annotations)
                  --no-variations    drop side variations
                  --limit N          export at most N games

                Examples:
                  oriondb export my.pgn label:prep -o prep.pgn
                  oriondb export my.pgn player:carlsen as:white --no-variations -o -
                """,
                Args.Spec.of("o|output=", "no-comments", "no-variations", "limit="), SearchCommands::export));
    }

    /** Query from the terms; {@code --limit} overrides; machine formats return everything unless limited. */
    private static Query query(Args a, List<String> terms, boolean unlimitedByDefault) {
        Query q = Context.query(terms);
        boolean limited = terms.stream().anyMatch(t -> t.toLowerCase().startsWith("limit:"));
        if (a.has("limit")) q.page(q.offset(), a.positiveInt("limit", 50));
        else if (unlimitedByDefault && !limited) q.page(q.offset(), Integer.MAX_VALUE);
        return q;
    }

    // ------------------------------------------------------------ search

    static int search(Args a, Context ctx) {
        String file = a.require(0, "database file");
        List<String> terms = a.rest(1);
        String format = a.choice("format", "table", "ids", "pgn", "json", "tsv");
        Query q = query(a, terms, format.equals("ids") || format.equals("pgn"));
        try (Database db = ctx.openReadOnly(file)) {
            Snapshot snap = db.snapshot();
            SearchResult res = Searcher.search(snap, q);
            List<SearchResult.Match> matches = res.matches();
            boolean showMatch = matches.stream().anyMatch(m -> m.ply() >= 0 || m.inVariation());
            String summary = matches.isEmpty()
                ? "no games match (of " + validCount(db) + ")"
                : (matches.size() == res.total() ? res.total() + " game" + (res.total() == 1 ? "" : "s")
                    : "showing " + (q.offset() + 1) + "–" + (q.offset() + matches.size()) + " of " + res.total() + " games");
            switch (format) {
                case "ids" -> {
                    for (SearchResult.Match m : matches) ctx.out.println(m.record().id());
                    if (matches.size() < res.total()) ctx.err.println(summary);
                }
                case "pgn" -> {
                    for (SearchResult.Match m : matches) {
                        ctx.out.println(snap.pgn(m.record().id()).strip());
                        ctx.out.println();
                    }
                    if (matches.size() < res.total()) ctx.err.println(summary);
                }
                case "json" -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("total", res.total());
                    o.put("offset", q.offset());
                    o.put("games", matches.stream().map(m -> json(m)).toList());
                    ctx.out.println(Table.GSON.toJson(o));
                }
                default -> {
                    Table t = showMatch
                        ? new Table("id", "white", "black", "result", "date", "eco", "plies", "event", "match")
                        : new Table("id", "white", "black", "result", "date", "eco", "plies", "event");
                    t.maxWidth("white", 28).maxWidth("black", 28).maxWidth("event", 30);
                    for (SearchResult.Match m : matches) {
                        GameRecord r = m.record();
                        Object[] cells = {r.id(), Fmt.player(r.white(), r.whiteElo()), Fmt.player(r.black(), r.blackElo()),
                            r.resultText(), r.dateText(), Fmt.orDash(r.eco()), r.plyCount(), Fmt.orDash(r.event()),
                            m.ply() >= 0 || m.inVariation() ? Fmt.moveLabel(r, m.ply()) : ""};
                        t.row(showMatch ? cells : java.util.Arrays.copyOf(cells, 8));
                    }
                    if (format.equals("tsv")) {
                        t.print(ctx.out, "tsv");
                        ctx.err.println(summary);
                    } else {
                        if (!matches.isEmpty()) t.print(ctx.out, "table");
                        ctx.out.println(summary);
                    }
                }
            }
            ctx.noteInvalid(db);
            return 0;
        }
    }

    private static int validCount(Database db) {
        return db.size() - db.invalidGames().size();
    }

    static Map<String, Object> json(SearchResult.Match m) {
        GameRecord r = m.record();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", r.id());
        o.put("white", r.white());
        o.put("whiteElo", r.whiteElo() > 0 ? r.whiteElo() : null);
        o.put("black", r.black());
        o.put("blackElo", r.blackElo() > 0 ? r.blackElo() : null);
        o.put("result", r.resultText());
        o.put("date", r.dateText());
        o.put("event", r.event());
        o.put("site", r.site());
        o.put("round", r.round());
        o.put("eco", r.eco());
        o.put("opening", r.opening());
        o.put("plies", r.plyCount());
        o.put("labels", r.labels());
        if (m.ply() >= 0 || m.inVariation()) {
            o.put("matchPly", m.ply());
            o.put("matchMove", Fmt.moveLabel(r, m.ply()));
        }
        return o;
    }

    // ------------------------------------------------------------ export

    static int export(Args a, Context ctx) throws IOException {
        String file = a.require(0, "database file");
        String output = a.get("output");
        if (output == null) throw new UsageException("missing -o <out.pgn> (use '-o -' for stdout)");
        List<String> terms = a.rest(1);
        Query q = query(a, terms, true);
        boolean stdout = output.equals("-");
        if (!stdout) {
            Path out = Path.of(output).toAbsolutePath().normalize();
            Path dbp = Path.of(file).toAbsolutePath().normalize();
            if (out.equals(dbp) || (Files.exists(out) && Files.exists(dbp) && Files.isSameFile(out, dbp))) {
                throw new UsageException("refusing to overwrite the database itself; choose another output file");
            }
            if (Files.isDirectory(out)) throw new UsageException(output + " is a directory");
        }
        boolean comments = !a.has("no-comments"), variations = !a.has("no-variations");
        PgnWriter.Options wo = new PgnWriter.Options(80, comments, variations, true, false);
        try (Database db = ctx.openReadOnly(file)) {
            Snapshot snap = db.snapshot();
            List<Integer> ids = terms.isEmpty() && !a.has("limit")
                ? snap.records().stream().filter(GameRecord::isValid).map(GameRecord::id).toList()
                : Searcher.search(snap, q).matches().stream().map(m -> m.record().id()).toList();
            int n = 0;
            Writer w = stdout
                ? new BufferedWriter(new OutputStreamWriter(ctx.out, StandardCharsets.UTF_8))
                : Files.newBufferedWriter(Path.of(output), StandardCharsets.UTF_8);
            try {
                for (int id : ids) {
                    String text = comments && variations ? snap.pgn(id) : PgnWriter.write(snap.game(id), wo);
                    w.write(text.strip());
                    w.write("\n\n");
                    n++;
                }
            } finally {
                if (stdout) w.flush();
                else w.close();
            }
            ctx.err.println("exported " + n + " game" + (n == 1 ? "" : "s") + (stdout ? "" : " to " + output));
            ctx.noteInvalid(db);
            return 0;
        }
    }

    static String queryHelp() {
        return QueryParser.HELP;
    }
}
