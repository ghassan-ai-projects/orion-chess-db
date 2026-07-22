package com.oriondb.search;

import com.oriondb.db.Database;
import com.oriondb.db.GameData;
import com.oriondb.model.Game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Duplicate game detection: groups games with identical mainline moves.
 * Groups are labeled by whether the players also match (a true duplicate)
 * or differ (same moves under different names — worth a look).
 */
public final class Duplicates {

    public record Group(List<Integer> gameIds, boolean samePlayers) {}

    private Duplicates() {}

    public static List<Group> find(Database db) {
        Map<Long, List<Integer>> bySeqHash = new HashMap<>();
        for (Game game : db.games()) {
            if (game.plyCount() == 0) continue;
            GameData data = db.data(game.id());
            if (data == null) continue;
            bySeqHash.computeIfAbsent(data.moveSeqHash(), k -> new ArrayList<>())
                .add(game.id());
        }

        List<Group> groups = new ArrayList<>();
        for (List<Integer> ids : bySeqHash.values()) {
            if (ids.size() < 2) continue;
            // Verify against hash collisions and split by identical movetext.
            Map<List<String>, List<Integer>> bySans = new HashMap<>();
            for (int id : ids) {
                bySans.computeIfAbsent(db.game(id).mainlineSans(), k -> new ArrayList<>())
                    .add(id);
            }
            for (List<Integer> confirmed : bySans.values()) {
                if (confirmed.size() < 2) continue;
                boolean samePlayers = true;
                Game first = db.game(confirmed.get(0));
                for (int i = 1; i < confirmed.size() && samePlayers; i++) {
                    Game other = db.game(confirmed.get(i));
                    samePlayers = first.white().equalsIgnoreCase(other.white())
                        && first.black().equalsIgnoreCase(other.black());
                }
                groups.add(new Group(confirmed, samePlayers));
            }
        }
        groups.sort((a, b) -> Integer.compare(b.gameIds().size(), a.gameIds().size()));
        return groups;
    }
}
