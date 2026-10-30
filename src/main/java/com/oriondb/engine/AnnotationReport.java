package com.oriondb.engine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link GameAnnotator#annotate}: one assessment per analysed move
 * plus summary statistics for each side.
 */
public record AnnotationReport(List<MoveAssessment> moves, SideStats white, SideStats black) {

    public AnnotationReport {
        moves = List.copyOf(moves);
    }

    /** Stats for color 0 (white) or 1 (black). */
    public SideStats side(int color) {
        return color == 0 ? white : black;
    }

    /** Builds the per-side summaries from the assessments. */
    static AnnotationReport of(List<MoveAssessment> moves) {
        return new AnnotationReport(moves, SideStats.of(0, moves), SideStats.of(1, moves));
    }

    /**
     * Summary for one side.
     *
     * @param color                0 = white, 1 = black
     * @param moves                number of assessed moves
     * @param averageCentipawnLoss mean centipawn loss (each loss capped at 1000)
     * @param accuracy             mean lichess-style move accuracy 0..100 (100 when no moves)
     * @param counts               number of moves per classification
     */
    public record SideStats(int color, int moves, double averageCentipawnLoss, double accuracy,
                            Map<Classification, Integer> counts) {

        public SideStats {
            EnumMap<Classification, Integer> c = new EnumMap<>(Classification.class);
            for (Classification k : Classification.values()) c.put(k, counts.getOrDefault(k, 0));
            counts = java.util.Collections.unmodifiableMap(c);
        }

        public int count(Classification c) {
            return counts.get(c);
        }

        public int inaccuracies() {
            return count(Classification.INACCURACY);
        }

        public int mistakes() {
            return count(Classification.MISTAKE);
        }

        public int blunders() {
            return count(Classification.BLUNDER);
        }

        static SideStats of(int color, List<MoveAssessment> all) {
            Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
            int n = 0;
            double loss = 0, acc = 0;
            for (MoveAssessment m : all) {
                if (m.color() != color) continue;
                n++;
                loss += m.centipawnLoss();
                acc += m.accuracy();
                counts.merge(m.classification(), 1, Integer::sum);
            }
            return new SideStats(color, n, n == 0 ? 0 : loss / n, n == 0 ? 100 : acc / n, counts);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%s: %d moves, acpl %.1f, accuracy %.1f%%, %d inaccuracies, %d mistakes, %d blunders",
                color == 0 ? "White" : "Black", moves, averageCentipawnLoss, accuracy, inaccuracies(), mistakes(), blunders());
        }
    }
}
