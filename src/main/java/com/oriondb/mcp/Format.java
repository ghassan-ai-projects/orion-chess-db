package com.oriondb.mcp;

import com.google.gson.JsonObject;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.db.GameRecord;
import com.oriondb.stats.Tally;

import java.util.List;
import java.util.Locale;

/** Compact text and JSON renderings shared by the tools. */
final class Format {
    private Format() {}

    /** "12. Nf3" or "12... Nf6" for {@code move} played in {@code before}. */
    static String moveLabel(Position before, int move) {
        return before.fullmoveNumber() + (before.sideToMove() == 0 ? ". " : "... ") + San.format(before, move);
    }

    /** SAN moves from {@code start} with move numbers: "12... Nf6 13. Nc3". */
    static String numbered(Position start, List<String> san) {
        StringBuilder sb = new StringBuilder();
        int number = start.fullmoveNumber();
        int side = start.sideToMove();
        for (int i = 0; i < san.size(); i++) {
            if (side == 0) sb.append(number).append(". ");
            else if (i == 0) sb.append(number).append("... ");
            sb.append(san.get(i)).append(' ');
            if (side == 1) number++;
            side ^= 1;
        }
        return sb.toString().strip();
    }

    /** Numbered SAN for packed/legal moves played from {@code start}. */
    static String numberedMoves(Position start, int[] moves, int count) {
        List<String> san = new java.util.ArrayList<>();
        Position p = start;
        for (int i = 0; i < count; i++) {
            san.add(San.format(p, moves[i]));
            p = p.play(moves[i]);
        }
        return numbered(start, san);
    }

    static String sideName(int color) {
        return color == 0 ? "white" : "black";
    }

    // ------------------------------------------------------------ game rows

    static JsonObject row(GameRecord r, int ply) {
        JsonObject o = Json.obj("id", r.id(), "white", r.white(), "black", r.black(),
            "white_elo", r.whiteElo() > 0 ? r.whiteElo() : null, "black_elo", r.blackElo() > 0 ? r.blackElo() : null,
            "result", r.resultText(), "date", r.dateText(), "event", blankToNull(r.event()),
            "eco", r.eco(), "opening", r.opening(), "plies", r.plyCount(), "labels", r.labels());
        if (ply >= 0) {
            o.addProperty("match_ply", ply);
            String m = matchMove(r, ply);
            if (m != null) o.addProperty("match_move", m);
        }
        return o;
    }

    static String rowText(GameRecord r, int ply) {
        StringBuilder sb = new StringBuilder("#").append(r.id()).append(' ')
            .append(player(r.white(), r.whiteElo())).append(" - ").append(player(r.black(), r.blackElo()))
            .append("  ").append(r.resultText()).append("  ").append(r.dateText());
        if (!r.event().isBlank() && !r.event().equals("?")) sb.append("  ").append(r.event());
        if (r.eco() != null) sb.append("  ").append(r.eco());
        if (r.opening() != null) sb.append(' ').append(r.opening());
        sb.append("  ").append((r.plyCount() + 1) / 2).append(" moves");
        if (!r.labels().isEmpty()) sb.append("  [").append(String.join(", ", r.labels())).append(']');
        if (ply >= 0) {
            String m = matchMove(r, ply);
            sb.append("  @ply ").append(ply).append(m == null ? " (start)" : " (after " + m + ")");
        }
        return sb.toString();
    }

    /** The mainline move that reached ply {@code ply}, e.g. "12. Nf3"; null for ply 0. */
    static String matchMove(GameRecord r, int ply) {
        if (ply <= 0 || ply > r.plyCount()) return null;
        Position p = r.startPosition();
        for (int i = 0; i < ply - 1; i++) p = p.play(r.move(i));
        return moveLabel(p, r.move(ply - 1));
    }

    static String player(String name, int elo) {
        String n = name == null || name.isBlank() ? "?" : name;
        return elo > 0 ? n + " (" + elo + ")" : n;
    }

    // ------------------------------------------------------------ tallies

    static JsonObject tally(Tally t) {
        return Json.obj("games", t.games(), "wins", t.wins(), "draws", t.draws(), "losses", t.losses(),
            "score_pct", round1(t.percent()),
            "avg_opponent_elo", t.averageOpponentElo() > 0 ? t.averageOpponentElo() : null,
            "performance", t.performance() > 0 ? t.performance() : null);
    }

    static String tallyText(Tally t) {
        String s = String.format(Locale.ROOT, "%d games +%d =%d -%d (%.1f%%)", t.games(), t.wins(), t.draws(), t.losses(), t.percent());
        if (t.performance() > 0) s += ", perf " + t.performance();
        return s;
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() || s.equals("?") ? null : s;
    }

    /** Joins at most {@code max} items, noting how many were left out. */
    static String limited(List<?> items, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, items.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        if (items.size() > max) sb.append(", ... (").append(items.size() - max).append(" more)");
        return sb.toString();
    }
}
