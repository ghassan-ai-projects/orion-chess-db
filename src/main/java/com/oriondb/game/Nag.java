package com.oriondb.game;

import java.util.Map;

/** Numeric Annotation Glyphs and their conventional symbols. */
public final class Nag {
    public static final int GOOD = 1, MISTAKE = 2, BRILLIANT = 3, BLUNDER = 4, INTERESTING = 5, DUBIOUS = 6;
    public static final int FORCED = 7, DRAWISH = 10, UNCLEAR = 13;
    public static final int WHITE_SLIGHTLY_BETTER = 14, BLACK_SLIGHTLY_BETTER = 15;
    public static final int WHITE_BETTER = 16, BLACK_BETTER = 17, WHITE_WINNING = 18, BLACK_WINNING = 19;
    public static final int NOVELTY = 146;

    private static final Map<Integer, String> SYMBOLS = Map.ofEntries(
        Map.entry(1, "!"), Map.entry(2, "?"), Map.entry(3, "!!"), Map.entry(4, "??"),
        Map.entry(5, "!?"), Map.entry(6, "?!"), Map.entry(7, "□"), Map.entry(10, "="),
        Map.entry(13, "∞"), Map.entry(14, "⩲"), Map.entry(15, "⩱"), Map.entry(16, "±"),
        Map.entry(17, "∓"), Map.entry(18, "+-"), Map.entry(19, "-+"), Map.entry(22, "⨀"),
        Map.entry(23, "⨀"), Map.entry(32, "⟳"), Map.entry(33, "⟳"), Map.entry(36, "↑"),
        Map.entry(37, "↑"), Map.entry(40, "→"), Map.entry(41, "→"), Map.entry(132, "⇆"),
        Map.entry(133, "⇆"), Map.entry(138, "⊕"), Map.entry(139, "⊕"), Map.entry(140, "∆"),
        Map.entry(146, "N"));

    /** Symbols accepted in movetext, including ASCII spellings. */
    private static final Map<String, Integer> PARSE = Map.ofEntries(
        Map.entry("!", 1), Map.entry("?", 2), Map.entry("!!", 3), Map.entry("??", 4),
        Map.entry("!?", 5), Map.entry("?!", 6), Map.entry("□", 7), Map.entry("=", 10),
        Map.entry("∞", 13), Map.entry("+=", 14), Map.entry("⩲", 14), Map.entry("=+", 15),
        Map.entry("⩱", 15), Map.entry("+/-", 16), Map.entry("±", 16), Map.entry("-/+", 17),
        Map.entry("∓", 17), Map.entry("+-", 18), Map.entry("-+", 19), Map.entry("+--", 18),
        Map.entry("--+", 19), Map.entry("⨀", 22), Map.entry("→", 40), Map.entry("↑", 36),
        Map.entry("⇆", 132), Map.entry("∆", 140), Map.entry("N", 146));

    private static final Map<Integer, String> NAMES = Map.ofEntries(
        Map.entry(1, "good move"), Map.entry(2, "mistake"), Map.entry(3, "brilliant move"),
        Map.entry(4, "blunder"), Map.entry(5, "interesting move"), Map.entry(6, "dubious move"),
        Map.entry(7, "only move"), Map.entry(10, "equal position"), Map.entry(13, "unclear position"),
        Map.entry(14, "white is slightly better"), Map.entry(15, "black is slightly better"),
        Map.entry(16, "white is better"), Map.entry(17, "black is better"),
        Map.entry(18, "white is winning"), Map.entry(19, "black is winning"),
        Map.entry(22, "white is in zugzwang"), Map.entry(23, "black is in zugzwang"),
        Map.entry(36, "white has the initiative"), Map.entry(37, "black has the initiative"),
        Map.entry(40, "white has the attack"), Map.entry(41, "black has the attack"),
        Map.entry(132, "white has counterplay"), Map.entry(133, "black has counterplay"),
        Map.entry(138, "white is in time trouble"), Map.entry(139, "black is in time trouble"),
        Map.entry(140, "with the idea"), Map.entry(146, "novelty"));

    private Nag() {}

    /** Display symbol, or "$n" when there is none. */
    public static String symbol(int nag) {
        return SYMBOLS.getOrDefault(nag, "$" + nag);
    }

    public static String describe(int nag) {
        return NAMES.getOrDefault(nag, "NAG $" + nag);
    }

    /** Parses "$14", "!?", "+/-", "±" ...; returns -1 if not a NAG. */
    public static int parse(String token) {
        if (token.length() > 1 && token.charAt(0) == '$') {
            try {
                int n = Integer.parseInt(token.substring(1));
                return n >= 0 && n <= 255 ? n : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return PARSE.getOrDefault(token, -1);
    }

    /** Move-quality NAGs ($1-$6) are mutually exclusive on one move. */
    public static boolean isMoveAssessment(int nag) {
        return nag >= 1 && nag <= 6;
    }

    /** Position-assessment NAGs ($10-$19) are mutually exclusive. */
    public static boolean isPositionAssessment(int nag) {
        return nag >= 10 && nag <= 19;
    }
}
