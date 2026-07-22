package com.oriondb.eco;

import com.oriondb.chess.Board;
import com.oriondb.chess.Move;
import com.oriondb.model.Game;
import com.oriondb.model.Games;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * ECO opening classification using the lichess chess-openings book
 * (3,800+ named lines, CC0 licensed), embedded as a resource.
 *
 * The book is keyed by Zobrist hash, so classification is transposition
 * aware: a game is labeled by the deepest book position it reaches on its
 * mainline, regardless of move order. Loaded lazily on first use.
 */
public final class EcoClassifier {

    /** A classified opening. */
    public record Opening(String eco, String name, int ply) {}

    private static volatile EcoClassifier instance;

    private final Map<Long, Opening> byPosition;

    public static EcoClassifier instance() {
        EcoClassifier local = instance;
        if (local == null) {
            synchronized (EcoClassifier.class) {
                local = instance;
                if (local == null) {
                    local = new EcoClassifier();
                    instance = local;
                }
            }
        }
        return local;
    }

    private EcoClassifier() {
        this.byPosition = load();
    }

    private static Map<Long, Opening> load() {
        Map<Long, Opening> book = new HashMap<>(8192);
        InputStream in = EcoClassifier.class.getResourceAsStream("openings.tsv");
        if (in == null) {
            return book; // classification quietly disabled
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                int tab1 = line.indexOf('\t');
                int tab2 = line.indexOf('\t', tab1 + 1);
                if (tab1 < 0 || tab2 < 0) continue;
                String eco = line.substring(0, tab1);
                String name = line.substring(tab1 + 1, tab2);
                String pgn = line.substring(tab2 + 1);

                Board board = Board.startingPosition();
                int ply = 0;
                boolean ok = true;
                for (String tokenText : pgn.split("\\s+")) {
                    if (tokenText.isEmpty() || Character.isDigit(tokenText.charAt(0))) {
                        continue; // move numbers
                    }
                    int move = board.parseSan(tokenText);
                    if (move == Move.NONE) {
                        ok = false;
                        break;
                    }
                    board = board.play(move);
                    ply++;
                }
                if (ok && ply > 0) {
                    Opening opening = new Opening(eco, name, ply);
                    // Deepest book line wins for a given position.
                    book.merge(board.zobrist(), opening,
                        (a, b) -> a.ply() >= b.ply() ? a : b);
                }
            }
        } catch (IOException e) {
            // Leave whatever loaded so far.
        }
        return book;
    }

    /** Number of book positions. */
    public int size() {
        return byPosition.size();
    }

    /** Look up a single position; null if it is not a book position. */
    public Opening lookup(long zobrist) {
        return byPosition.get(zobrist);
    }

    /**
     * Classify a game by the deepest book position on its mainline.
     * Returns null for games that never touch the book.
     */
    public Opening classify(Game game) {
        return classify(Games.mainlineHashes(game));
    }

    /**
     * Classify from precomputed mainline hashes (index 0 = start position).
     * The book is only ~35 plies deep, so scanning stops early.
     */
    public Opening classify(long[] mainlineHashes) {
        Opening best = null;
        int limit = Math.min(mainlineHashes.length, 40);
        for (int i = 1; i < limit; i++) {
            Opening hit = byPosition.get(mainlineHashes[i]);
            if (hit != null && (best == null || hit.ply() >= best.ply())) {
                best = hit;
            }
        }
        return best;
    }
}
