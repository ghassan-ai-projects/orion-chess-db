package com.oriondb.engine;

/**
 * How long an engine searches one position. Any combination of depth,
 * move time and node count may be set (0 = unset); the engine stops at
 * whichever comes first. At least one must be set.
 */
public record Limit(int depth, long movetimeMs, long nodes) {

    public Limit {
        if (depth < 0 || movetimeMs < 0 || nodes < 0) throw new IllegalArgumentException("negative search limit");
        if (depth == 0 && movetimeMs == 0 && nodes == 0) throw new IllegalArgumentException("empty search limit");
    }

    /** Search to a fixed depth (plies). */
    public static Limit depth(int depth) {
        return new Limit(depth, 0, 0);
    }

    /** Search for a fixed time per position. */
    public static Limit movetime(long millis) {
        return new Limit(0, millis, 0);
    }

    /** Search a fixed number of nodes. */
    public static Limit nodes(long nodes) {
        return new Limit(0, 0, nodes);
    }

    public Limit withDepth(int depth) {
        return new Limit(depth, movetimeMs, nodes);
    }

    public Limit withMovetime(long millis) {
        return new Limit(depth, millis, nodes);
    }

    public Limit withNodes(long nodes) {
        return new Limit(depth, movetimeMs, nodes);
    }

    /** The UCI {@code go} command for this limit. */
    String goCommand() {
        StringBuilder sb = new StringBuilder("go");
        if (depth > 0) sb.append(" depth ").append(depth);
        if (movetimeMs > 0) sb.append(" movetime ").append(movetimeMs);
        if (nodes > 0) sb.append(" nodes ").append(nodes);
        return sb.toString();
    }

    @Override
    public String toString() {
        return goCommand().substring(3);
    }
}
