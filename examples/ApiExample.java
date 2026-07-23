import com.oriondb.analysis.Motifs;
import com.oriondb.db.Database;
import com.oriondb.model.Game;
import com.oriondb.search.OpeningExplorer;
import com.oriondb.search.PlayerStats;
import com.oriondb.search.Search;

import java.nio.file.Path;
import java.util.List;

/**
 * OrionDB v2 library usage. Compile with oriondb.jar on the classpath:
 *
 *   javac -cp target/oriondb.jar examples/ApiExample.java -d /tmp/ex
 *   java  -cp target/oriondb.jar:/tmp/ex ApiExample
 */
public class ApiExample {
    public static void main(String[] args) throws Exception {
        // A database is a plain PGN file. Opening validates every game,
        // normalizes SAN, and indexes positions, material, and tactics.
        try (Database db = Database.open(Path.of("examples/famous-games.pgn"))) {
            db.setSaveOnClose(false);
            System.out.println("Games: " + db.gameCount());

            // --- Fluent search -------------------------------------------
            List<Game> mates = db.search()
                .motif("checkmate")
                .sortBy(Search.Sort.DATE, false)
                .find();
            System.out.println("\nGames ending in mate:");
            mates.forEach(g -> System.out.println("  " + g));

            // Position search is transposition aware.
            int italians = db.search()
                .openingLine("1. e4 e5 2. Nf3")
                .count();
            System.out.println("\nOpen games after 2.Nf3: " + italians);

            // Endgame hunting by material signature.
            List<Game> rookEndings = db.search().material("KRP vs KR").find();
            System.out.println("Rook endings: " + rookEndings.size());

            // --- Opening explorer ----------------------------------------
            OpeningExplorer.Result tree = db.explorer().explore("1. e4 e5");
            System.out.println("\nAfter 1.e4 e5 (" + tree.totalGames() + " games):");
            for (OpeningExplorer.MoveStat move : tree.moves()) {
                System.out.printf("  %-6s %d games, %.1f%% for White%n",
                    move.san(), move.games(), move.scorePercent());
            }

            // --- Player dossier ------------------------------------------
            PlayerStats.Report morphy = db.players().report("Morphy");
            if (morphy != null) {
                System.out.printf("%n%s: %d games, %.1f%% as White%n",
                    morphy.name(), morphy.totalGames(), morphy.asWhite().percent());
            }

            // --- Tactical moments of one game ----------------------------
            Game opera = db.search().player("Morphy").find().get(0);
            db.addGameTags(opera.id(), "classic", "tactics");
            System.out.println("Tagged classics: " + db.search().tag("classic").count());
            System.out.println("\nTactics in " + opera + ":");
            for (Motifs.Moment moment : Motifs.moments(opera)) {
                System.out.printf("  move %d: %s  [%s]%n",
                    moment.ply() / 2 + 1, moment.san(),
                    String.join(", ", moment.motifNames()));
            }
        }
    }
}
