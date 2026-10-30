package com.oriondb.stats;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.eco.EcoBook;
import com.oriondb.index.PositionIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Move statistics from any position across all games reaching it (in any
 * move order): for each continuation, games, white/draw/black results,
 * score for the side to move, average rating of the players making it,
 * their performance, most recent year, and the strongest example games.
 */
public final class OpeningExplorer {

    public record MoveStats(String san, String uci, int games, int whiteWins, int draws, int blackWins,
                            double score, int averageElo, int performance, int lastYear, List<Integer> topGames) {}

    /**
     * @param games      games reaching the position (after filtering)
     * @param endedHere  games whose mainline ends in this position
     * @param opening    book opening name at this position, if any
     */
    public record Result(String fen, int games, int whiteWins, int draws, int blackWins, int endedHere,
                         EcoBook.Opening opening, List<MoveStats> moves) {}

    private OpeningExplorer() {}

    public static Result explore(Snapshot snap, Position pos, Predicate<GameRecord> filter, int samplesPerMove) {
        Map<Integer, Integer> firstPly = new HashMap<>();
        for (PositionIndex.Hit h : snap.positionHits(pos)) firstPly.merge(h.gameId(), h.ply(), Math::min);

        final class Acc {
            int games, w, d, b, eloSum, eloN, lastYear;
            double moverPoints;
            final Tally mover = new Tally();
            final List<GameRecord> examples = new ArrayList<>();
        }
        Map<Integer, Acc> byMove = new LinkedHashMap<>();
        int total = 0, tw = 0, td = 0, tb = 0, ended = 0;
        int stm = pos.sideToMove();
        for (Map.Entry<Integer, Integer> e : firstPly.entrySet()) {
            GameRecord r = snap.find(e.getKey());
            if (r == null || (filter != null && !filter.test(r))) continue;
            total++;
            int res = r.resultCode();
            if (res == GameRecord.WHITE_WINS) tw++;
            else if (res == GameRecord.DRAW) td++;
            else if (res == GameRecord.BLACK_WINS) tb++;
            int ply = e.getValue();
            if (ply >= r.plyCount()) {
                ended++;
                continue;
            }
            int move = r.move(ply);
            Acc a = byMove.computeIfAbsent(move, k -> new Acc());
            a.games++;
            if (res == GameRecord.WHITE_WINS) a.w++;
            else if (res == GameRecord.DRAW) a.d++;
            else if (res == GameRecord.BLACK_WINS) a.b++;
            int moverElo = stm == 0 ? r.whiteElo() : r.blackElo();
            int oppElo = stm == 0 ? r.blackElo() : r.whiteElo();
            if (moverElo > 0) {
                a.eloSum += moverElo;
                a.eloN++;
            }
            if (res != GameRecord.UNKNOWN) {
                double s = res == GameRecord.DRAW ? 0.5 : (res == GameRecord.WHITE_WINS) == (stm == 0) ? 1 : 0;
                a.mover.add(s, oppElo);
                a.moverPoints += s;
            }
            a.lastYear = Math.max(a.lastYear, r.year());
            a.examples.add(r);
        }
        List<MoveStats> moves = new ArrayList<>();
        boolean chess960 = pos.hasChess960Castling();
        for (Map.Entry<Integer, Acc> e : byMove.entrySet()) {
            Acc a = e.getValue();
            int move = e.getKey();
            if (!pos.isLegal(move)) continue; // defensive: hash collision
            int decided = a.w + a.d + a.b;
            double score = decided == 0 ? 0 : 100.0 * a.moverPoints / decided;
            List<Integer> top = a.examples.stream()
                .sorted(Comparator.comparingInt(OpeningExplorer::strength).reversed().thenComparingInt(GameRecord::id))
                .limit(samplesPerMove).map(GameRecord::id).toList();
            moves.add(new MoveStats(San.format(pos, move), com.oriondb.chess.Uci.format(move, chess960), a.games,
                a.w, a.d, a.b, Math.round(score * 10) / 10.0, a.eloN == 0 ? 0 : a.eloSum / a.eloN,
                a.mover.performance(), a.lastYear, top));
        }
        moves.sort(Comparator.comparingInt(MoveStats::games).reversed().thenComparing(MoveStats::san));
        return new Result(pos.fen(), total, tw, td, tb, ended, EcoBook.get().lookup(pos), moves);
    }

    private static int strength(GameRecord r) {
        int w = r.whiteElo(), b = r.blackElo();
        return w > 0 && b > 0 ? (w + b) / 2 : Math.max(w, b);
    }

    /** Plays SAN moves from the start ("1. e4 c5 2. Nf3") and returns the position. */
    public static Position positionAfter(String moves) {
        Position p = Position.start();
        if (moves == null) return p;
        for (String tok : moves.trim().split("\\s+")) {
            String t = tok.replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty()) continue;
            int m = San.parse(p, t);
            if (Move.isNull(m)) throw new IllegalArgumentException("null moves are not allowed here");
            p = p.play(m);
        }
        return p;
    }
}
