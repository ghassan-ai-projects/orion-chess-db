package com.oriondb.db;

import com.oriondb.chess.Board;
import com.oriondb.model.Game;
import com.oriondb.pgn.PgnWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test that the database opens a realistic collection quickly.
 * Set -Doriondb.perf.games=N to try larger sizes locally.
 */
class DatabasePerfTest {

    static String generatePgn(int gameCount, long seed) {
        Random rng = new Random(seed);
        List<Game> games = new ArrayList<>(gameCount);
        String[] players = {"Adams", "Botvinnik", "Capablanca", "Duda", "Euwe", "Fischer",
            "Geller", "Hort", "Ivanchuk", "Jussupow", "Karpov", "Larsen"};
        for (int i = 0; i < gameCount; i++) {
            Board board = Board.startingPosition();
            var tree = new com.oriondb.model.MoveNode[1];
            com.oriondb.model.MoveNode last = null;
            int targetPlies = 40 + rng.nextInt(80);
            String result = "*";
            for (int ply = 0; ply < targetPlies; ply++) {
                List<Integer> moves = board.legalMoves();
                if (moves.isEmpty()) {
                    result = board.inCheck()
                        ? (board.whiteToMove() ? "0-1" : "1-0")
                        : "1/2-1/2";
                    break;
                }
                int move = moves.get(rng.nextInt(moves.size()));
                String san = board.toSan(move);
                var node = new com.oriondb.model.MoveNode(san);
                if (last == null) {
                    tree[0] = node;
                } else {
                    last.setNext(node);
                }
                last = node;
                board = board.play(move);
            }
            if (result.equals("*")) {
                result = switch (rng.nextInt(3)) {
                    case 0 -> "1-0";
                    case 1 -> "0-1";
                    default -> "1/2-1/2";
                };
            }
            LinkedHashMap<String, String> tags = new LinkedHashMap<>();
            tags.put("Event", "Perf Test " + (i % 7));
            tags.put("Site", "Nowhere");
            tags.put("Date", String.format("%d.%02d.%02d",
                1990 + rng.nextInt(35), 1 + rng.nextInt(12), 1 + rng.nextInt(28)));
            tags.put("Round", String.valueOf(1 + i % 9));
            tags.put("White", players[rng.nextInt(players.length)] + ", A" + (i % 50));
            tags.put("Black", players[rng.nextInt(players.length)] + ", B" + (i % 50));
            tags.put("Result", result);
            tags.put("WhiteElo", String.valueOf(2100 + rng.nextInt(700)));
            tags.put("BlackElo", String.valueOf(2100 + rng.nextInt(700)));
            games.add(new Game(tags, tree[0]));
        }
        return new PgnWriter().write(games);
    }

    @Test
    void openSpeed() throws Exception {
        int count = Integer.getInteger("oriondb.perf.games", 300);
        String pgn = generatePgn(count, 42);
        Path file = Files.createTempFile("orion-perf", ".pgn");
        try {
            Files.writeString(file, pgn);
            long start = System.nanoTime();
            try (Database db = Database.open(file)) {
                db.setSaveOnClose(false);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertEquals(count, db.gameCount());
                System.out.printf("Opened %d games in %d ms (%.0f games/s)%n",
                    count, elapsedMs, count * 1000.0 / Math.max(1, elapsedMs));
                // Generous bound: ~300 synthetic games should open in seconds.
                assertTrue(elapsedMs < 30_000, "open took " + elapsedMs + " ms");

                List<Integer> taggedIds = db.games().stream()
                    .map(Game::id)
                    .filter(id -> id % 10 == 0)
                    .toList();
                List<Integer> controlExcludedIds = db.games().stream()
                    .map(Game::id)
                    .filter(id -> id % 10 != 0)
                    .toList();
                db.addGameTags(taggedIds, List.of("benchmark"));
                db.addGameTags(controlExcludedIds, List.of("control-excluded"));
                int queryCount = Integer.getInteger("oriondb.perf.queries", 200);
                long queryStart = System.nanoTime();
                for (int i = 0; i < queryCount; i++) {
                    assertEquals(taggedIds.size(), db.search().tag("benchmark").count());
                }
                long queryMs = (System.nanoTime() - queryStart) / 1_000_000;
                System.out.printf("%d indexed tag queries in %d ms%n", queryCount, queryMs);
                assertTrue(queryMs < 10_000, "tag queries took " + queryMs + " ms");

                long scanStart = System.nanoTime();
                for (int i = 0; i < queryCount; i++) {
                    assertEquals(
                        taggedIds.size(),
                        db.search().withoutTag("control-excluded").count());
                }
                long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
                System.out.printf("%d full-scan tag queries in %d ms%n", queryCount, scanMs);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
