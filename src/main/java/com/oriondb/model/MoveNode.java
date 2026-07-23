package com.oriondb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
    private boolean readOnly;

    public MoveNode(String san) {
        this.san = Objects.requireNonNull(san, "san");
        if (san.isBlank()) {
            throw new IllegalArgumentException("SAN must not be blank");
        }
    }

    public String san() {
        return san;
    }

    public String comment() {
        return comment;
    }

    public void setComment(String comment) {
        requireMutable();
        this.comment = normalize(comment);
    }

    public void appendComment(String comment) {
        requireMutable();
        String cleaned = normalize(comment);
        if (cleaned == null) return;
        this.comment = this.comment == null ? cleaned : this.comment + " " + cleaned;
    }

    public String commentBefore() {
        return commentBefore;
    }

    public void setCommentBefore(String comment) {
        requireMutable();
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
        requireMutable();
        if (nag < 0) {
            throw new IllegalArgumentException("NAG must not be negative");
        }
        if (nags == null) nags = new ArrayList<>(2);
        nags.add(nag);
    }

    public MoveNode next() {
        return next;
    }

    public void setNext(MoveNode next) {
        requireMutable();
        this.next = next;
    }

    public List<MoveNode> variations() {
        return variations == null ? Collections.emptyList() : Collections.unmodifiableList(variations);
    }

    public void addVariation(MoveNode firstMove) {
        requireMutable();
        Objects.requireNonNull(firstMove, "firstMove");
        if (variations == null) variations = new ArrayList<>(2);
        variations.add(firstMove);
    }

    /**
     * Deep, mutable copy of this move and its continuations.
     *
     * Iterative along the {@code next} chain so arbitrarily long lines never
     * overflow the stack; recursion is only per variation nesting level,
     * which the PGN reader bounds.
     */
    public MoveNode copy() {
        MoveNode head = copyNode(this);
        MoveNode tail = head;
        for (MoveNode source = next; source != null; source = source.next) {
            MoveNode copied = copyNode(source);
            tail.next = copied;
            tail = copied;
        }
        return head;
    }

    /** Copy one node (annotations and variations, not the next chain). */
    private static MoveNode copyNode(MoveNode source) {
        MoveNode copy = new MoveNode(source.san);
        copy.commentBefore = source.commentBefore;
        copy.comment = source.comment;
        if (source.nags != null) {
            copy.nags = new ArrayList<>(source.nags);
        }
        if (source.variations != null) {
            copy.variations = new ArrayList<>(source.variations.size());
            for (MoveNode variation : source.variations) {
                copy.variations.add(variation.copy());
            }
        }
        return copy;
    }

    /**
     * Make this move tree read-only. Used by the database for stored games.
     * Iterative along the {@code next} chain (see {@link #copy()}).
     */
    public void makeReadOnly() {
        for (MoveNode node = this; node != null && !node.readOnly; node = node.next) {
            if (node.variations != null) {
                for (MoveNode variation : node.variations) {
                    variation.makeReadOnly();
                }
            }
            node.readOnly = true;
        }
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    private void requireMutable() {
        if (readOnly) {
            throw new UnsupportedOperationException(
                "Database games are read-only; copy the game and call Database.replace");
        }
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
