package com.oriondb.cli;

import com.oriondb.db.Database;
import com.oriondb.db.Snapshot;
import com.oriondb.query.Searcher;
import com.oriondb.stats.DatabaseStats;

import java.util.List;
import java.util.Map;

/** Label management: labels, label, label-rename, label-delete. */
final class LabelCommands {
    private LabelCommands() {}

    static List<Command> commands() {
        return List.of(
            new Command("labels", "<db> [--format table|tsv|json]",
                "List labels with their game counts",
                """
                Labels are free-form categories stored in each game's OrionLabels tag
                (lowercase). Search them with label:, anylabel: and nolabel:.
                """,
                Args.Spec.of("format="), LabelCommands::labels),
            new Command("label", "<db> (--ids 1,2 | --query \"...\") [--add a,b] [--remove c] [--set x,y]",
                "Add, remove or set labels on games chosen by id or query",
                """
                Options:
                  --ids LIST       games by id (e.g. 3,7,10-20)
                  --query "..."    games matching a query (see 'oriondb help search')
                  --add a,b        add labels
                  --remove c       remove labels
                  --set x,y        replace all labels (use --set "" to clear)

                Examples:
                  oriondb label my.pgn --query "player:carlsen eco:B90-B99" --add najdorf,prep
                  oriondb label my.pgn --ids 12,15 --remove prep
                """,
                Args.Spec.of("ids=", "query=", "add=", "remove=", "set="), LabelCommands::label),
            new Command("label-rename", "<db> <from> <to>",
                "Rename a label on every game (merges into an existing label)",
                "", Args.Spec.of(), LabelCommands::rename),
            new Command("label-delete", "<db> <label>",
                "Remove a label from every game",
                "", Args.Spec.of(), LabelCommands::deleteLabel));
    }

    static int labels(Args a, Context ctx) {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        String format = a.choice("format", Table.FORMATS);
        try (Database db = ctx.openReadOnly(file)) {
            Map<String, Integer> counts = DatabaseStats.labels(db.snapshot());
            if (counts.isEmpty() && !format.equals("json")) {
                ctx.out.println("no labels in " + file);
                return 0;
            }
            Table t = new Table("label", "games");
            for (Map.Entry<String, Integer> e : counts.entrySet()) t.row(e.getKey(), e.getValue());
            t.print(ctx.out, format);
            return 0;
        }
    }

    static int label(Args a, Context ctx) {
        String file = a.require(0, "database file");
        a.maxPositionals(1);
        a.exclusive("ids", "query");
        if (!a.has("ids") && !a.has("query")) throw new UsageException("choose games with --ids or --query");
        if (!a.has("add") && !a.has("remove") && !a.has("set")) throw new UsageException("nothing to do: give --add, --remove or --set");
        if (a.has("set") && (a.has("add") || a.has("remove"))) throw new UsageException("--set cannot be combined with --add/--remove");
        List<String> add = a.list("add"), remove = a.list("remove"), set = a.list("set");
        try (Database db = ctx.openWritable(file)) {
            Snapshot snap = db.snapshot();
            List<Integer> ids;
            if (a.has("ids")) {
                ids = Context.ids(List.of(a.get("ids")));
                List<Integer> missing = ids.stream().filter(i -> snap.find(i) == null).toList();
                if (!missing.isEmpty()) throw new CliException("no game with id " + GameCommands.join(missing) + "; nothing changed");
            } else {
                ids = Searcher.ids(snap, Context.query(a.get("query")));
            }
            if (ids.isEmpty()) {
                ctx.out.println("no games match; nothing changed");
                return 0;
            }
            int changed = db.batch(b -> {
                int n = 0;
                for (int id : ids) {
                    boolean c = a.has("set") ? b.setLabels(id, set) : b.updateLabels(id, add, remove);
                    if (c) n++;
                }
                return n;
            });
            if (changed > 0) db.save();
            ctx.out.println("changed labels on " + changed + " of " + ids.size() + " game" + (ids.size() == 1 ? "" : "s"));
            return 0;
        }
    }

    static int rename(Args a, Context ctx) {
        String file = a.require(0, "database file");
        String from = a.require(1, "label to rename");
        String to = a.require(2, "new label name");
        a.maxPositionals(3);
        try (Database db = ctx.openWritable(file)) {
            int n = db.batch(b -> b.renameLabel(from, to));
            if (n > 0) db.save();
            ctx.out.println(n == 0 ? "no game has label '" + from + "'" : "renamed '" + from + "' to '" + to + "' on " + n + " game" + (n == 1 ? "" : "s"));
            return 0;
        }
    }

    static int deleteLabel(Args a, Context ctx) {
        String file = a.require(0, "database file");
        String label = a.require(1, "label");
        a.maxPositionals(2);
        try (Database db = ctx.openWritable(file)) {
            int n = db.batch(b -> b.deleteLabel(label));
            if (n > 0) db.save();
            ctx.out.println(n == 0 ? "no game has label '" + label + "'" : "removed '" + label + "' from " + n + " game" + (n == 1 ? "" : "s"));
            return 0;
        }
    }
}
