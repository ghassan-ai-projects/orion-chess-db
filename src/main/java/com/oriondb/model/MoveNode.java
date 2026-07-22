package com.oriondb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node in a game's move tree.
 *
 * Each node is one played move (SAN) plus its annotations. {@link #next()}
 * continues the current line; {@link #variations()} are alternative moves to
 * <em>this</em> move (standard PGN RAV semantics), each the first node of its
 * own line.
 */
public final class MoveNode {
    private final String san;
    private String commentBefore;      // {comment} preceding the move
    private String comment;            // {comment} following the move
    private List<Integer> nags;        // $n annotation glyphs
    private MoveNode next;
    private List<MoveNode> variations;

    public MoveNode(String san) {
        this.san = san;
    }

    public String san() {
        return san;
    }

    public String comment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = normalize(comment);
    }

    public void appendComment(String comment) {
        String cleaned = normalize(comment);
        if (cleaned == null) return;
        this.comment = this.comment == null ? cleaned : this.comment + " " + cleaned;
    }

    public String commentBefore() {
        return commentBefore;
    }

    public void setCommentBefore(String comment) {
        this.commentBefore = normalize(comment);
    }

    private static String normalize(String c) {
        if (c == null) return null;
        String cleaned = c.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    public List<Integer> nags() {
        return nags == null ? Collections.emptyList() : Collections.unmodifiableList(nags);
    }

    public void addNag(int nag) {
        if (nags == null) nags = new ArrayList<>(2);
        nags.add(nag);
    }

    public MoveNode next() {
        return next;
    }

    public void setNext(MoveNode next) {
        this.next = next;
    }

    public List<MoveNode> variations() {
        return variations == null ? Collections.emptyList() : Collections.unmodifiableList(variations);
    }

    public void addVariation(MoveNode firstMove) {
        if (variations == null) variations = new ArrayList<>(2);
        variations.add(firstMove);
    }

    public boolean hasAnnotations() {
        return comment != null || commentBefore != null
            || (nags != null && !nags.isEmpty())
            || (variations != null && !variations.isEmpty());
    }

    /**
     * Traditional suffix for the move's NAGs when one exists ("!", "?", "!!",
     * "??", "!?", "?!"), else "".
     */
    public String nagSuffix() {
        if (nags == null) return "";
        for (int nag : nags) {
            switch (nag) {
                case 1: return "!";
                case 2: return "?";
                case 3: return "!!";
                case 4: return "??";
                case 5: return "!?";
                case 6: return "?!";
                default: break;
            }
        }
        return "";
    }

    @Override
    public String toString() {
        return san + nagSuffix();
    }
}
