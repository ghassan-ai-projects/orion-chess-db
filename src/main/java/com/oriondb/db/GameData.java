package com.oriondb.db;

import com.oriondb.analysis.Material;
import com.oriondb.analysis.Motifs;
import com.oriondb.analysis.PawnFeatures;
import com.oriondb.chess.Board;
import com.oriondb.eco.EcoClassifier;
import com.oriondb.model.Game;
import com.oriondb.model.Games;

import java.util.List;

/**
 * Derived, indexable data for one game, computed in a single mainline
 * replay: position hashes, material keys, motif and pawn-feature masks,
 * the ECO classification, and a move-sequence hash for duplicate detection.
 */
public final class GameData {
    /** hashes[k] = position before mainline move k; last entry = final position. */
    final long[] hashes;
    /** materialKeys[k] = packed material of the position at hashes[k]. */
    final int[] materialKeys;
    final int motifMask;
    final int pawnMask;
    final long moveSeqHash;
    final String eco;          // book classification (may differ from the tag), or null
    final String openingName;  // book opening name, or null
    final String finalFen;

    private GameData(long[] hashes, int[] materialKeys, int motifMask, int pawnMask,
                     long moveSeqHash, String eco, String openingName, String finalFen) {
        this.hashes = hashes;
        this.materialKeys = materialKeys;
        this.motifMask = motifMask;
        this.pawnMask = pawnMask;
        this.moveSeqHash = moveSeqHash;
        this.eco = eco;
        this.openingName = openingName;
        this.finalFen = finalFen;
    }

    /**
     * @throws IllegalArgumentException if the mainline contains a move that
     *         does not replay (games from PgnReader are always replayable)
     */
    static GameData build(Game game) {
        int plyCount = game.plyCount();
        long[] hashes = new long[plyCount + 1];
        int[] materialKeys = new int[plyCount + 1];
        int[] masks = new int[3]; // [motifs, pawnFeatures, pliesVisited]

        Board start = Games.startingBoard(game);
        hashes[0] = start.zobrist();
        materialKeys[0] = Material.key(start);
        masks[1] = PawnFeatures.compute(start);

        Board finalBoard = Games.replayMainline(game, (ply, before, move, after) -> {
            hashes[ply + 1] = after.zobrist();
            materialKeys[ply + 1] = Material.key(after);
            masks[0] |= Motifs.detect(before, move, after);
            masks[1] |= PawnFeatures.compute(after);
            masks[2]++;
        });
        if (masks[2] != plyCount) {
            throw new IllegalArgumentException(
                "Game does not replay: illegal move at ply " + masks[2]
                + " (" + game.mainlineSans().get(masks[2]) + ")");
        }

        long seqHash = moveSequenceHash(game.mainlineSans());
        EcoClassifier.Opening opening = EcoClassifier.instance().classify(hashes);

        return new GameData(hashes, materialKeys, masks[0], masks[1], seqHash,
            opening != null ? opening.eco() : null,
            opening != null ? opening.name() : null,
            finalBoard.toFen());
    }

    /** FNV-1a over the SAN sequence. */
    static long moveSequenceHash(List<String> sans) {
        long hash = 0xcbf29ce484222325L;
        for (String san : sans) {
            for (int i = 0; i < san.length(); i++) {
                hash ^= san.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= '\n';
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public long[] hashes() {
        return hashes.clone();
    }

    public int[] materialKeys() {
        return materialKeys.clone();
    }

    public int motifMask() {
        return motifMask;
    }

    public int pawnMask() {
        return pawnMask;
    }

    public long moveSeqHash() {
        return moveSeqHash;
    }

    /** Book ECO code, or null if the game never touches the book. */
    public String eco() {
        return eco;
    }

    /** Book opening name, or null. */
    public String openingName() {
        return openingName;
    }

    public String finalFen() {
        return finalFen;
    }

    public long finalHash() {
        return hashes[hashes.length - 1];
    }

    public int plyCount() {
        return hashes.length - 1;
    }
}
