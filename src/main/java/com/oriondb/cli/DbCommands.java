package com.oriondb.cli;

import com.oriondb.db.Database;
import com.oriondb.db.GameRecord;
import com.oriondb.db.ImportException;
import com.oriondb.db.ImportOptions;
import com.oriondb.db.ImportResult;
import com.oriondb.db.Snapshot;
import com.oriondb.db.Source;
import com.oriondb.mcp.McpServer;
import com.oriondb.stats.DatabaseStats;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Whole-database commands: import, stats, check, reindex, mcp. */
final class DbCommands {
    private DbCommands() {}

    static List<Command> commands() {
        return List.of(
            new Command("import", "<db.pgn> <file.pgn>... [--label a,b] [--keep-duplicates] [--atomic]",
                "Import PGN files into a database (created if missing)",
                """
                Adds every game of the given PGN files. Games already present (same moves
                and players) are skipped unless --keep-duplicates. Games that fail to parse
                are rejected and listed; with --atomic any invalid game rejects the whole import.

                Options:
                  --label a,b          put these labels on every imported game
                  --keep-duplicates    import games even if already present
                  --atomic             all-or-nothing: import nothing if any game is invalid

                Examples:
                  oriondb import my.pgn twic1500.pgn twic1501.pgn --label twic
                  oriondb import prep.pgn lichess_export.pgn --atomic
                """,
                Args.Spec.of("label=", "keep-duplicates", "atomic"), DbCommands::importCmd),
            new Command("stats", "<db> [--top N] [--format text|json]",
                "Database overview: games, results, players, events, openings, labels",
                """
                Options:
                  --top N        how many players/events/openings to list (default 10)
                  --format       text (default) or json
                """,
                Args.Spec.of("top=", "format="), DbCommands::stats),
            new Command("check", "<db> [--format table|tsv|json]",
                "List games that could not be parsed (exit 1 if any)",
                """
                Invalid games are kept verbatim in the file but are invisible to search and
                statistics. Fix them in the PGN by hand, or remove them with 'oriondb delete'.
                """,
                Args.Spec.of("format="), DbCommands::check),
            new Command("reindex", "<db>",
                "Delete the .oidx index cache and rebuild it from the PGN",
                """
                The cache is rebuilt automatically whenever the PGN changes; reindexing is
                only needed after upgrading OrionDB or if the cache is suspected stale.
                Needs the write lock (fails while an MCP server has the database open).
                """,
                Args.Spec.of(), DbCommands::reindex),
            new Command("mcp", "<db> [--read-only]",
                "Serve the database to AI assistants over MCP (stdio)",
                """
                Speaks the Model Context Protocol (newline-delimited JSON-RPC) on stdin/stdout.
                Changes are saved after every mutation. With --read-only the database is
                opened without the write lock and mutating tools fail.

                Claude Code example:
                  claude mcp add oriondb -- /path/to/bin/oriondb mcp /path/to/games.pgn
                """,
                Args.Spec.of("read-only"), DbCommands::mcp));
    }

    // ------------------------------------------------------------ import

    static int importCmd(Args a, Context ctx) {
        String dbFile = a.require(0, "database file");
        List<String> files = a.rest(1);
        if (files.isEmpty()) throw new UsageException("missing PGN file(s) to import");
        Path dbPath = Path.of(dbFile).toAbsolutePath().normalize();
        for (String f : files) {
            Path p = Path.of(f);
            if (!Files.isRegularFile(p)) throw new CliException("no such file: " + f);
            if (p.toAbsolutePath().normalize().equals(dbPath)) throw new UsageException("cannot import a database into itself");
        }
        ImportOptions opts = ImportOptions.DEFAULT.withLabels(a.list("label"))
            .withSkipDuplicates(!a.has("keep-duplicates")).withAtomic(a.has("atomic"));
        boolean created = !Files.exists(dbPath);
        long t0 = System.nanoTime();
        try (Database db = ctx.openOrCreate(dbFile)) {
            int before = db.size();
            List<ImportResult> results = new ArrayList<>();
            try {
                db.update(b -> {
                    for (String f : files) results.add(b.importFile(Path.of(f), opts));
                });
            } catch (ImportException e) {
                String f = files.get(results.size());
                printImport(ctx, f, e.result());
                ctx.err.println("import rejected (--atomic): " + e.result().invalid() + " invalid game(s) in " + f
                    + "; nothing was imported");
                return 1;
            }
            int read = 0, added = 0, dups = 0, invalid = 0;
            for (int i = 0; i < files.size(); i++) {
                ImportResult r = results.get(i);
                printImport(ctx, files.get(i), r);
                read += r.read();
                added += r.added();
                dups += r.duplicates();
                invalid += r.invalid();
            }
            if (files.size() > 1) {
                ctx.out.printf("total: read %d, added %d, duplicates %d, invalid %d%n", read, added, dups, invalid);
            }
            if (db.isDirty()) db.save();
            ctx.out.printf("%s %s: %d games (was %d) in %s%n", created ? "created" : "saved", dbFile, db.size(), before,
                Fmt.seconds(System.nanoTime() - t0));
            return 0;
        }
    }

    private static void printImport(Context ctx, String file, ImportResult r) {
        ctx.out.printf("%s: read %d, added %d, duplicates %d, invalid %d%n", file, r.read(), r.added(), r.duplicates(), r.invalid());
        int shown = Math.min(10, r.errors().size());
        for (int i = 0; i < shown; i++) ctx.out.println("  rejected " + r.errors().get(i));
        if (r.invalid() > shown) ctx.out.println("  ... and " + (r.invalid() - shown) + " more invalid game(s)");
    }

    // ------------------------------------------------------------ stats

    static int stats(Args a, Context ctx) {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        int top = a.positiveInt("top", 10);
        String format = a.choice("format", "text", "json");
        try (Database db = ctx.openReadOnly(file)) {
            DatabaseStats.Stats s = DatabaseStats.compute(db.snapshot(), top);
            if (format.equals("json")) {
                ctx.out.println(Table.GSON.toJson(s));
                return 0;
            }
            var o = ctx.out;
            int valid = s.games() - s.invalid();
            o.println("Database:    " + db.path());
            o.println("Games:       " + s.games() + (s.invalid() > 0 ? "  (" + s.invalid() + " invalid — run 'oriondb check')" : ""));
            o.println("Positions:   " + s.indexedPositions() + " indexed");
            o.println("Years:       " + Fmt.years(s.firstYear(), s.lastYear()));
            if (valid > 0) {
                o.printf("Results:     1-0 %d (%s)   draw %d (%s)   0-1 %d (%s)%s%n",
                    s.whiteWins(), Fmt.pct(100.0 * s.whiteWins() / valid), s.draws(), Fmt.pct(100.0 * s.draws() / valid),
                    s.blackWins(), Fmt.pct(100.0 * s.blackWins() / valid), s.unfinished() > 0 ? "   * " + s.unfinished() : "");
                o.println("Avg length:  " + s.averagePlies() + " plies");
                o.println("Avg Elo:     " + (s.averageElo() > 0 ? s.averageElo() : "-"));
                o.println("Annotated:   " + s.annotated() + "   with evals: " + s.withEvals() + "   Chess960: " + s.chess960());
            }
            section(o, "Top players", s.players());
            section(o, "Top events", s.events());
            section(o, "Top openings", s.openings());
            section(o, "Labels", s.labels());
            return 0;
        }
    }

    private static void section(java.io.PrintStream o, String title, List<DatabaseStats.Count> counts) {
        if (counts.isEmpty()) return;
        o.println();
        o.println(title + ":");
        int w = 0;
        for (DatabaseStats.Count c : counts) w = Math.max(w, String.valueOf(c.games()).length());
        for (DatabaseStats.Count c : counts) o.printf("  %" + w + "d  %s%n", c.games(), c.name());
    }

    // ------------------------------------------------------------ check

    static int check(Args a, Context ctx) throws IOException {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        String format = a.choice("format", Table.FORMATS);
        try (Database db = ctx.openReadOnly(file)) {
            List<GameRecord> bad = db.invalidGames();
            if (bad.isEmpty()) {
                if (format.equals("json")) ctx.out.println("[]");
                else ctx.out.println("all " + db.size() + " games parse");
                return 0;
            }
            long[] lines = lineNumbers(db.path(), bad);
            Table t = new Table("id", "line", "white", "black", "error").maxWidth("error", 70).maxWidth("white", 24).maxWidth("black", 24);
            for (int i = 0; i < bad.size(); i++) {
                GameRecord r = bad.get(i);
                t.row(r.id(), lines[i] > 0 ? lines[i] : null, Fmt.orDash(r.white()), Fmt.orDash(r.black()), r.error());
            }
            t.print(ctx.out, format);
            ctx.err.println(bad.size() + " of " + db.size() + " games could not be parsed; fix them in the PGN or remove them with "
                + "'oriondb delete " + file + " <ids>'");
            return 1;
        }
    }

    /** 1-based line of each record's first byte in the file (0 if unknown). */
    private static long[] lineNumbers(Path file, List<GameRecord> records) throws IOException {
        Integer[] order = new Integer[records.size()];
        long[] offsets = new long[records.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            offsets[i] = records.get(i).source() instanceof Source.FileSpan span ? span.offset() : -1;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingLong(i -> offsets[i]));
        long[] out = new long[records.size()];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            long pos = 0, line = 1;
            for (int i : order) {
                if (offsets[i] < 0) continue;
                for (; pos < offsets[i]; pos++) {
                    int c = in.read();
                    if (c < 0) return out;
                    if (c == '\n') line++;
                }
                out[i] = line;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ reindex

    static int reindex(Args a, Context ctx) throws IOException {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        Path p = Context.existing(file);
        Path cache = p.resolveSibling(p.getFileName() + ".oidx");
        boolean had = Files.deleteIfExists(cache);
        long t0 = System.nanoTime();
        try (Database db = ctx.openWritable(file)) {
            Snapshot s = db.snapshot();
            ctx.out.printf("%s %s: %d games (%d invalid), %d positions indexed in %s%n",
                had ? "reindexed" : "indexed", file, s.size(), db.invalidGames().size(), s.indexedPositions(),
                Fmt.seconds(System.nanoTime() - t0));
        }
        return 0;
    }

    // ------------------------------------------------------------ mcp

    static int mcp(Args a, Context ctx) {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        boolean readOnly = a.has("read-only");
        Database db = readOnly ? ctx.openReadOnly(file)
            : Database.open(Path.of(file), Database.Options.DEFAULT.withAutoSave(true));
        try (db) {
            ctx.err.println("oriondb: serving " + db.path() + " (" + db.size() + " games" + (readOnly ? ", read-only" : "")
                + ") over MCP on stdio");
            McpServer.serve(db, ctx.in, ctx.out);
        }
        return 0;
    }
}
