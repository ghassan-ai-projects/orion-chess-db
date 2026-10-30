package com.oriondb.engine;

/**
 * The engine's verdict on one mainline move.
 *
 * @param ply            half-move number of the move (1 = the first move of the game)
 * @param san            the move played
 * @param color          the mover: 0 = white, 1 = black
 * @param scoreBefore    evaluation before the move, White's point of view
 * @param scoreAfter     evaluation after the move, White's point of view
 * @param bestMoveSan    the engine's preferred move in SAN (empty if unknown)
 * @param classification quality of the move
 * @param centipawnLoss  evaluation lost by the mover (0..1000, mates counted as ±1000)
 * @param accuracy       lichess-style move accuracy 0..100
 */
public record MoveAssessment(int ply, String san, int color, Score scoreBefore, Score scoreAfter, String bestMoveSan,
                             Classification classification, int centipawnLoss, double accuracy) {

    /** Move number as written in PGN ("12" for both 12. and 12...). */
    public int moveNumber() {
        return (ply + 1) / 2;
    }

    @Override
    public String toString() {
        return moveNumber() + (color == 0 ? ". " : "... ") + san + " " + scoreBefore + " -> " + scoreAfter
            + " " + classification + (classification == Classification.BEST ? "" : " (best " + bestMoveSan + ")");
    }
}
