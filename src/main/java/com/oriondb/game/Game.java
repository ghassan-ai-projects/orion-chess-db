package com.oriondb.game;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;

import java.util.ArrayList;
import java.util.List;

/** A game: tag pairs plus a tree of moves with annotations. */
public final class Game {
    private final Tags tags = new Tags();
    private final GameNode root;

    public Game() {
        this(Position.start());
    }

    public Game(Position start) {
        root = new GameNode(null, Move.NONE, start);
        if (!start.equals(Position.start()) || start.fullmoveNumber() != 1) {
            tags.set("SetUp", "1");
            tags.set("FEN", start.fen());
        }
    }

    /** Builds a game from SAN moves ("1. e4 e5 2. Nf3" or "e4 e5 Nf3"). */
    public static Game fromMoves(String moves) {
        return fromMoves(Position.start(), moves);
    }

    public static Game fromMoves(Position start, String moves) {
        Game g = new Game(start);
        GameNode n = g.root;
        for (String token : moves.trim().split("\\s+")) {
            String t = token.replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty() || t.equals("*") || t.equals("1-0") || t.equals("0-1") || t.equals("1/2-1/2")) continue;
            n = n.play(San.parse(n.position(), t));
        }
        return g;
    }

    public Tags tags() {
        return tags;
    }

    public String tag(String name) {
        return tags.get(name);
    }

    public GameNode root() {
        return root;
    }

    public Position startPosition() {
        return root.position();
    }

    public String result() {
        return tags.get("Result", "*");
    }

    /** Variant tag says Chess960, or castling rights only make sense in Chess960. */
    public boolean isChess960() {
        String v = tags.get("Variant", "").toLowerCase();
        return v.contains("960") || v.contains("fischer") || root.position().hasChess960Castling();
    }

    /** Mainline nodes after the root. */
    public List<GameNode> mainline() {
        List<GameNode> out = new ArrayList<>();
        for (GameNode n = root.next(); n != null; n = n.next()) out.add(n);
        return out;
    }

    public int[] mainlineMoves() {
        List<GameNode> line = mainline();
        int[] moves = new int[line.size()];
        for (int i = 0; i < moves.length; i++) moves[i] = line.get(i).move();
        return moves;
    }

    /** Mainline SAN, e.g. "1. e4 e5 2. Nf3". */
    public String mainlineSan() {
        StringBuilder sb = new StringBuilder();
        for (GameNode n : mainline()) {
            Position before = n.parent().position();
            if (before.sideToMove() == 0) sb.append(before.fullmoveNumber()).append(". ");
            else if (sb.isEmpty()) sb.append(before.fullmoveNumber()).append("... ");
            sb.append(n.san()).append(' ');
        }
        return sb.toString().strip();
    }

    public int plyCount() {
        int n = 0;
        for (GameNode x = root.next(); x != null; x = x.next()) n++;
        return n;
    }

    public GameNode end() {
        GameNode n = root;
        while (n.next() != null) n = n.next();
        return n;
    }

    public Position endPosition() {
        return end().position();
    }

    /** Mainline node after {@code ply} half-moves (0 = root), or null. */
    public GameNode nodeAtPly(int ply) {
        GameNode n = root;
        for (int i = 0; i < ply && n != null; i++) n = n.next();
        return n;
    }

    /**
     * Finds a node by the moves leading to it from the start, following
     * variations as needed (e.g. "e4 c5 Nf3 d6"). Returns null if absent.
     */
    public GameNode find(String moves) {
        GameNode n = root;
        if (moves == null || moves.isBlank()) return n;
        for (String token : moves.trim().split("\\s+")) {
            String t = token.replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty()) continue;
            int move;
            try {
                move = San.parse(n.position(), t);
            } catch (IllegalArgumentException e) {
                return null;
            }
            GameNode next = null;
            for (GameNode c : n.children()) if (c.move() == move) { next = c; break; }
            if (next == null) return null;
            n = next;
        }
        return n;
    }

    public boolean hasVariations() {
        return anyNode(root, n -> n.children().size() > 1);
    }

    public boolean hasComments() {
        return anyNode(root, n -> n.plainComment() != null || n.startingComment() != null);
    }

    public boolean hasNags() {
        return anyNode(root, n -> n.nags().length > 0);
    }

    private static boolean anyNode(GameNode n, java.util.function.Predicate<GameNode> p) {
        if (p.test(n)) return true;
        for (GameNode c : n.children()) if (anyNode(c, p)) return true;
        return false;
    }

    /** All nodes, depth-first in PGN order. */
    public List<GameNode> allNodes() {
        List<GameNode> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(GameNode n, List<GameNode> out) {
        out.add(n);
        for (GameNode c : n.children()) collect(c, out);
    }

    /** Result implied by the final position: mate or stalemate, else null. */
    public String terminationResult() {
        Position end = endPosition();
        if (end.isCheckmate()) return end.sideToMove() == 0 ? "0-1" : "1-0";
        if (end.isStalemate() || end.isInsufficientMaterial()) return "1/2-1/2";
        return null;
    }
}
