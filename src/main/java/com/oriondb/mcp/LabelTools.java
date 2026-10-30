package com.oriondb.mcp;

import com.oriondb.db.Batch;

import java.util.ArrayList;
import java.util.List;

/** label_games, manage_label. */
final class LabelTools {
    private LabelTools() {}

    static List<Tool> tools(Context ctx) {
        return List.of(
            Tool.mutation("label_games", "Label games",
                "Add, remove or replace labels (free-form categories such as 'prep', 'endgame-study', 'to-review') on games "
                    + "selected by 'ids' or by 'query' (text query language, see search_games). Labels are lowercase and "
                    + "stored in the PGN (OrionLabels tag). Returns the number of games changed.",
                Schema.object()
                    .ints("ids", "Game ids.")
                    .str("query", "Text query selecting games, e.g. 'player:tal motif:sacrifice' (used when ids are absent).")
                    .strings("add", "Labels to add.")
                    .strings("remove", "Labels to remove.")
                    .strings("set", "Replace all labels with exactly these (an empty array clears them); excludes add/remove."),
                false, (b, a) -> label(ctx, b, a)),
            Tool.mutation("manage_label", "Manage label",
                "Rename a label everywhere (renaming onto an existing label merges them), merge one label into another, or "
                    + "delete a label from every game. Returns the number of games changed.",
                Schema.object()
                    .choice("action", "What to do.", "rename", "merge", "delete")
                    .str("label", "The label to rename/merge/delete.")
                    .str("to", "rename/merge: the new (or target) label.")
                    .required("action", "label"),
                false, (b, a) -> manage(b, a)));
    }

    private static ToolResult label(Context ctx, Batch b, Args a) {
        boolean set = a.has("set");
        List<String> add = a.strings("add"), remove = a.strings("remove"), labels = a.strings("set");
        if (set && (!add.isEmpty() || !remove.isEmpty())) throw new ToolException("'set' cannot be combined with 'add'/'remove'");
        if (!set && add.isEmpty() && remove.isEmpty()) throw new ToolException("give 'add', 'remove' or 'set'");
        List<Integer> ids = SearchTools.select(ctx.snap(), a, false);
        List<Integer> changed = new ArrayList<>();
        for (int id : ids) {
            if (!b.exists(id)) continue;
            boolean c = set ? b.setLabels(id, labels) : b.updateLabels(id, add, remove);
            if (c) changed.add(id);
        }
        StringBuilder sb = new StringBuilder("Changed labels on ").append(changed.size()).append(" of ").append(ids.size())
            .append(ids.size() == 1 ? " selected game" : " selected games");
        if (set) sb.append(" (set to ").append(labels.isEmpty() ? "none" : String.join(", ", labels)).append(')');
        if (!add.isEmpty()) sb.append(" (+").append(String.join(", +", add)).append(')');
        if (!remove.isEmpty()) sb.append(" (-").append(String.join(", -", remove)).append(')');
        if (!changed.isEmpty()) sb.append(": ").append(EditTools.ranges(changed.stream().sorted().toList(), 40));
        return ToolResult.of(sb.toString(), Json.obj("selected", ids.size(), "changed", changed.size(),
            "changed_ids", changed.size() > 1000 ? changed.subList(0, 1000) : changed));
    }

    private static ToolResult manage(Batch b, Args a) {
        String action = a.requireStr("action"), label = a.requireStr("label");
        int changed;
        String text;
        switch (action) {
            case "rename", "merge" -> {
                String to = a.requireStr("to");
                changed = b.renameLabel(label, to);
                text = (action.equals("merge") ? "Merged '" + label + "' into '" : "Renamed '" + label + "' to '") + to.strip().toLowerCase()
                    + "' on " + changed + " games";
            }
            case "delete" -> {
                if (a.has("to")) throw new ToolException("'to' is not used with delete");
                changed = b.deleteLabel(label);
                text = "Removed '" + label + "' from " + changed + " games";
            }
            default -> throw new ToolException("unknown action '" + action + "' (rename, merge or delete)");
        }
        if (changed == 0) text += " (no game had that label)";
        return ToolResult.of(text, Json.obj("action", action, "changed", changed));
    }
}
