package com.oriondb.mcp;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parsing of positions and move lists given as tool arguments. */
final class Inputs {
    private static final Set<String> RESULTS = Set.of("*", "1-0", "0-1", "1/2-1/2");

    private Inputs() {}

    /** A position plus how it was reached (for engines: repetition history). */
    record PositionInput(Position start, List<Integer> moves, Position position, String description) {}

    /** Schema properties for {@link #position}. */
    static Schema positionSchema() {
        return Schema.object()
            .str("fen", "Position as FEN. With 'moves', the moves are played from this position.")
            .str("moves", "SAN moves from the standard start (or from 'fen'), e.g. \"1. e4 c5 2. Nf3\" or \"e4 c5 Nf3\".")
            .integer("game_id", "Take the position from this game's mainline (with 'ply').", 1, Integer.MAX_VALUE)
            .integer("ply", "With game_id: half-moves played from the game's start (0 = start position; default: the final position).", 0, 10000);
    }

    /** Resolves {@code fen}/{@code moves} or {@code game_id}+{@code ply}; defaults to the start position. */
    static PositionInput position(Args a, Snapshot snap) {
        if (a.has("game_id")) {
            if (a.has("fen") || a.has("moves")) throw new ToolException("give either game_id (+ ply) or fen/moves, not both");
            GameRecord r = game(snap, a.requireInt("game_id"));
            int ply = a.integer("ply", r.plyCount(), 0, 10000);
            if (ply > r.plyCount()) throw new ToolException("game " + r.id() + " has only " + r.plyCount() + " plies (asked for ply " + ply + ")");
            int[] main = r.mainline();
            List<Integer> moves = new ArrayList<>();
            for (int i = 0; i < ply; i++) moves.add(main[i]);
            Position p = r.startPosition();
            for (int m : moves) p = p.play(m);
            String desc = "game #" + r.id() + " after " + (ply == 0 ? "0 moves" : Format.numberedMoves(r.startPosition(), main, ply));
            return new PositionInput(r.startPosition(), moves, p, desc);
        }
        if (a.has("ply")) throw new ToolException("'ply' needs 'game_id'");
        Position start = a.has("fen") ? fen(a.str("fen")) : Position.start();
        List<Integer> moves = moves(start, a.str("moves", ""));
        Position p = start;
        for (int m : moves) p = p.play(m);
        String desc = moves.isEmpty() ? (a.has("fen") ? "FEN" : "start position")
            : Format.numberedMoves(start, moves.stream().mapToInt(Integer::intValue).toArray(), moves.size());
        return new PositionInput(start, moves, p, desc);
    }

    static Position fen(String fen) {
        try {
            return Position.fromFen(fen.strip());
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid FEN '" + fen + "': " + e.getMessage());
        }
    }

    /** Parses SAN (move numbers, results and annotation suffixes allowed). */
    static List<Integer> moves(Position start, String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        Position p = start;
        for (String token : text.trim().split("\\s+")) {
            String t = token.replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty() || RESULTS.contains(t)) continue;
            int m;
            try {
                m = San.parse(p, t);
            } catch (IllegalArgumentException e) {
                throw new ToolException("cannot play '" + t + "' after " + (out.isEmpty() ? "the start" : Format.numberedMoves(start,
                    out.stream().mapToInt(Integer::intValue).toArray(), out.size())) + " (position " + p.fen() + "): " + e.getMessage());
            }
            if (Move.isNull(m)) throw new ToolException("null moves ('--') are not allowed here");
            out.add(m);
            p = p.play(m);
        }
        return out;
    }

    /** A valid game record by id. */
    static GameRecord game(Snapshot snap, int id) {
        GameRecord r = snap.find(id);
        if (r == null) throw new ToolException("no game with id " + id + " (use search_games to find ids)");
        if (!r.isValid()) throw new ToolException("game " + id + " could not be parsed and cannot be used here: " + r.error());
        return r;
    }
}
