package com.oriondb.query;

import com.oriondb.analysis.Material;
import com.oriondb.analysis.Motif;
import com.oriondb.analysis.PawnFeature;
import com.oriondb.chess.Position;
import com.oriondb.db.GameRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text query language, e.g.
 * <pre>
 *   player:carlsen as:white outcome:win eco:B90-B99 elo:&gt;=2700 year:2015..2020
 *   material:"KRP vs KR" pieces:&lt;=6 motif:fork,sacrifice label:prep sort:-date limit:20
 * </pre>
 * Terms are {@code key:value} (quote values containing spaces); bare words
 * search players, event, site and opening. See {@link #HELP}.
 */
public final class QueryParser {
    public static final String HELP = """
        Query terms are AND-combined; combine with OR, NOT (or a leading -) and parentheses:
          (eco:B90-B99 OR eco:C00-C19) -tag:Event="Titled Arena" elo:2600
        Quote values with spaces; bare words search players/event/site/opening.
          white: black: player:        name tokens, any order, accent-insensitive
          as:white|black               restrict player: to one color
          outcome:win|draw|loss        result from player:'s side
          event: site: round: opening: substring / prefix match
          date:2020  date:2020.05..2021  year:1990..1999   (also date:>=2020)
          result:1-0|0-1|draw|decisive|unknown
          eco:B90  eco:B9  eco:B90-B99
          elo:2600 (at least)  elo:2500..2700   elomode:either|both|average
          moves:20..40 (full moves)   plies:<=60
          line:"1. e4 c5 2. Nf3"      mainline starts with these moves
          fen:"<FEN>"                 reaches this position (any move order) on the mainline
          scope:all                   fen: also matches positions inside variations (match ply -1)
          pattern:"Kg1 kg8 Pe5 -pd5 R*7 w"  piece placement reached on the mainline
          material:"KRP vs KR"  material:KRPKR  (colors either way; material!:... exact colors)
          pieces:<=6                  reaches a position with at most N pieces
          motif:fork,pin,skewer,discovered_check,double_check,sacrifice,queen_sacrifice,
                checkmate,smothered_mate,back_rank_mate,stalemate,promotion,underpromotion,
                en_passant,castling,castling_kingside,castling_queenside   motifby:white|black
          move:Bxh7+                  a mainline move fitting this SAN pattern was played
                                      (x = capture, + check, # mate, =Q promotion, Q* any queen move)
          sequence:"Bxh7+ Kxh7 Ng5+"  consecutive moves anywhere on the mainline
          moveby:white|black  movenum:10..25   restrict move:/sequence: by side and move number
          balance:>=3  balance:-1..1  a position with this material balance (White minus Black, pawns)
          structure:iqp,isolated,hanging_pawns,passed,advanced_passer,connected_passed_pawns,
                doubled,maroczy_bind,carlsbad,stonewall,opposite_colored_bishops   structureby:white|black
          label:a,b (all)  anylabel:a,b  nolabel:a,b
          annotated:yes|no  comments:yes|no  variations:yes|no  evals:yes|no  chess960:yes|no
          comment:"text"              comment contains text
          tag:Name=Value  tag:Name=*  any PGN tag
          id:1,5,9
          sort:date|-date|white|black|event|elo|whiteelo|blackelo|ply|eco|id   limit:N  offset:N
          after:<cursor>              stable keyset paging (cursor from the previous page)
        """;

    private QueryParser() {}

    /** Output options that always apply to the whole query, wherever they appear. */
    private static final Set<String> GLOBAL_KEYS = Set.of("sort", "limit", "offset", "after");

    public static Query parse(String text) {
        Parser p = new Parser(tokenize(text));
        Query q = p.or();
        if (p.i < p.toks.size()) throw new IllegalArgumentException("unexpected '" + p.toks.get(p.i) + "'");
        for (String[] g : p.globals) apply(q, g[0], g[1]);
        return q;
    }

    /**
     * Recursive descent over: {@code or := and (OR and)*},
     * {@code and := unary*}, {@code unary := (NOT|-) unary | '(' or ')' | term}.
     */
    private static final class Parser {
        final List<String> toks;
        final List<String[]> globals = new ArrayList<>();
        int i;

        Parser(List<String> toks) {
            this.toks = new ArrayList<>(toks);
        }

        Query or() {
            List<Query> alternatives = new ArrayList<>();
            alternatives.add(and());
            while (i < toks.size() && isOr(toks.get(i))) {
                i++;
                alternatives.add(and());
            }
            return alternatives.size() == 1 ? alternatives.get(0) : Query.anyOf(alternatives.toArray(Query[]::new));
        }

        Query and() {
            Query q = new Query();
            List<String> words = new ArrayList<>();
            while (i < toks.size() && !toks.get(i).equals(")") && !isOr(toks.get(i))) unary(q, words);
            if (!words.isEmpty()) q.text(String.join(" ", words));
            return q;
        }

        void unary(Query q, List<String> words) {
            String t = toks.get(i);
            if (t.equalsIgnoreCase("AND")) {
                i++;
                return;
            }
            if (t.equals("NOT") || t.equals("not") || t.equals("-") || (t.startsWith("-") && t.length() > 1 && !isNumber(t))) {
                if (t.length() > 1 && t.startsWith("-")) toks.set(i, t.substring(1));
                else i++;
                if (i >= toks.size()) throw new IllegalArgumentException("NOT needs a term after it");
                Query negated = new Query();
                List<String> negWords = new ArrayList<>();
                unary(negated, negWords);
                if (!negWords.isEmpty()) negated.text(String.join(" ", negWords));
                q.not(negated);
                return;
            }
            if (t.equals("(")) {
                i++;
                Query group = or();
                if (i >= toks.size() || !toks.get(i).equals(")")) throw new IllegalArgumentException("missing ')'");
                i++;
                q.and(group);
                return;
            }
            if (t.equals(")")) throw new IllegalArgumentException("unbalanced ')'");
            i++;
            int colon = t.indexOf(':');
            if (colon <= 0) {
                words.add(unquote(t));
                return;
            }
            String key = t.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = unquote(t.substring(colon + 1));
            if (GLOBAL_KEYS.contains(key)) globals.add(new String[] {key, value});
            else apply(q, key, value);
        }

        private static boolean isOr(String t) {
            return t.equals("OR") || t.equals("or") || t.equals("|");
        }

        private static boolean isNumber(String t) {
            return t.matches("-\\d+");
        }
    }

    private static void apply(Query q, String key, String v) {
        switch (key) {
            case "white" -> q.white(v);
            case "black" -> q.black(v);
            case "player" -> q.player(v);
            case "as", "color" -> q.playerColor(color(v));
            case "outcome" -> q.outcome(Query.Outcome.valueOf(upper(v)));
            case "event" -> q.event(v);
            case "site" -> q.site(v);
            case "round" -> q.round(v);
            case "opening" -> q.opening(v);
            case "comment" -> q.comment(v);
            case "date", "year" -> {
                int[] r = range(v);
                q.dateRange(r[0] < 0 ? null : dateText(v, true), r[1] < 0 ? null : dateText(v, false));
            }
            case "result" -> q.results(results(v));
            case "eco" -> q.eco(v);
            case "elo" -> {
                // A bare number means "at least".
                int[] r = v.strip().matches("\\d+") ? new int[] {Integer.parseInt(v.strip()), -1} : range(v);
                q.elo(Math.max(0, r[0]), Math.max(0, r[1]), q.eloMode);
            }
            case "elomode" -> q.eloMode(Query.EloMode.valueOf(upper(v)));
            case "moves" -> {
                int[] r = range(v);
                q.plies(r[0] < 0 ? 0 : Math.max(0, r[0] * 2 - 1), r[1] < 0 ? 0 : r[1] * 2);
            }
            case "plies", "ply" -> {
                int[] r = range(v);
                q.plies(Math.max(0, r[0]), Math.max(0, r[1]));
            }
            case "line" -> q.line(v);
            case "fen", "position" -> q.position(Position.fromFen(v));
            case "scope" -> q.positionInVariations(switch (v.strip().toLowerCase(Locale.ROOT)) {
                case "all", "variations" -> true;
                case "mainline" -> false;
                default -> throw new IllegalArgumentException("scope: is mainline or all");
            });
            case "pattern" -> q.pattern(PiecePattern.parse(v));
            case "material" -> q.material(Material.parse(v), false);
            case "material!" -> q.material(Material.parse(v), true);
            case "pieces" -> q.maxPieces(range(v)[1] > 0 ? range(v)[1] : range(v)[0]);
            case "motif", "motifs", "tactic" -> {
                for (String m : v.split(",")) {
                    if (m.strip().equalsIgnoreCase("castling")) {
                        q.and(Query.anyOf(new Query().motif(Motif.CASTLING_KINGSIDE), new Query().motif(Motif.CASTLING_QUEENSIDE)));
                    } else {
                        q.motif(Motif.parse(m));
                    }
                }
            }
            case "move" -> {
                for (String m : v.split(",")) q.move(MovePattern.parse(m));
            }
            case "sequence", "seq" -> q.sequence(MovePattern.parseList(v));
            case "moveby" -> q.moveBy(color(v));
            case "movenum" -> {
                int[] r = range(v);
                q.moveNumbers(Math.max(0, r[0]), Math.max(0, r[1]));
            }
            case "balance" -> {
                Integer[] r = signedRange(v);
                q.balance(r[0], r[1]);
            }
            case "motifby" -> q.motifBy(color(v));
            case "structure", "pawns" -> {
                for (String f : v.split(",")) q.feature(PawnFeature.parse(f));
            }
            case "structureby" -> q.featureColor(color(v));
            case "label", "labels" -> {
                for (String l : v.split(",")) q.withLabel(l);
            }
            case "anylabel" -> {
                for (String l : v.split(",")) q.withAnyLabel(l);
            }
            case "nolabel", "-label" -> {
                for (String l : v.split(",")) q.withoutLabel(l);
            }
            case "annotated" -> q.annotated(bool(v));
            case "comments" -> q.hasComments(bool(v));
            case "variations" -> q.hasVariations(bool(v));
            case "evals" -> q.hasEvals(bool(v));
            case "chess960" -> q.chess960(bool(v));
            case "tag" -> {
                int eq = v.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("tag: needs Name=Value");
                q.tag(v.substring(0, eq).strip(), unquote(v.substring(eq + 1)));
            }
            case "id", "ids" -> {
                Set<Integer> ids = new HashSet<>();
                for (String s : v.split(",")) ids.add(Integer.parseInt(s.strip()));
                q.ids(ids);
            }
            case "sort" -> {
                boolean desc = v.startsWith("-");
                String name = upper(desc ? v.substring(1) : v);
                if (name.equals("WHITEELO")) name = "WHITE_ELO";
                if (name.equals("BLACKELO")) name = "BLACK_ELO";
                q.sort(Query.Sort.valueOf(name), desc);
            }
            case "after" -> q.after(v);
            case "limit" -> q.page(q.offset, Integer.parseInt(v));
            case "offset" -> q.page(Integer.parseInt(v), q.limit);
            default -> throw new IllegalArgumentException("unknown query key '" + key + "'");
        }
    }

    /** Signed range for material balance: "3", ">=2", "-1..1", "<=-3" (bare number means at least). */
    static Integer[] signedRange(String v) {
        String s = v.strip();
        try {
            if (s.contains("..")) {
                String[] p = s.split("\\.\\.", 2);
                return new Integer[] {p[0].isBlank() ? null : Integer.valueOf(p[0].strip()),
                    p[1].isBlank() ? null : Integer.valueOf(p[1].strip())};
            }
            if (s.startsWith(">=")) return new Integer[] {Integer.valueOf(s.substring(2).strip()), null};
            if (s.startsWith("<=")) return new Integer[] {null, Integer.valueOf(s.substring(2).strip())};
            int n = Integer.parseInt(s);
            return n >= 0 ? new Integer[] {n, null} : new Integer[] {null, n};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad balance '" + v + "' (use 3, >=2, <=-3, -1..1)");
        }
    }

    /** "a..b", ">=a", "<=b", ">a", "<b", "a" → {lo, hi} with -1 for open ends ("a" → {a, a}). */
    static int[] range(String v) {
        String s = v.strip();
        try {
            if (s.contains("..")) {
                String[] p = s.split("\\.\\.", 2);
                return new int[] {p[0].isBlank() ? -1 : num(p[0]), p[1].isBlank() ? -1 : num(p[1])};
            }
            if (s.startsWith(">=")) return new int[] {num(s.substring(2)), -1};
            if (s.startsWith("<=")) return new int[] {-1, num(s.substring(2))};
            if (s.startsWith(">")) return new int[] {num(s.substring(1)) + 1, -1};
            if (s.startsWith("<")) return new int[] {-1, num(s.substring(1)) - 1};
            int n = num(s);
            return new int[] {n, n};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad range '" + v + "' (use N, a..b, >=N, <=N)");
        }
    }

    private static int num(String s) {
        return Integer.parseInt(s.strip().split("[.\\-/]")[0]);
    }

    /** The lower or upper date text of a date term, keeping month/day precision. */
    private static String dateText(String v, boolean lower) {
        String s = v.strip();
        if (s.contains("..")) {
            String[] p = s.split("\\.\\.", 2);
            return lower ? p[0] : p[1];
        }
        if (s.startsWith(">=") || s.startsWith("<=")) return s.substring(2);
        if (s.startsWith(">") || s.startsWith("<")) return s.substring(1);
        return s;
    }

    private static Set<Integer> results(String v) {
        Set<Integer> out = new HashSet<>();
        for (String r : v.split(",")) {
            switch (r.strip().toLowerCase(Locale.ROOT)) {
                case "1-0", "white" -> out.add(GameRecord.WHITE_WINS);
                case "0-1", "black" -> out.add(GameRecord.BLACK_WINS);
                case "1/2-1/2", "draw", "=" -> out.add(GameRecord.DRAW);
                case "decisive" -> {
                    out.add(GameRecord.WHITE_WINS);
                    out.add(GameRecord.BLACK_WINS);
                }
                case "*", "unknown" -> out.add(GameRecord.UNKNOWN);
                default -> throw new IllegalArgumentException("bad result '" + r + "'");
            }
        }
        return out;
    }

    static Query.Color color(String v) {
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "white", "w" -> Query.Color.WHITE;
            case "black", "b" -> Query.Color.BLACK;
            case "any", "either" -> Query.Color.ANY;
            default -> throw new IllegalArgumentException("bad color '" + v + "'");
        };
    }

    static Boolean bool(String v) {
        return switch (v.strip().toLowerCase(Locale.ROOT)) {
            case "yes", "true", "1", "y" -> true;
            case "no", "false", "0", "n" -> false;
            default -> throw new IllegalArgumentException("expected yes/no, got '" + v + "'");
        };
    }

    private static String upper(String v) {
        return v.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String unquote(String v) {
        String s = v.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    /** Splits on whitespace and parentheses outside double quotes. */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '"') quoted = !quoted;
            if ((Character.isWhitespace(c) || c == '(' || c == ')') && !quoted) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
                if (c != ' ' && !Character.isWhitespace(c)) out.add(String.valueOf(c));
            } else {
                cur.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("unclosed quote");
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
