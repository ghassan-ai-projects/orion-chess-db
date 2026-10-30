package com.oriondb.game;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A node in a game tree: the position after {@link #move()}, plus the
 * annotations attached to that move. The first child continues the line;
 * further children are variations (alternatives to the first child).
 */
public final class GameNode {
    private static final Pattern COMMAND = Pattern.compile("\\[%(\\w+)\\s+([^\\]]*)\\]");
    private static final int[] NO_NAGS = {};

    private final GameNode parent;
    private final int move;
    private final Position position;
    private String san;
    private final List<GameNode> children = new ArrayList<>(1);
    private String comment;
    private String startingComment;
    private int[] nags = NO_NAGS;

    GameNode(GameNode parent, int move, Position position) {
        this.parent = parent;
        this.move = move;
        this.position = position;
    }

    public GameNode parent() {
        return parent;
    }

    public boolean isRoot() {
        return parent == null;
    }

    /** The move leading to this node ({@link Move#NONE} at the root). */
    public int move() {
        return move;
    }

    /** Position after the move. */
    public Position position() {
        return position;
    }

    /** SAN of the move (with check suffix); empty at the root. */
    public String san() {
        if (san == null) san = parent == null ? "" : San.format(parent.position, move);
        return san;
    }

    public List<GameNode> children() {
        return Collections.unmodifiableList(children);
    }

    /** The main continuation, or null at the end of a line. */
    public GameNode next() {
        return children.isEmpty() ? null : children.get(0);
    }

    /** Alternatives to {@link #next()}. */
    public List<GameNode> variations() {
        return children.size() <= 1 ? List.of() : Collections.unmodifiableList(children.subList(1, children.size()));
    }

    /** Half-moves from the game's starting position. */
    public int depth() {
        int d = 0;
        for (GameNode n = this; n.parent != null; n = n.parent) d++;
        return d;
    }

    /** True if every ancestor is the main continuation of its parent. */
    public boolean isMainline() {
        for (GameNode n = this; n.parent != null; n = n.parent) {
            if (n.parent.children.get(0) != n) return false;
        }
        return true;
    }

    /** Moves from the root to this node. */
    public List<GameNode> line() {
        List<GameNode> path = new ArrayList<>();
        for (GameNode n = this; n.parent != null; n = n.parent) path.add(n);
        Collections.reverse(path);
        return path;
    }

    // ------------------------------------------------------------ editing

    /**
     * Plays {@code move} from this node: returns the existing child for that
     * move, or appends a new one (main line if this node had no children).
     */
    public GameNode play(int move) {
        for (GameNode c : children) if (c.move == move) return c;
        return appendChild(move);
    }

    /** Plays a move given in SAN (or UCI/LAN). */
    public GameNode play(String san) {
        return play(San.parse(position, san));
    }

    /** Always appends a new child, even if the move already exists (PGN fidelity). */
    public GameNode appendChild(int move) {
        if (!Move.isNull(move) && !position.isLegal(move)) {
            throw new IllegalArgumentException("illegal move " + Move.toString(move) + " in " + position.fen());
        }
        GameNode child = new GameNode(this, move, position.play(move));
        children.add(child);
        return child;
    }

    /** Makes this node the main continuation of its parent. */
    public void promoteToMainline() {
        requireChild();
        parent.children.remove(this);
        parent.children.add(0, this);
    }

    /** Moves this variation one place up among its siblings. */
    public void promote() {
        requireChild();
        int i = parent.children.indexOf(this);
        if (i > 0) Collections.swap(parent.children, i, i - 1);
    }

    /** Removes this node and everything after it. */
    public void delete() {
        requireChild();
        parent.children.remove(this);
    }

    /** Removes all moves after this node. */
    public void truncate() {
        children.clear();
    }

    /** Removes the variations (not the main continuation) below this node, recursively. */
    public void stripVariations() {
        if (children.size() > 1) children.subList(1, children.size()).clear();
        if (!children.isEmpty()) children.get(0).stripVariations();
    }

    private void requireChild() {
        if (parent == null) throw new IllegalStateException("the root node has no siblings");
    }

    // ------------------------------------------------------------ annotations

    /** Comment after the move (or before the first move, at the root); null if none. */
    public String comment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null || comment.isBlank() ? null : comment.strip();
    }

    /** Appends text to the comment. */
    public void appendComment(String text) {
        if (text == null || text.isBlank()) return;
        setComment(comment == null ? text : comment + " " + text.strip());
    }

    /** Comment placed before the move (common at the start of variations). */
    public String startingComment() {
        return startingComment;
    }

    public void setStartingComment(String text) {
        this.startingComment = text == null || text.isBlank() ? null : text.strip();
    }

    public int[] nags() {
        return nags.clone();
    }

    public boolean hasNag(int nag) {
        for (int n : nags) if (n == nag) return true;
        return false;
    }

    /** Adds a NAG; a move assessment ("!", "?" ...) or position assessment replaces the previous one. */
    public void addNag(int nag) {
        if (hasNag(nag)) return;
        if (Nag.isMoveAssessment(nag)) removeNagsIf(Nag::isMoveAssessment);
        if (Nag.isPositionAssessment(nag)) removeNagsIf(Nag::isPositionAssessment);
        nags = Arrays.copyOf(nags, nags.length + 1);
        nags[nags.length - 1] = nag;
    }

    /** Adds a NAG exactly as read (PGN fidelity: no replacement of earlier assessments). */
    public void appendNag(int nag) {
        nags = Arrays.copyOf(nags, nags.length + 1);
        nags[nags.length - 1] = nag;
    }

    public void removeNag(int nag) {
        removeNagsIf(n -> n == nag);
    }

    public void clearNags() {
        nags = NO_NAGS;
    }

    private void removeNagsIf(java.util.function.IntPredicate p) {
        nags = Arrays.stream(nags).filter(n -> !p.test(n)).toArray();
    }

    /** Embedded commands such as {@code [%clk 0:01:23]} or {@code [%eval 0.35]}. */
    public Map<String, String> commands() {
        Map<String, String> out = new LinkedHashMap<>();
        if (comment == null) return out;
        Matcher m = COMMAND.matcher(comment);
        while (m.find()) out.put(m.group(1), m.group(2).strip());
        return out;
    }

    public String command(String name) {
        return commands().get(name);
    }

    /** Sets (or with a null value removes) an embedded {@code [%name value]} command. */
    public void setCommand(String name, String value) {
        String text = comment == null ? "" : comment;
        Matcher m = COMMAND.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        while (m.find()) {
            if (m.group(1).equals(name)) {
                m.appendReplacement(sb, replaced || value == null ? "" : Matcher.quoteReplacement("[%" + name + " " + value + "]"));
                replaced = true;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        String result = sb.toString().replaceAll("\\s{2,}", " ").strip();
        if (!replaced && value != null) result = ("[%" + name + " " + value + "] " + result).strip();
        setComment(result);
    }

    /** Comment text without embedded commands. */
    public String plainComment() {
        if (comment == null) return null;
        String s = COMMAND.matcher(comment).replaceAll("").replaceAll("\\s{2,}", " ").strip();
        return s.isEmpty() ? null : s;
    }

    @Override
    public String toString() {
        return parent == null ? "(start)" : (position.ply() + 1) / 2 + (parent.position.sideToMove() == 0 ? ". " : "... ") + san();
    }
}
