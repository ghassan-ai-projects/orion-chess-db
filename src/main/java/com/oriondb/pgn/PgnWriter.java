package com.oriondb.pgn;

import com.oriondb.chess.Position;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes games in PGN export format (Seven Tag Roster first, wrapped movetext). */
public final class PgnWriter {

    /**
     * @param lineWidth     maximum movetext line length
     * @param comments      include comments
     * @param variations    include variations
     * @param nags          include NAGs
     * @param symbolicNags  write "!?" instead of "$5" directly after moves (not strict export format)
     */
    public record Options(int lineWidth, boolean comments, boolean variations, boolean nags, boolean symbolicNags) {
        public static final Options DEFAULT = new Options(80, true, true, true, false);
        /** Mainline only, no annotations. */
        public static final Options BARE = new Options(80, false, false, false, false);
    }

    private final Options options;
    private final List<String> tokens = new ArrayList<>();

    private PgnWriter(Options options) {
        this.options = options;
    }

    public static String write(Game game) {
        return write(game, Options.DEFAULT);
    }

    public static String write(Game game, Options options) {
        StringBuilder sb = new StringBuilder(1024);
        for (Map.Entry<String, String> e : game.tags().exportOrder()) {
            sb.append(PgnText.tagLine(e.getKey(), e.getValue())).append('\n');
        }
        sb.append('\n').append(movetext(game, options)).append('\n');
        return sb.toString();
    }

    /** Wrapped movetext including the result token. */
    public static String movetext(Game game, Options options) {
        PgnWriter w = new PgnWriter(options);
        GameNode root = game.root();
        if (options.comments() && root.comment() != null) w.comment(root.comment());
        w.line(root, true);
        w.tokens.add(game.result());
        return w.wrap();
    }

    private void line(GameNode from, boolean forceNumber) {
        GameNode node = from;
        while (node.next() != null) {
            GameNode main = node.next();
            forceNumber = move(main, forceNumber);
            if (options.variations()) {
                for (GameNode v : node.variations()) {
                    tokens.add("(");
                    boolean f = move(v, true);
                    line(v, f);
                    tokens.add(")");
                    forceNumber = true;
                }
            }
            node = main;
        }
    }

    /** Emits one move with its annotations; returns whether the next move needs an explicit number. */
    private boolean move(GameNode n, boolean forceNumber) {
        if (options.comments() && n.startingComment() != null) {
            comment(n.startingComment());
            forceNumber = true;
        }
        Position before = n.parent().position();
        String number;
        if (before.sideToMove() == 0) number = before.fullmoveNumber() + ".";
        else number = forceNumber ? before.fullmoveNumber() + "..." : null;
        String san = n.san();
        if (options.nags() && options.symbolicNags()) {
            for (int nag : n.nags()) if (Nag.isMoveAssessment(nag)) san += Nag.symbol(nag);
        }
        if (number != null) tokens.add(number + " " + san);
        else tokens.add(san);
        if (options.nags()) {
            for (int nag : n.nags()) {
                if (options.symbolicNags() && Nag.isMoveAssessment(nag)) continue;
                tokens.add("$" + nag);
            }
        }
        boolean commented = false;
        if (options.comments() && n.comment() != null) {
            comment(n.comment());
            commented = true;
        }
        return commented;
    }

    private void comment(String text) {
        String safe = text.replace('}', ')').replace('{', '(');
        String[] words = safe.strip().split("\\s+");
        if (words.length == 1) {
            tokens.add("{" + words[0] + "}");
            return;
        }
        tokens.add("{" + words[0]);
        for (int i = 1; i < words.length - 1; i++) tokens.add(words[i]);
        tokens.add(words[words.length - 1] + "}");
    }

    private String wrap() {
        StringBuilder out = new StringBuilder();
        int lineLen = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean glueRight = i > 0 && tokens.get(i - 1).equals("(");
            boolean glueLeft = t.equals(")");
            int needed = (lineLen == 0 || glueRight || glueLeft ? 0 : 1) + t.length();
            if (lineLen > 0 && lineLen + needed > options.lineWidth()) {
                out.append('\n');
                lineLen = 0;
                needed = t.length();
            } else if (lineLen > 0 && !glueRight && !glueLeft) {
                out.append(' ');
            }
            out.append(t);
            lineLen += needed;
        }
        return out.toString();
    }
}
