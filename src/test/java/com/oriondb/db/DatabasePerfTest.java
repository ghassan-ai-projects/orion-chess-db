package com.oriondb.db;

import com.oriondb.chess.Position;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.query.QueryParser;
import com.oriondb.query.Searcher;
import com.oriondb.stats.OpeningExplorer;
import com.oriondb.stats.PlayerReport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Opt-in scale profile: {@code mvn test -Dtest=DatabasePerfTest -Doriondb.slow=true -Doriondb.perf.games=50000}.
 * Generates random legal games (biased toward a few openings so positions repeat).
 */
class DatabasePerfTest {
    private static final String[] OPENINGS = {
        "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6", "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6", "d4 Nf6 c4 e6 Nc3 Bb4",
        "d4 d5 c4 e6 Nc3 Nf6", "e4 e6 d4 d5 Nc3 Bb4", "c4 e5 Nc3 Nf6 g3", "Nf3 d5 g3 Nf6 Bg2", "e4 c6 d4 d5 e5 Bf5",
    };

    @Test
    void profile(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("oriondb.slow"));
        int n = Integer.getInteger("oriondb.perf.games", 20000);
        Path file = dir.resolve("perf.pgn");
        SplittableRandom rnd = new SplittableRandom(42);
        long t0 = System.nanoTime();
        long plies = 0;
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            for (int i = 0; i < n; i++) {
                Game g = Game.fromMoves(OPENINGS[rnd.nextInt(OPENINGS.length)]);
                GameNode node = g.end();
                int len = 40 + rnd.nextInt(100);
                while (node.depth() < len) {
                    int[] legal = node.position().legalMoves();
                    if (legal.length == 0) break;
                    node = node.play(legal[rnd.nextInt(legal.length)]);
                }
                plies += node.depth();
                g.tags().set("White", "Player " + rnd.nextInt(500));
                g.tags().set("Black", "Player " + rnd.nextInt(500));
                g.tags().set("WhiteElo", String.valueOf(1800 + rnd.nextInt(1000)));
                g.tags().set("BlackElo", String.valueOf(1800 + rnd.nextInt(1000)));
                g.tags().set("Date", (1990 + rnd.nextInt(35)) + ".0" + (1 + rnd.nextInt(9)) + ".1" + rnd.nextInt(10));
                g.tags().set("Event", "Event " + rnd.nextInt(200));
                g.tags().set("Result", new String[] {"1-0", "0-1", "1/2-1/2"}[rnd.nextInt(3)]);
                w.write(PgnWriter.write(g));
                w.write('\n');
            }
        }
        report("generated %d games (%d plies, %d MB)", t0, n, plies, Files.size(file) >> 20);

        t0 = System.nanoTime();
        try (Database db = Database.open(file)) {
            report("first open (parse + analyse + index) %d games", t0, db.size());
        }
        t0 = System.nanoTime();
        Database db = Database.open(file);
        report("reopen from cache, %d indexed positions", t0, db.snapshot().indexedPositions());
        Snapshot s = db.snapshot();

        Position najdorf = OpeningExplorer.positionAfter(OPENINGS[0]);
        for (String q : List.of("player:\"player 7\"", "elo:2600 elomode:both year:2000..2010", "eco:B90-B99 result:1-0",
                "material:\"KR vs KR\"", "pieces:<=6", "motif:fork,pin", "structure:iqp", "pattern:\"Qh7 kg8\"",
                "fen:\"" + najdorf.fen() + "\" elo:2500")) {
            t0 = System.nanoTime();
            int total = Searcher.search(s, QueryParser.parse(q)).total();
            report("search %-45s -> %d", t0, q, total);
        }
        t0 = System.nanoTime();
        OpeningExplorer.Result ex = OpeningExplorer.explore(s, Position.start(), null, 3);
        report("explorer at start: %d games, %d moves", t0, ex.games(), ex.moves().size());
        t0 = System.nanoTime();
        PlayerReport.build(s, "Player 7", 10);
        report("player report", t0);

        t0 = System.nanoTime();
        db.update(b -> b.updateLabels(1, List.of("x"), List.of()));
        db.save();
        report("label one game + save (journal append)", t0);
        t0 = System.nanoTime();
        for (int i = 2; i < 102; i++) {
            int id = i;
            db.update(b -> b.setTag(id, "Annotator", "perf"));
            db.save();
        }
        report("100 edits, each saved", t0);
        t0 = System.nanoTime();
        Searcher.search(db.snapshot(), QueryParser.parse("(eco:B90-B99 OR eco:C00-C19) -result:draw move:O-O-O"));
        report("search with OR/NOT + move scan", t0);
        t0 = System.nanoTime();
        db.compact();
        report("compact (full PGN rewrite)", t0);
        db.close();
    }

    private static void report(String fmt, long t0, Object... args) {
        System.out.printf("%-80s %6d ms%n", String.format(fmt, args), (System.nanoTime() - t0) / 1_000_000);
    }
}
