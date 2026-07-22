package com.oriondb.analysis;

import com.oriondb.chess.Board;
import com.oriondb.chess.Move;
import com.oriondb.chess.Piece;
import com.oriondb.chess.Square;
import com.oriondb.model.Game;
import com.oriondb.model.Games;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tactical motif detection. Motifs are attributed to the move that creates
 * them: after the move is played, the position is examined from the mover's
 * point of view.
 */
public final class Motifs {
    public static final int FORK = 1;
    public static final int PIN = 1 << 1;               // absolute pin (to the king)
    public static final int SKEWER = 1 << 2;
    public static final int DISCOVERED_CHECK = 1 << 3;
    public static final int DOUBLE_CHECK = 1 << 4;
    public static final int PROMOTION = 1 << 5;
    public static final int UNDERPROMOTION = 1 << 6;
    public static final int EN_PASSANT = 1 << 7;
    public static final int CHECKMATE = 1 << 8;
    public static final int CASTLING = 1 << 9;

    private static final int[] PIECE_VALUES = {1, 3, 3, 5, 9, 1000}; // P N B R Q K

    private Motifs() {}

    /** One tactical moment in a game. */
    public record Moment(int ply, String san, int motifs) {
        public List<String> motifNames() {
            return names(motifs);
        }
    }

    /**
     * Motifs created by playing {@code move} in {@code before},
     * yielding {@code after}.
     */
    public static int detect(Board before, int move, Board after) {
        if (Move.isNull(move)) return 0;

        int mask = 0;
        int special = Move.special(move);
        int us = before.sideToMove();
        int them = Piece.opposite(us);

        // Move-encoded motifs
        int promo = Move.promotion(move);
        if (promo != 0) {
            mask |= PROMOTION;
            if (promo != Piece.QUEEN) mask |= UNDERPROMOTION;
        }
        if (special == Move.EN_PASSANT) mask |= EN_PASSANT;
        if (special == Move.CASTLE_KINGSIDE || special == Move.CASTLE_QUEENSIDE) mask |= CASTLING;

        // Checks
        int theirKing = after.kingSquare(them);
        if (theirKing != Square.NONE) {
            List<Integer> checkers = attackersOf(after, theirKing, us);
            if (checkers.size() >= 2) {
                mask |= DOUBLE_CHECK | DISCOVERED_CHECK;
            } else if (checkers.size() == 1) {
                int checkerSquare = checkers.get(0);
                if (checkerSquare != moverDestination(move, us)) {
                    mask |= DISCOVERED_CHECK;
                }
            }
            if (after.isCheckmate()) {
                mask |= CHECKMATE;
            }
        }

        // Fork by the moved piece
        int dest = moverDestination(move, us);
        if (dest != Square.NONE && forkAt(after, dest, us)) {
            mask |= FORK;
        }

        // Pins and skewers newly created by this move (present after, absent before)
        mask |= pinsAndSkewers(after, us) & ~pinsAndSkewers(before, us);

        return mask;
    }

    /** Destination square of the piece that moved (the rook for castling counts too). */
    private static int moverDestination(int move, int us) {
        int special = Move.special(move);
        if (special == Move.CASTLE_KINGSIDE) {
            return us == Piece.WHITE ? Square.G1 : Square.G8;
        }
        if (special == Move.CASTLE_QUEENSIDE) {
            return us == Piece.WHITE ? Square.C1 : Square.C8;
        }
        return Move.to(move);
    }

    private static List<Integer> attackersOf(Board board, int target, int by) {
        List<Integer> attackers = new ArrayList<>(2);
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.pieceAt(sq);
            if (piece == Piece.NONE || Piece.color(piece) != by) continue;
            if (attacks(board, sq, target)) {
                attackers.add(sq);
            }
        }
        return attackers;
    }

    /** Does the piece on {@code from} attack {@code target}? */
    private static boolean attacks(Board board, int from, int target) {
        int piece = board.pieceAt(from);
        if (piece == Piece.NONE || from == target) return false;
        int type = Piece.type(piece);
        int ff = Square.file(from), fr = Square.rank(from);
        int tf = Square.file(target), tr = Square.rank(target);
        int df = tf - ff, dr = tr - fr;

        switch (type) {
            case Piece.PAWN: {
                int dir = Piece.isWhite(piece) ? 1 : -1;
                return dr == dir && Math.abs(df) == 1;
            }
            case Piece.KNIGHT:
                return (Math.abs(df) == 2 && Math.abs(dr) == 1)
                    || (Math.abs(df) == 1 && Math.abs(dr) == 2);
            case Piece.KING:
                return Math.abs(df) <= 1 && Math.abs(dr) <= 1;
            case Piece.BISHOP:
                if (Math.abs(df) != Math.abs(dr)) return false;
                return rayClear(board, ff, fr, tf, tr);
            case Piece.ROOK:
                if (df != 0 && dr != 0) return false;
                return rayClear(board, ff, fr, tf, tr);
            case Piece.QUEEN:
                if (df != 0 && dr != 0 && Math.abs(df) != Math.abs(dr)) return false;
                return rayClear(board, ff, fr, tf, tr);
            default:
                return false;
        }
    }

    private static boolean rayClear(Board board, int ff, int fr, int tf, int tr) {
        int stepF = Integer.compare(tf, ff);
        int stepR = Integer.compare(tr, fr);
        int f = ff + stepF, r = fr + stepR;
        while (f != tf || r != tr) {
            if (board.pieceAt(Square.of(f, r)) != Piece.NONE) return false;
            f += stepF;
            r += stepR;
        }
        return true;
    }

    /**
     * A fork: the piece on {@code square} attacks two or more enemy pieces
     * that are each "worth attacking" — the king, a more valuable piece, or
     * an undefended one.
     */
    private static boolean forkAt(Board board, int square, int us) {
        int piece = board.pieceAt(square);
        if (piece == Piece.NONE) return false;
        int myValue = PIECE_VALUES[Piece.type(piece)];
        int them = Piece.opposite(us);
        int targets = 0;
        for (int sq = 0; sq < 64; sq++) {
            int enemy = board.pieceAt(sq);
            if (enemy == Piece.NONE || Piece.color(enemy) != them) continue;
            if (!attacks(board, square, sq)) continue;
            int enemyValue = PIECE_VALUES[Piece.type(enemy)];
            boolean worthwhile = Piece.type(enemy) == Piece.KING
                || enemyValue > myValue
                || !isDefended(board, sq, them);
            if (worthwhile && ++targets >= 2) return true;
        }
        return false;
    }

    private static boolean isDefended(Board board, int square, int by) {
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.pieceAt(sq);
            if (piece == Piece.NONE || Piece.color(piece) != by || sq == square) continue;
            if (attacks(board, sq, square)) return true;
        }
        return false;
    }

    /**
     * Absolute pins and skewers by {@code us} sliders: walk each slider's
     * rays; when the first two pieces hit are both enemies, classify by
     * their values (pin if the far piece is the king, skewer if the near
     * piece outranks the far one).
     */
    private static int pinsAndSkewers(Board board, int us) {
        int mask = 0;
        int them = Piece.opposite(us);
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.pieceAt(sq);
            if (piece == Piece.NONE || Piece.color(piece) != us) continue;
            int type = Piece.type(piece);
            if (type != Piece.BISHOP && type != Piece.ROOK && type != Piece.QUEEN) continue;

            int[][] dirs = sliderDirs(type);
            for (int[] d : dirs) {
                int near = Square.NONE;
                int f = Square.file(sq) + d[0];
                int r = Square.rank(sq) + d[1];
                while (Square.isValid(f, r)) {
                    int here = Square.of(f, r);
                    int occupant = board.pieceAt(here);
                    if (occupant != Piece.NONE) {
                        if (Piece.color(occupant) != them) break;
                        if (near == Square.NONE) {
                            near = here;
                        } else {
                            int nearType = Piece.type(board.pieceAt(near));
                            int farType = Piece.type(occupant);
                            if (farType == Piece.KING && nearType != Piece.KING) {
                                mask |= PIN;
                            } else if (PIECE_VALUES[nearType] > PIECE_VALUES[farType]) {
                                mask |= SKEWER;
                            }
                            break;
                        }
                    }
                    f += d[0];
                    r += d[1];
                }
            }
        }
        return mask;
    }

    private static int[][] sliderDirs(int type) {
        int[][] diag = {{1, 1}, {1, -1}, {-1, -1}, {-1, 1}};
        int[][] ortho = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        int[][] all = {{1, 1}, {1, -1}, {-1, -1}, {-1, 1}, {0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        return type == Piece.BISHOP ? diag : type == Piece.ROOK ? ortho : all;
    }

    /** Replay a game and list its tactical moments. */
    public static List<Moment> moments(Game game) {
        List<Moment> moments = new ArrayList<>();
        List<String> sans = game.mainlineSans();
        Games.replayMainline(game, (ply, before, move, after) -> {
            int mask = detect(before, move, after);
            if (mask != 0) {
                moments.add(new Moment(ply, sans.get(ply), mask));
            }
        });
        return moments;
    }

    public static List<String> names(int mask) {
        List<String> names = new ArrayList<>();
        if ((mask & FORK) != 0) names.add("fork");
        if ((mask & PIN) != 0) names.add("pin");
        if ((mask & SKEWER) != 0) names.add("skewer");
        if ((mask & DISCOVERED_CHECK) != 0) names.add("discovered-check");
        if ((mask & DOUBLE_CHECK) != 0) names.add("double-check");
        if ((mask & PROMOTION) != 0) names.add("promotion");
        if ((mask & UNDERPROMOTION) != 0) names.add("underpromotion");
        if ((mask & EN_PASSANT) != 0) names.add("en-passant");
        if ((mask & CHECKMATE) != 0) names.add("checkmate");
        if ((mask & CASTLING) != 0) names.add("castling");
        return names;
    }

    /** Parse a motif name to its bit; 0 if unknown. */
    public static int fromName(String name) {
        if (name == null) return 0;
        switch (name.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "fork": return FORK;
            case "pin": return PIN;
            case "skewer": return SKEWER;
            case "discovered-check": return DISCOVERED_CHECK;
            case "double-check": return DOUBLE_CHECK;
            case "promotion": return PROMOTION;
            case "underpromotion": return UNDERPROMOTION;
            case "en-passant": return EN_PASSANT;
            case "checkmate": return CHECKMATE;
            case "castling": return CASTLING;
            default: return 0;
        }
    }
}
