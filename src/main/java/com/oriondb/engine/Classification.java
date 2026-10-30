package com.oriondb.engine;

import com.oriondb.game.Nag;

/** Quality of a move judged by the engine (lichess thresholds). */
public enum Classification {
    /** The engine's own first choice. */
    BEST(0),
    /** Not the engine's choice, but losing less than 10% winning chances. */
    GOOD(0),
    /** Lost at least 0.1 winning chances: {@code ?!}. */
    INACCURACY(Nag.DUBIOUS),
    /** Lost at least 0.2 winning chances: {@code ?}. */
    MISTAKE(Nag.MISTAKE),
    /** Lost at least 0.3 winning chances: {@code ??}. */
    BLUNDER(Nag.BLUNDER);

    private final int nag;

    Classification(int nag) {
        this.nag = nag;
    }

    /** The NAG for this class, or 0 for {@link #BEST} and {@link #GOOD}. */
    public int nag() {
        return nag;
    }

    /** True for inaccuracies, mistakes and blunders. */
    public boolean isError() {
        return nag != 0;
    }
}
