import com.oriondb.chess.Position;
import com.oriondb.db.Database;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.engine.AnnotationReport;
import com.oriondb.engine.GameAnnotator;
import com.oriondb.engine.UciEngine;
import com.oriondb.game.Game;
import com.oriondb.query.Query;
import com.oriondb.query.QueryParser;
import com.oriondb.query.Searcher;
import com.oriondb.stats.OpeningExplorer;
import com.oriondb.stats.PlayerReport;

import java.nio.file.Path;
import java.util.List;

/**
 * Library tour. Build the jar ({@code mvn -q package}), then:
 * {@code java -cp target/oriondb.jar examples/ApiExample.java mygames.pgn}
 */
public class ApiExample {
    public static void main(String[] args) throws Exception {
        try (Database db = Database.open(Path.of(args.length > 0 ? args[0] : "examples/famous-games.pgn"))) {
            // Import (deduplicated, labelled, invalid games reported, never half-applied)
            // db.importFile(Path.of("new-games.pgn"), com.oriondb.db.ImportOptions.DEFAULT.withLabels(List.of("inbox")));

            Snapshot snap = db.snapshot();

            // Search: fluent, or the text language used by the CLI and MCP server
            var najdorfWins = Searcher.search(snap, new Query()
                .eco("B90-B99").minElo(2600).sort(Query.Sort.DATE, true).page(0, 10));
            var mates = Searcher.search(snap, QueryParser.parse("motif:smothered_mate sort:-date"));
            System.out.println(najdorfWins.total() + " Najdorfs, " + mates.total() + " smothered mates");

            // Opening explorer from any position (transposition-aware)
            Position pos = OpeningExplorer.positionAfter("1. e4 e5 2. Nf3");
            OpeningExplorer.explore(snap, pos, null, 3).moves().forEach(m ->
                System.out.printf("%-6s %4d games  %5.1f%%%n", m.san(), m.games(), m.score()));

            // Player dossier
            PlayerReport.Report report = PlayerReport.build(snap, "Morphy", 5);
            System.out.println("Morphy: " + report.total());

            // Edit a game: add a variation and a comment, then save atomically
            // (only on a database you named, so the bundled sample stays untouched)
            if (args.length > 0 && snap.size() > 0) {
                GameRecord first = snap.records().get(0);
                Game game = db.game(first.id());
                if (game.find("e4 e5") != null) game.find("e4 e5").play("Nf3").setComment("The main line");
                else game.end().setComment("Reviewed");
                db.replace(first.id(), game);
                db.update(b -> b.updateLabels(first.id(), List.of("studied"), List.of()));
                db.save();

                // Engine annotation (needs Stockfish or ORIONDB_ENGINE)
                try (UciEngine engine = UciEngine.start()) {
                    AnnotationReport r = new GameAnnotator(engine).annotate(db.game(first.id()),
                        GameAnnotator.Options.defaults().reportOnly());
                    System.out.printf("accuracy white %.1f%%, black %.1f%%%n", r.white().accuracy(), r.black().accuracy());
                } catch (Exception e) {
                    System.out.println("no engine: " + e.getMessage());
                }
            }
        }
    }
}
