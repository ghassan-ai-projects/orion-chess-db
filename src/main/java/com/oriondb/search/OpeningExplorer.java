package com.oriondb.search;

import com.oriondb.chess.Board;
import com.oriondb.db.Database;
import com.oriondb.eco.EcoClassifier;
import com.oriondb.model.Game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChessBase-style opening explorer: for any position, every continuation
 * played in the database with game counts, score, average rating of the
 * side playing the move, and sample games. Transposition aware — games
 * reaching the position by any move order are included.
 */
public final class OpeningExplorer {

    /** Statistics for one continuation from the explored position. */
    public record MoveStat(
        String san,
        int games,
        int whiteWins,
        int draws,
        int blackWins,
        /** Score from the perspective of the side to move, 0..100. */
        double scorePercent,
        /** Average Elo of the players who chose this move, or null. */
        Integer averageElo,
        /** Latest date this was played (sortable form), or null. */
        String lastPlayed,
        /** Sample game ids, strongest players first. */
        List<Integer> sampleGames
    ) {}

    /** Explorer result for a position. */
    public record Result(
        String fen,
        boolean whiteToMove,
        /** Book opening name for this exact position, if any. */
        String openingEco,
        String openingName,
        int totalGames,
        int whiteWins,
        int draws,
        int blackWins,
        List<MoveStat> moves,
        /** Games that ended in (or whose mainline ends at) this position. */
        List<Integer> gamesEndingHere
    ) {}

    private static final int SAMPLE_LIMIT = 5;

    private final Database db;

    public OpeningExplorer(Database db) {
        this.db = db;
    }

    /** Explore the position after a SAN sequence from the start ("1. e4 c5 ..."). */
    public Result explore(String sanLine) {
        return explore(Search.positionAfter(sanLine == null ? "" : sanLine));
    }

    /** Explore a FEN position. */
    public Result exploreFen(String fen) {
        return explore(Board.fromFen(fen));
    }

    public Result explore(Board position) {
        long hash = position.zobrist();
        boolean whiteToMove = position.whiteToMove();

        final class Tally {
            int games, whiteWins, draws, blackWins;
            long eloSum;
            int eloCount;
            String lastPlayed;
            final List<int[]> samples = new ArrayList<>(); // {gameId, sortElo}

            void add(Game game) {
                games++;
                switch (game.result()) {
                    case "1-0": whiteWins++; break;
                    case "0-1": blackWins++; break;
                    case "1/2-1/2": draws++; break;
                    default: break;
                }
                Integer moverElo = whiteToMove ? game.whiteElo() : game.blackElo();
                if (moverElo != null) {
                    eloSum += moverElo;
                    eloCount++;
                }
                String date = game.sortableDate();
                if (date != null && (lastPlayed == null || date.compareTo(lastPlayed) > 0)) {
                    lastPlayed = date;
                }
                Integer avg = game.averageElo();
                samples.add(new int[]{game.id(), avg != null ? avg : 0});
            }
        }

        Map<String, Tally> byMove = new HashMap<>();
        Tally total = new Tally();
        List<Integer> endingHere = new ArrayList<>();

        for (long entry : db.positionEntries(hash)) {
            int gameId = Database.entryGameId(entry);
            int ply = Database.entryPly(entry);
            Game game = db.game(gameId);
            if (game == null) continue;

            total.add(game);
            List<String> sans = game.mainlineSans();
            if (ply < sans.size()) {
                byMove.computeIfAbsent(sans.get(ply), k -> new Tally()).add(game);
            } else {
                endingHere.add(gameId);
            }
        }

        List<MoveStat> moves = new ArrayList<>(byMove.size());
        for (Map.Entry<String, Tally> entry : byMove.entrySet()) {
            Tally t = entry.getValue();
            double points = whiteToMove
                ? t.whiteWins + t.draws / 2.0
                : t.blackWins + t.draws / 2.0;
            int decided = t.whiteWins + t.draws + t.blackWins;
            double scorePct = decided == 0 ? 0 : 100.0 * points / decided;
            t.samples.sort((a, b) -> Integer.compare(b[1], a[1]));
            List<Integer> sampleIds = new ArrayList<>(Math.min(SAMPLE_LIMIT, t.samples.size()));
            for (int i = 0; i < t.samples.size() && i < SAMPLE_LIMIT; i++) {
                sampleIds.add(t.samples.get(i)[0]);
            }
            moves.add(new MoveStat(
                entry.getKey(), t.games, t.whiteWins, t.draws, t.blackWins,
                Math.round(scorePct * 10) / 10.0,
                t.eloCount > 0 ? (int) (t.eloSum / t.eloCount) : null,
                t.lastPlayed,
                sampleIds));
        }
        moves.sort(Comparator.comparingInt(MoveStat::games).reversed()
            .thenComparing(MoveStat::san));

        EcoClassifier.Opening opening = EcoClassifier.instance().lookup(hash);

        return new Result(
            position.toFen(), whiteToMove,
            opening != null ? opening.eco() : null,
            opening != null ? opening.name() : null,
            total.games, total.whiteWins, total.draws, total.blackWins,
            moves, endingHere);
    }
}
