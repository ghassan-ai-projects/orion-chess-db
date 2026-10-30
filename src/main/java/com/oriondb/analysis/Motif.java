package com.oriondb.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tactical and notable moments, attributed to the move that creates them. */
public enum Motif {
    FORK("a piece attacks two or more valuable or undefended pieces"),
    PIN("a move creates an absolute pin against the king"),
    SKEWER("a slider attacks the king or queen with a piece behind it"),
    DISCOVERED_CHECK("check given by a piece other than the one that moved"),
    DOUBLE_CHECK("check from two pieces at once"),
    SACRIFICE("a piece of minor value or more is given up and not regained"),
    QUEEN_SACRIFICE("the queen is given up and not regained (also counts as a sacrifice)"),
    CHECKMATE("the move mates"),
    SMOTHERED_MATE("knight mate with the king boxed in by its own pieces"),
    BACK_RANK_MATE("rook or queen mate on the king's back rank"),
    STALEMATE("the move stalemates"),
    PROMOTION("pawn promotion"),
    UNDERPROMOTION("promotion to a knight, bishop or rook"),
    EN_PASSANT("en passant capture"),
    CASTLING_KINGSIDE("O-O"),
    CASTLING_QUEENSIDE("O-O-O");

    private final String description;

    Motif(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int bit() {
        return 1 << ordinal();
    }

    /** Parses "fork", "back-rank-mate", "BACK_RANK_MATE"... */
    public static Motif parse(String s) {
        String k = s.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Motif m : values()) if (m.name().equals(k)) return m;
        throw new IllegalArgumentException("unknown motif '" + s + "'; known: " + keys());
    }

    public static List<Motif> fromMask(int mask) {
        List<Motif> out = new ArrayList<>();
        for (Motif m : values()) if ((mask & m.bit()) != 0) out.add(m);
        return out;
    }

    public static List<String> keys() {
        List<String> out = new ArrayList<>();
        for (Motif m : values()) out.add(m.key());
        return out;
    }
}
