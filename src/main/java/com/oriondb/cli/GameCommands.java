package com.oriondb.cli;

import com.oriondb.analysis.Motif;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.db.Database;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.eco.EcoBook;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;
import com.oriondb.stats.Duplicates;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Single-game commands: game, board, tag, delete, dedupe. */
final class GameCommands {
    private GameCommands() {}

    static List<Command> commands() {
        return List.of(
            new Command("game", "<db> <id> [--pgn] [--ply N] [--flip]",
                "Show one game: headers, labels, moves and tactical motifs",
                """
                Options:
                  --pgn      print the stored PGN text instead
                  --ply N    also show the board after N half-moves (0 = start)
                  --flip     show the board from Black's side

                Examples:
                  oriondb game my.pgn 42
                  oriondb game my.pgn 42 --ply 31
                """,
                Args.Spec.of("pgn", "ply=", "flip"), GameCommands::game),
            new Command("board", "<fen | \"1. e4 e5 ...\"> [--flip]",
                "Show a position (FEN and/or moves): diagram, legal moves, opening name",
                """
                Accepts a FEN, SAN moves from the start, or a FEN followed by moves.
                No database is needed.

                Examples:
                  oriondb board "1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6"
                  oriondb board "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3" Bb5
                """,
                Args.Spec.of("flip"), GameCommands::board),
            new Command("tag", "<db> <id> <Name> <value> | <db> <id> <Name> --remove",
                "Set or remove a PGN header tag of a game",
                """
                Examples:
                  oriondb tag my.pgn 42 Annotator "Kasparov, Garry"
                  oriondb tag my.pgn 42 Annotator --remove
                OrionId/OrionLabels are managed by OrionDB (use 'label'); FEN, SetUp and
                Variant cannot be edited alone.
                """,
                Args.Spec.of("remove"), GameCommands::tag),
            new Command("delete", "<db> <ids>",
                "Delete games by id (e.g. 7 or 3,9,12 or 20-30)",
                """
                Deletes the games and saves. The previous file is kept as <db>.bak.

                Example:
                  oriondb delete my.pgn 3,9,20-25
                """,
                Args.Spec.of(), GameCommands::delete),
            new Command("dedupe", "<db> [--apply] [--any-players] [--min-plies N]",
                "Find duplicate games (dry run); --apply deletes the redundant copies",
                """
                Games with identical moves and players form a group; the copy with the
                most annotations and metadata is kept.

                Options:
                  --apply          delete the redundant copies and save (default: only list them)
                  --any-players    ignore player names (same moves suffice)
                  --min-plies N    ignore games shorter than N plies
                                   (default 0, or 20 with --any-players)
                """,
                Args.Spec.of("apply", "any-players", "min-plies="), GameCommands::dedupe));
    }

    // ------------------------------------------------------------ game

    static int game(Args a, Context ctx) {
        String file = a.require(0, "database file");
        int id = Context.id(a.require(1, "game id"));
        a.maxPositionals(2);
        try (Database db = ctx.openReadOnly(file)) {
            Snapshot snap = db.snapshot();
            GameRecord r = snap.find(id);
            if (r == null) throw new CliException("no game with id " + id + " in " + file);
            PrintStream o = ctx.out;
            if (a.has("pgn")) {
                o.println(snap.pgn(id).strip());
                return 0;
            }
            if (!r.isValid()) {
                o.println("Game #" + id + " could not be parsed: " + r.error());
                o.println();
                o.println(snap.pgn(id).strip());
                return 1;
            }
            Game g = snap.game(id);
            o.println("Game #" + id + "   " + Fmt.players(r) + "   " + r.resultText());
            for (Map.Entry<String, String> e : r.tags().entrySet()) {
                o.printf("  %-12s %s%n", e.getKey(), e.getValue());
            }
            if (!r.labels().isEmpty()) o.println("  Labels       " + String.join(", ", r.labels()));
            if (r.eco() != null || r.opening() != null) {
                o.println("  Opening      " + Fmt.orDash(r.eco()) + (r.opening() == null ? "" : " " + r.opening()));
            }
            o.println();
            o.println(Fmt.wrap(movetext(g), 78, "  "));
            List<String> extras = new ArrayList<>();
            if (r.has(GameRecord.HAS_COMMENTS)) extras.add("comments");
            if (r.has(GameRecord.HAS_VARIATIONS)) extras.add("variations");
            if (r.has(GameRecord.HAS_EVALS)) extras.add("engine evals");
            if (!extras.isEmpty()) o.println("  (has " + String.join(", ", extras) + "; --pgn shows them)");
            List<int[]> hits = r.motifHits().stream()
                .filter(h -> h[1] != Motif.CASTLING_KINGSIDE.ordinal() && h[1] != Motif.CASTLING_QUEENSIDE.ordinal()).toList();
            if (!hits.isEmpty()) {
                o.println();
                o.println("Motifs:");
                Position[] pos = r.positions();
                int lastPly = -1;
                StringBuilder sb = null;
                for (int[] h : hits) {
                    if (h[0] != lastPly) {
                        if (sb != null) o.println(sb);
                        sb = new StringBuilder("  ").append(String.format("%-14s", Fmt.moveLabel(pos[h[0] - 1], r.move(h[0] - 1))));
                        lastPly = h[0];
                    } else {
                        sb.append(", ");
                    }
                    sb.append(Motif.values()[h[1]].key());
                }
                o.println(sb);
            }
            if (a.has("ply")) {
                int ply = a.intValue("ply", 0);
                if (ply < 0 || ply > r.plyCount()) throw new UsageException("--ply must be 0.." + r.plyCount());
                Position p = r.positions()[ply];
                o.println();
                o.println(ply == 0 ? "Start position:" : "After " + Fmt.moveLabel(r, ply) + " (ply " + ply + "):");
                o.println(p.diagram(a.has("flip")));
                o.println("FEN: " + p.fen());
            }
            return 0;
        }
    }

    /** Numbered mainline with NAG symbols. */
    static String movetext(Game g) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (GameNode n : g.mainline()) {
            Position before = n.parent().position();
            if (before.sideToMove() == 0) sb.append(before.fullmoveNumber()).append(". ");
            else if (first) sb.append(before.fullmoveNumber()).append("... ");
            sb.append(n.san());
            for (int nag : n.nags()) {
                String s = Nag.symbol(nag);
                sb.append(s.startsWith("$") || !Nag.isMoveAssessment(nag) ? " " + s : s);
            }
            sb.append(' ');
            first = false;
        }
        sb.append(g.result());
        return sb.toString();
    }

    // ------------------------------------------------------------ board

    static int board(Args a, Context ctx) {
        if (a.count() == 0) throw new UsageException("missing FEN or moves");
        Fmt.Line line = Fmt.line(a.positionals());
        Position p = line.end();
        PrintStream o = ctx.out;
        if (!line.moves().isEmpty()) o.println(line.san());
        o.println(p.diagram(a.has("flip")));
        o.println();
        o.println("FEN:     " + p.fen());
        String state = p.isCheckmate() ? "checkmate" : p.isStalemate() ? "stalemate"
            : p.isCheck() ? "in check" : p.isInsufficientMaterial() ? "insufficient material" : "";
        o.println("To move: " + (p.sideToMove() == 0 ? "White" : "Black") + (state.isEmpty() ? "" : " (" + state + ")"));
        EcoBook.Opening op = EcoBook.get().lookup(p);
        if (op == null && line.start().equals(Position.start()) && !line.moves().isEmpty()) {
            op = EcoBook.get().classify(line.positions().toArray(Position[]::new));
            if (op != null) o.println("Opening: " + op.eco() + " " + op.name() + " (deepest book position on the line)");
        } else if (op != null) {
            o.println("Opening: " + op.eco() + " " + op.name());
        }
        int[] legal = p.legalMoves();
        List<String> sans = new ArrayList<>();
        for (int m : legal) sans.add(San.format(p, m));
        java.util.Collections.sort(sans);
        o.println("Legal:   " + (sans.isEmpty() ? "none" : sans.size() + " moves"));
        if (!sans.isEmpty()) o.println(Fmt.wrap(String.join(" ", sans), 78, "  "));
        return 0;
    }

    // ------------------------------------------------------------ tag

    static int tag(Args a, Context ctx) {
        String file = a.require(0, "database file");
        int id = Context.id(a.require(1, "game id"));
        String name = a.require(2, "tag name");
        String value;
        if (a.has("remove")) {
            a.maxPositionals(3);
            value = null;
        } else {
            List<String> rest = a.rest(3);
            if (rest.isEmpty()) throw new UsageException("missing tag value (or --remove)");
            value = String.join(" ", rest);
        }
        try (Database db = ctx.openWritable(file)) {
            String old = db.snapshot().get(id).tag(name);
            db.setTag(id, name, value);
            ctx.save(db);
            if (value == null) {
                ctx.out.println(old == null ? "game " + id + " had no " + name + " tag" : "game " + id + ": removed " + name + " (was \"" + old + "\")");
            } else {
                ctx.out.println("game " + id + ": " + name + " = \"" + value + "\"" + (old == null ? "" : " (was \"" + old + "\")"));
            }
            return 0;
        }
    }

    // ------------------------------------------------------------ delete

    static int delete(Args a, Context ctx) {
        String file = a.require(0, "database file");
        List<Integer> ids = Context.ids(a.rest(1));
        try (Database db = ctx.openWritable(file)) {
            Snapshot snap = db.snapshot();
            List<Integer> missing = ids.stream().filter(i -> snap.find(i) == null).toList();
            if (!missing.isEmpty()) throw new CliException("no game with id " + join(missing) + "; nothing deleted");
            for (int i : ids) ctx.out.println("deleting #" + snap.get(i));
            db.delete(ids);
            db.save();
            ctx.out.println("deleted " + ids.size() + " game" + (ids.size() == 1 ? "" : "s") + "; " + db.size() + " left");
            return 0;
        }
    }

    // ------------------------------------------------------------ dedupe

    static int dedupe(Args a, Context ctx) {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        boolean any = a.has("any-players"), apply = a.has("apply");
        int minPlies = a.intValue("min-plies", any ? 20 : 0);
        try (Database db = apply ? ctx.openWritable(file) : ctx.openReadOnly(file)) {
            Snapshot snap = db.snapshot();
            List<Duplicates.Group> groups = Duplicates.find(snap, !any, minPlies);
            if (groups.isEmpty()) {
                ctx.out.println("no duplicates among " + snap.size() + " games");
                return 0;
            }
            List<Integer> redundant = new ArrayList<>();
            for (Duplicates.Group g : groups) {
                GameRecord keep = snap.get(g.keep());
                ctx.out.println("keep #" + keep.id() + "  " + Fmt.players(keep) + "  " + keep.resultText() + "  " + keep.dateText()
                    + "   " + (apply ? "deleting" : "duplicates") + ": " + join(g.redundant()));
                redundant.addAll(g.redundant());
            }
            ctx.out.println(groups.size() + " group" + (groups.size() == 1 ? "" : "s") + ", " + redundant.size()
                + " redundant cop" + (redundant.size() == 1 ? "y" : "ies"));
            if (!apply) {
                ctx.out.println("dry run: nothing deleted; rerun with --apply to delete the redundant copies");
                return 0;
            }
            db.delete(redundant);
            db.save();
            ctx.out.println("deleted " + redundant.size() + "; " + db.size() + " games left");
            return 0;
        }
    }

    static String join(List<Integer> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }
}
