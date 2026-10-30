package com.oriondb.analysis;

import com.oriondb.chess.Bitboards;
import com.oriondb.chess.Move;
import com.oriondb.chess.Piece;
import com.oriondb.chess.Position;
import com.oriondb.chess.Square;

import java.util.ArrayList;
import java.util.List;

/** Finds tactical motifs along a line of positions. */
public final class MotifDetector {

    /** A motif occurring on a move: {@code ply} is 1 for the first move of the line. */
    public record Hit(int ply, Motif motif) {}

    private MotifDetector() {}

    /**
     * @param positions positions[0] is the start, positions[i] the position after moves[i-1]
     * @param moves     the moves of the line
     */
    public static List<Hit> detect(Position[] positions, int[] moves) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < moves.length; i++) {
            int move = moves[i];
            if (Move.isNull(move)) continue;
            Position before = positions[i], after = positions[i + 1];
            int ply = i + 1;
            int us = before.sideToMove(), them = us ^ 1;
            int dest = Move.destination(move);

            if (Move.isCastle(move)) hits.add(new Hit(ply, Move.isKingsideCastle(move) ? Motif.CASTLING_KINGSIDE : Motif.CASTLING_QUEENSIDE));
            if (Move.isEnPassant(move)) hits.add(new Hit(ply, Motif.EN_PASSANT));
            if (Move.isPromotion(move)) {
                hits.add(new Hit(ply, Motif.PROMOTION));
                if (Move.promotionType(move) != Piece.QUEEN) hits.add(new Hit(ply, Motif.UNDERPROMOTION));
            }

            long checkers = after.checkers();
            boolean mate = false;
            if (checkers != 0) {
                if (Long.bitCount(checkers) >= 2) hits.add(new Hit(ply, Motif.DOUBLE_CHECK));
                if ((checkers & ~Square.bit(dest)) != 0 && !Move.isCastle(move)) hits.add(new Hit(ply, Motif.DISCOVERED_CHECK));
                if (after.legalMoves().length == 0) {
                    mate = true;
                    hits.add(new Hit(ply, Motif.CHECKMATE));
                    if (isSmothered(after, checkers)) hits.add(new Hit(ply, Motif.SMOTHERED_MATE));
                    if (isBackRank(after, checkers)) hits.add(new Hit(ply, Motif.BACK_RANK_MATE));
                }
            } else if (after.legalMoves().length == 0) {
                hits.add(new Hit(ply, Motif.STALEMATE));
            }
            if (mate) continue;

            if (isFork(after, dest, us)) hits.add(new Hit(ply, Motif.FORK));
            int pinOrSkewer = pinOrSkewer(after, dest, us, them);
            if ((pinOrSkewer & 1) != 0) hits.add(new Hit(ply, Motif.PIN));
            if ((pinOrSkewer & 2) != 0) hits.add(new Hit(ply, Motif.SKEWER));

            Motif sac = sacrifice(positions, moves, i);
            if (sac != null) {
                hits.add(new Hit(ply, Motif.SACRIFICE));
                if (sac == Motif.QUEEN_SACRIFICE) hits.add(new Hit(ply, sac));
            }
        }
        return hits;
    }

    private static boolean isSmothered(Position p, long checkers) {
        if (Long.bitCount(checkers) != 1) return false;
        int checker = Long.numberOfTrailingZeros(checkers);
        if (Piece.type(p.pieceAt(checker)) != Piece.KNIGHT) return false;
        int side = p.sideToMove();
        int king = p.kingSquare(side);
        return (Bitboards.kingAttacks(king) & ~p.occupied(side)) == 0;
    }

    private static boolean isBackRank(Position p, long checkers) {
        if (Long.bitCount(checkers) != 1) return false;
        int checker = Long.numberOfTrailingZeros(checkers);
        int type = Piece.type(p.pieceAt(checker));
        int side = p.sideToMove();
        int king = p.kingSquare(side);
        if (type != Piece.ROOK && type != Piece.QUEEN) return false;
        if (Square.relativeRank(king, side) != 0 || Square.rank(checker) != Square.rank(king)) return false;
        // The king's escape squares on the next rank are blocked by its own pieces.
        long forward = Bitboards.kingAttacks(king) & ~(Bitboards.RANK_1 << (8 * Square.rank(king)));
        return (forward & ~p.occupied(side)) == 0;
    }

    private static boolean isFork(Position after, int sq, int us) {
        int piece = after.pieceAt(sq);
        if (piece < 0) return false;
        int forkerValue = valueOf(Piece.type(piece));
        int them = us ^ 1;
        long targets = after.attacksFrom(sq) & after.occupied(them);
        int count = 0;
        for (long t = targets; t != 0; t &= t - 1) {
            int s = Long.numberOfTrailingZeros(t);
            int type = Piece.type(after.pieceAt(s));
            if (type == Piece.PAWN) continue;
            boolean valuable = valueOf(type) > forkerValue;
            boolean undefended = after.attackers(s, them) == 0;
            if (valuable || undefended) count++;
        }
        // The forker must not simply be lost for nothing.
        boolean forkerSafe = after.attackers(sq, them) == 0 || after.attackers(sq, us) != 0 || forkerValue <= 1;
        return count >= 2 && forkerSafe;
    }

    /** Bit 0: the moved slider pins a piece to the king; bit 1: it skewers K or Q. */
    private static int pinOrSkewer(Position after, int sq, int us, int them) {
        int piece = after.pieceAt(sq);
        if (piece < 0) return 0;
        int type = Piece.type(piece);
        if (type != Piece.BISHOP && type != Piece.ROOK && type != Piece.QUEEN) return 0;
        long occ = after.occupied();
        int result = 0;
        int king = after.kingSquare(them);
        long line = Bitboards.line(sq, king);
        if (line != 0 && slidesAlong(type, sq, king)) {
            long between = Bitboards.between(sq, king) & occ;
            if (Long.bitCount(between) == 1 && (between & after.occupied(them)) != 0) {
                int pinned = Long.numberOfTrailingZeros(between);
                if (Piece.type(after.pieceAt(pinned)) != Piece.PAWN) result |= 1;
            }
        }
        // Skewer: directly attacked K or Q with a lesser non-pawn piece behind it on the same line.
        long attacked = Bitboards.attacks(type, sq, occ) & after.occupied(them);
        for (long t = attacked; t != 0; t &= t - 1) {
            int front = Long.numberOfTrailingZeros(t);
            int frontType = Piece.type(after.pieceAt(front));
            if (frontType != Piece.KING && frontType != Piece.QUEEN) continue;
            // Squares that become visible once the front piece steps aside: the piece behind it.
            long beyond = Bitboards.attacks(type, sq, occ & ~Square.bit(front)) & ~Bitboards.attacks(type, sq, occ)
                & Bitboards.line(sq, front) & after.occupied(them);
            for (long b = beyond; b != 0; b &= b - 1) {
                int behindType = Piece.type(after.pieceAt(Long.numberOfTrailingZeros(b)));
                if (behindType != Piece.PAWN && valueOf(behindType) < valueOf(frontType)) result |= 2;
            }
        }
        return result;
    }

    private static boolean slidesAlong(int type, int a, int b) {
        boolean straight = Square.file(a) == Square.file(b) || Square.rank(a) == Square.rank(b);
        return type == Piece.QUEEN || (type == Piece.ROOK) == straight;
    }

    /**
     * A sacrifice: the moved piece (or the piece left en prise on the destination)
     * is captured next ply and material is not regained one ply later.
     */
    private static Motif sacrifice(Position[] positions, int[] moves, int i) {
        if (i + 1 >= moves.length) return null;
        Position after = positions[i + 1];
        int dest = Move.destination(moves[i]);
        int piece = after.pieceAt(dest);
        if (piece < 0) return null;
        int type = Piece.type(piece);
        if (type == Piece.PAWN || type == Piece.KING) return null;
        int reply = moves[i + 1];
        if (Move.isNull(reply) || Move.to(reply) != dest || Move.isCastle(reply)) return null;
        int us = positions[i].sideToMove();
        int before = signedBalance(positions[i], us);
        int end = Math.min(i + 3, moves.length);
        int later = signedBalance(positions[end], us);
        if (before - later < 2) return null;
        // Mating attacks still count; otherwise require the deficit to persist one more move pair.
        if (end + 2 <= moves.length && signedBalance(positions[end + 2], us) >= before - 1) return null;
        return type == Piece.QUEEN ? Motif.QUEEN_SACRIFICE : Motif.SACRIFICE;
    }

    private static int signedBalance(Position p, int us) {
        int b = Material.balance(Material.signature(p));
        return us == Piece.WHITE ? b : -b;
    }

    private static int valueOf(int type) {
        return type == Piece.KING ? 100 : Piece.value(type);
    }
}
