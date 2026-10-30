package com.oriondb.cli;

import com.oriondb.chess.Position;
import com.oriondb.db.Database;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.engine.Analysis;
import com.oriondb.engine.AnnotationReport;
import com.oriondb.engine.EngineConfig;
import com.oriondb.engine.EngineException;
import com.oriondb.engine.GameAnnotator;
import com.oriondb.engine.Limit;
import com.oriondb.engine.MoveAssessment;
import com.oriondb.engine.PvLine;
import com.oriondb.engine.Score;
import com.oriondb.engine.UciEngine;
import com.oriondb.game.Game;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** UCI engine commands: analyze, annotate. */
final class EngineCommands {
    private EngineCommands() {}

    private static final String ENGINE_OPTIONS = """
          --depth N        search depth in plies
          --time MS        search time per position in milliseconds
          --engine PATH    UCI engine binary (default: $ORIONDB_ENGINE, stockfish on PATH,
                           or a common install location)
          --threads N      engine threads (default min(4, cores))
          --hash MB        engine hash size (default 128)
        """;

    static List<Command> commands() {
        return List.of(
            new Command("analyze", "<fen | moves> [--depth N | --time MS] [--multipv N] [--engine PATH]",
                "Analyse a position with a UCI engine (e.g. Stockfish)",
                """
                The position is a FEN, SAN moves from the start, or a FEN followed by moves.
                Scores are from White's point of view. Default: --depth 20, one line.

                Options:
                  --multipv N      show the N best lines
                """ + ENGINE_OPTIONS + """

                Examples:
                  oriondb analyze "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6" --multipv 3
                  oriondb analyze "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1" --depth 10
                """,
                Args.Spec.of("depth=", "time=", "multipv=", "engine=", "threads=", "hash="), EngineCommands::analyze),
            new Command("annotate", "<db> <ids> [--depth N | --time MS] [--white-only|--black-only] [--no-variations] [--dry-run]",
                "Annotate games with engine evals, ?!/?/?? marks and better lines",
                """
                Analyses every move and writes [%eval] comments, NAGs for inaccuracies,
                mistakes and blunders, and the engine's line before mistakes and blunders.
                Prints accuracy, average centipawn loss and error counts per side.
                Existing human annotations are kept. Default: --depth 14.

                Options:
                  --white-only / --black-only   assess only one side's moves
                  --no-variations               do not add engine lines
                  --dry-run                     report only; do not modify the database
                """ + ENGINE_OPTIONS + """

                Example:
                  oriondb annotate my.pgn 12,15 --time 500
                """,
                Args.Spec.of("depth=", "time=", "engine=", "threads=", "hash=", "white-only", "black-only",
                    "no-variations", "dry-run"), EngineCommands::annotate));
    }

    // ------------------------------------------------------------ shared

    static Limit limit(Args a, int defaultDepth) {
        a.exclusive("depth", "time");
        if (a.has("time")) {
            int ms = a.positiveInt("time", 0);
            return Limit.movetime(ms);
        }
        return Limit.depth(a.positiveInt("depth", defaultDepth));
    }

    static UciEngine startEngine(Args a) throws EngineException {
        EngineConfig cfg;
        if (a.has("engine")) {
            Path p = Path.of(a.get("engine"));
            if (!Files.isRegularFile(p) || !Files.isExecutable(p)) throw new CliException("engine not found or not executable: " + p);
            cfg = EngineConfig.of(p);
        } else {
            cfg = EngineConfig.detect().orElseThrow(() -> new CliException(
                "no UCI engine found; install Stockfish, pass --engine PATH, or set " + EngineConfig.ENV));
        }
        if (a.has("threads")) cfg = cfg.withThreads(a.positiveInt("threads", 1));
        if (a.has("hash")) cfg = cfg.withHash(a.positiveInt("hash", 1));
        return new UciEngine(cfg);
    }

    static String score(Score s) {
        return s == null ? "?" : s.toString();
    }

    // ------------------------------------------------------------ analyze

    static int analyze(Args a, Context ctx) throws EngineException {
        if (a.count() == 0) throw new UsageException("missing FEN or moves");
        Fmt.Line line = Fmt.line(a.positionals());
        Limit limit = limit(a, 20);
        int multipv = a.positiveInt("multipv", 1);
        Position end = line.end();
        PrintStream o = ctx.out;
        if (!line.moves().isEmpty()) o.println("Line:   " + line.san());
        o.println("FEN:    " + end.fen());
        if (end.isCheckmate() || end.isStalemate()) {
            o.println(end.isCheckmate() ? "Checkmate: " + (end.sideToMove() == 0 ? "Black" : "White") + " has won" : "Stalemate: draw");
            return 0;
        }
        try (UciEngine engine = startEngine(a)) {
            o.println("Engine: " + engine.name() + " (" + limit + (multipv > 1 ? ", multipv " + multipv : "") + ")");
            Analysis an = engine.analyse(line.start(), line.moves(), limit, multipv);
            o.println();
            if (an.lines().isEmpty()) {
                o.println("(the engine returned no lines)");
                return 1;
            }
            for (PvLine pv : an.lines()) {
                Score white = pv.score().forWhite(end.sideToMove());
                o.printf(Locale.ROOT, "%d. %7s  d%-3d %s%n", pv.multipv(), score(white), pv.depth(),
                    Fmt.numbered(end, pv.san()));
            }
            o.printf(Locale.ROOT, "%n%,d nodes in %.1f s%n", an.nodes(), an.timeMs() / 1000.0);
            return 0;
        }
    }

    // ------------------------------------------------------------ annotate

    static int annotate(Args a, Context ctx) throws EngineException {
        String file = a.require(0, "database file");
        List<Integer> ids = Context.ids(a.rest(1));
        a.exclusive("white-only", "black-only");
        Limit limit = limit(a, 14);
        boolean dryRun = a.has("dry-run");
        GameAnnotator.Options opts = GameAnnotator.Options.defaults().withLimit(limit)
            .withColors(!a.has("black-only"), !a.has("white-only"));
        if (a.has("no-variations")) opts = opts.withVariations(false, 8);
        if (dryRun) opts = opts.reportOnly();
        try (Database db = dryRun ? ctx.openReadOnly(file) : ctx.openWritable(file)) {
            Snapshot snap = db.snapshot();
            for (int id : ids) {
                GameRecord r = snap.find(id);
                if (r == null) throw new CliException("no game with id " + id + "; nothing annotated");
                if (!r.isValid()) throw new CliException("game " + id + " is not valid PGN: " + r.error());
            }
            int done = 0;
            try (UciEngine engine = startEngine(a)) {
                GameAnnotator annotator = new GameAnnotator(engine);
                ctx.err.println("annotating " + ids.size() + " game" + (ids.size() == 1 ? "" : "s") + " with " + engine.name()
                    + " (" + limit + ")" + (dryRun ? ", dry run" : ""));
                for (int i = 0; i < ids.size(); i++) {
                    int id = ids.get(i);
                    GameRecord r = snap.get(id);
                    Game g = snap.game(id);
                    String prefix = "  #" + id + " (" + (i + 1) + "/" + ids.size() + "): ";
                    engine.newGame();
                    // live "\r" progress only on a terminal; logs get one line per game
                    boolean live = System.console() != null;
                    int[] count = new int[2];
                    AnnotationReport rep = annotator.annotate(g, opts, (n, total) -> {
                        count[0] = n;
                        count[1] = total;
                        if (live) ctx.err.print("\r" + prefix + n + "/" + total + " positions");
                    });
                    if (live) ctx.err.println();
                    else ctx.err.println(prefix + count[0] + " positions analysed");
                    if (!dryRun) {
                        db.replace(id, g);
                        db.save();
                    }
                    done++;
                    printReport(ctx.out, r, rep, opts);
                }
            } finally {
                if (!dryRun && done > 0) ctx.err.println("saved " + done + " annotated game" + (done == 1 ? "" : "s") + " to " + file);
            }
            return 0;
        }
    }

    private static void printReport(PrintStream o, GameRecord r, AnnotationReport rep, GameAnnotator.Options opts) {
        o.println("#" + r.id() + "  " + Fmt.players(r) + "  " + r.resultText() + "  " + r.dateText());
        for (AnnotationReport.SideStats s : List.of(rep.white(), rep.black())) {
            if ((s.color() == 0 && !opts.white()) || (s.color() == 1 && !opts.black())) continue;
            o.printf(Locale.ROOT, "  %-5s  accuracy %5.1f%%  acpl %5.1f  inaccuracies %d  mistakes %d  blunders %d%n",
                s.color() == 0 ? "White" : "Black", s.accuracy(), s.averageCentipawnLoss(), s.inaccuracies(), s.mistakes(), s.blunders());
        }
        List<String> bad = new ArrayList<>();
        for (MoveAssessment m : rep.moves()) {
            if (m.classification().ordinal() < com.oriondb.engine.Classification.MISTAKE.ordinal()) continue;
            String mark = m.classification() == com.oriondb.engine.Classification.BLUNDER ? "??" : "?";
            bad.add(m.moveNumber() + (m.color() == 0 ? ". " : "... ") + m.san() + mark
                + (m.bestMoveSan().isEmpty() ? "" : " (" + m.bestMoveSan() + ")") + "  " + m.scoreBefore() + " -> " + m.scoreAfter());
        }
        if (!bad.isEmpty()) {
            o.println("  mistakes and blunders (engine's choice in parentheses, evals for White):");
            for (String b : bad) o.println("    " + b);
        }
    }
}
