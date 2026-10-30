package com.oriondb.cli;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.db.GameRecord;
import com.oriondb.stats.Tally;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text formatting shared by commands, and parsing of positions given on the command line. */
final class Fmt {
    private Fmt() {}

    /** A start position plus moves played from it. */
    record Line(Position start, List<Integer> moves, List<Position> positions) {
        Position end() {
            return positions.get(positions.size() - 1);
        }

        String san() {
            List<String> sans = new ArrayList<>();
            for (int i = 0; i < moves.size(); i++) sans.add(San.format(positions.get(i), moves.get(i)));
            return numbered(start, sans);
        }
    }

    /**
     * Parses command-line tokens: an optional FEN (first token contains '/';
     * its fields may be split across tokens) followed by SAN moves with
     * optional move numbers ("1. e4 e5 2. Nf3" or "e4 e5 Nf3").
     */
    static Line line(List<String> tokens) {
        return line(null, tokens);
    }

    /** As {@link #line(List)}, starting from {@code fen} when given. */
    static Line line(String fen, List<String> tokens) {
        List<String> words = new ArrayList<>();
        for (String t : tokens) for (String w : t.trim().split("\\s+")) if (!w.isEmpty()) words.add(w);
        Position start = Position.start();
        int i = 0;
        if (fen != null) {
            start = fen(fen);
        } else if (!words.isEmpty() && words.get(0).contains("/")) {
            // the longest prefix (up to six fields) that parses as a FEN
            Position found = null;
            int used = 0;
            for (int k = Math.min(6, words.size()); k >= 1 && found == null; k--) {
                try {
                    found = Position.fromFen(String.join(" ", words.subList(0, k)));
                    used = k;
                } catch (IllegalArgumentException e) {
                    // try fewer fields
                }
            }
            if (found == null) fen(String.join(" ", words.subList(0, Math.min(6, words.size())))); // throws with the reason
            start = found;
            i = used;
        }
        List<Integer> moves = new ArrayList<>();
        List<Position> positions = new ArrayList<>();
        positions.add(start);
        Position p = start;
        for (; i < words.size(); i++) {
            String t = words.get(i).replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty() || t.equals("*") || t.equals("1-0") || t.equals("0-1") || t.equals("1/2-1/2")) continue;
            int m;
            try {
                m = San.parse(p, t);
            } catch (IllegalArgumentException e) {
                throw new UsageException("illegal or unknown move '" + words.get(i) + "' after "
                    + (moves.isEmpty() ? "the start" : new Line(start, moves, positions).san()) + ": " + e.getMessage());
            }
            if (Move.isNull(m)) throw new UsageException("null moves are not allowed here");
            moves.add(m);
            p = p.play(m);
            positions.add(p);
        }
        return new Line(start, List.copyOf(moves), List.copyOf(positions));
    }

    static Position fen(String fen) {
        try {
            return Position.fromFen(fen.strip());
        } catch (IllegalArgumentException e) { // includes ChessFormatException
            throw new UsageException("bad FEN '" + fen.strip() + "': " + e.getMessage());
        }
    }

    /** "1. e4 e5 2. Nf3" (or "12... Kd8 13. Qf6+") from SAN moves played from {@code start}. */
    static String numbered(Position start, List<String> sans) {
        StringBuilder sb = new StringBuilder();
        int number = start.fullmoveNumber();
        int side = start.sideToMove();
        for (int i = 0; i < sans.size(); i++) {
            if (side == 0) sb.append(number).append(". ");
            else if (i == 0) sb.append(number).append("... ");
            sb.append(sans.get(i)).append(' ');
            if (side == 1) number++;
            side ^= 1;
        }
        return sb.toString().strip();
    }

    /** Label of the move reaching ply {@code ply} of a game's mainline: "16. Qb8+" / "15... Nxd7"; "start" for 0. */
    static String moveLabel(GameRecord r, int ply) {
        if (ply <= 0) return "start";
        if (ply > r.plyCount()) return "ply " + ply;
        Position p = r.startPosition();
        for (int i = 0; i < ply - 1; i++) p = p.play(r.move(i));
        return moveLabel(p, r.move(ply - 1));
    }

    static String moveLabel(Position before, int move) {
        return before.fullmoveNumber() + (before.sideToMove() == 0 ? ". " : "... ") + San.format(before, move);
    }

    static String player(String name, int elo) {
        String n = name == null || name.isBlank() ? "?" : name;
        return elo > 0 ? n + " (" + elo + ")" : n;
    }

    static String players(GameRecord r) {
        return player(r.white(), r.whiteElo()) + " - " + player(r.black(), r.blackElo());
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    static String wdl(int w, int d, int l) {
        return "+" + w + " =" + d + " -" + l;
    }

    static String tally(Tally t) {
        if (t.games() == 0) return "no games";
        String s = t.games() + " game" + (t.games() == 1 ? "" : "s") + "  " + wdl(t.wins(), t.draws(), t.losses())
            + "  " + pct(t.percent());
        if (t.performance() > 0) s += "  perf " + t.performance();
        return s;
    }

    static String years(int first, int last) {
        if (first == 0) return "unknown";
        return first == last ? String.valueOf(first) : first + "–" + last;
    }

    static String orDash(int v) {
        return v > 0 ? String.valueOf(v) : "-";
    }

    static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** Words wrapped to {@code width} columns, each line prefixed by {@code indent}. */
    static String wrap(String text, int width, String indent) {
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) continue;
            if (col > 0 && col + 1 + w.length() > width) {
                sb.append('\n');
                col = 0;
            }
            if (col == 0) {
                sb.append(indent).append(w);
                col = indent.length() + w.length();
            } else {
                sb.append(' ').append(w);
                col += 1 + w.length();
            }
        }
        return sb.toString();
    }

    static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }
}
