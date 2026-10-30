package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oriondb.engine.EngineException;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The tool registry: lookup, argument validation, error mapping, and the atomic {@code batch} tool. */
final class Tools {
    static final int MAX_BATCH = 1000;

    private final Context ctx;
    private final PrintStream log;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    Tools(Context ctx, PrintStream log) {
        this.ctx = ctx;
        this.log = log;
        List<Tool> all = new ArrayList<>();
        all.addAll(ReadTools.tools(ctx));
        all.addAll(SearchTools.tools(ctx));
        all.addAll(StatsTools.tools(ctx));
        all.addAll(EditTools.tools(ctx));
        all.addAll(LabelTools.tools(ctx));
        all.addAll(EngineTools.tools(ctx));
        all.add(batchTool());
        for (Tool t : all) tools.put(t.name(), t);
    }

    Tool get(String name) {
        return tools.get(name);
    }

    JsonArray list() {
        JsonArray a = new JsonArray();
        for (Tool t : tools.values()) a.add(t.toJson());
        return a;
    }

    /** Runs a tool; every failure becomes an {@code isError} result. */
    ToolResult call(Tool t, JsonObject arguments) {
        try {
            Args a = new Args(arguments);
            a.validate(t.schema());
            if (t.handler() != null) return t.handler().call(a);
            return ctx.mutate(b -> unchecked(() -> t.mutation().apply(b, a)));
        } catch (Exception | StackOverflowError e) {
            return failure(t.name(), e);
        }
    }

    private ToolResult failure(String tool, Throwable e) {
        Throwable c = unwrap(e);
        if (c instanceof ToolException || c instanceof IllegalArgumentException || c instanceof IllegalStateException
                || c instanceof com.oriondb.db.DatabaseException || c instanceof com.oriondb.pgn.PgnException) {
            return ToolResult.error(message(c));
        }
        if (c instanceof EngineException) return ToolResult.error("engine error: " + message(c));
        log.println("oriondb mcp: tool " + tool + " failed");
        c.printStackTrace(log);
        return ToolResult.error("internal error in " + tool + ": " + c.getClass().getSimpleName()
            + (c.getMessage() == null ? "" : ": " + c.getMessage()));
    }

    // ------------------------------------------------------------ batch

    private Tool batchTool() {
        JsonObject item = Schema.object()
            .str("tool", "A mutating tool: import_pgn, add_game, edit_game, set_tags, label_games, manage_label, "
                + "delete_games or remove_duplicates.")
            .prop("arguments", Json.obj("type", "object", "description", "That tool's arguments, exactly as for a direct call."))
            .required("tool")
            .build();
        return Tool.write("batch", "Batch changes",
            "Apply several changes atomically in one transaction: all succeed and are saved together, or nothing changes. "
                + "Each operation is {tool, arguments} for one of the mutating tools (import_pgn, add_game, edit_game, "
                + "set_tags, label_games, manage_label, delete_games, remove_duplicates). Later operations see earlier "
                + "ones' effects by id; 'query' selections are evaluated against the database as it was before the batch.",
            Schema.object()
                .prop("operations", Json.obj("type", "array", "minItems", 1, "maxItems", MAX_BATCH,
                    "description", "Operations, applied in order.", "items", item))
                .required("operations"),
            this::batch);
    }

    private record Op(Tool tool, Args args) {}

    private static final class OpFailed extends RuntimeException {
        final int index;
        final String tool;

        OpFailed(int index, String tool, Throwable cause) {
            super(cause);
            this.index = index;
            this.tool = tool;
        }
    }

    private ToolResult batch(Args a) {
        JsonArray raw = a.array("operations");
        if (raw == null || raw.isEmpty()) throw new ToolException("'operations' must be a non-empty array");
        if (raw.size() > MAX_BATCH) throw new ToolException("at most " + MAX_BATCH + " operations per batch");
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            JsonElement e = raw.get(i);
            String prefix = "operation " + (i + 1) + ": ";
            if (!e.isJsonObject()) throw new ToolException(prefix + "must be an object {tool, arguments}");
            Args oa = new Args(e.getAsJsonObject());
            try {
                oa.validate(Json.obj("properties", Json.obj("tool", true, "arguments", true), "required", Json.arr(List.of("tool"))));
            } catch (ToolException ex) {
                throw new ToolException(prefix + ex.getMessage());
            }
            String name = oa.requireStr("tool");
            Tool t = tools.get(name);
            if (t == null || !t.batchable()) {
                throw new ToolException(prefix + "'" + name + "' cannot be used in a batch (allowed: "
                    + tools.values().stream().filter(Tool::batchable).map(Tool::name).toList() + ")");
            }
            Args args = new Args(oa.obj("arguments"));
            try {
                args.validate(t.schema());
            } catch (ToolException ex) {
                throw new ToolException(prefix + name + ": " + ex.getMessage() + ". Nothing was changed.");
            }
            ops.add(new Op(t, args));
        }
        List<ToolResult> results;
        try {
            results = ctx.mutate(b -> {
                List<ToolResult> out = new ArrayList<>();
                for (int i = 0; i < ops.size(); i++) {
                    Op op = ops.get(i);
                    try {
                        out.add(op.tool().mutation().apply(b, op.args()));
                    } catch (Exception | StackOverflowError ex) {
                        throw new OpFailed(i, op.tool().name(), ex);
                    }
                }
                return out;
            });
        } catch (OpFailed f) {
            ToolResult r = failure(f.tool, f.getCause());
            return ToolResult.error("operation " + (f.index + 1) + " (" + f.tool + ") failed: " + r.text()
                + "\nThe batch was rolled back: nothing was changed.");
        }
        StringBuilder sb = new StringBuilder("Batch applied atomically: ").append(ops.size()).append(" operations.");
        JsonArray out = new JsonArray();
        for (int i = 0; i < results.size(); i++) {
            ToolResult r = results.get(i);
            sb.append("\n[").append(i + 1).append("] ").append(ops.get(i).tool().name()).append(": ").append(r.text());
            JsonObject o = Json.obj("tool", ops.get(i).tool().name(), "text", r.text());
            if (r.structured() != null) o.add("result", r.structured());
            out.add(o);
        }
        return ToolResult.of(sb.toString(), Json.obj("operations", ops.size(), "results", out));
    }

    // ------------------------------------------------------------ errors

    @FunctionalInterface
    private interface Body<R> {
        R run() throws Exception;
    }

    private static <R> R unchecked(Body<R> body) {
        try {
            return body.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new Wrapped(e);
        }
    }

    private static final class Wrapped extends RuntimeException {
        Wrapped(Exception cause) {
            super(cause);
        }
    }

    private static Throwable unwrap(Throwable e) {
        Throwable c = e;
        while ((c instanceof Wrapped || c instanceof OpFailed) && c.getCause() != null) c = c.getCause();
        return c;
    }

    /** A readable message for any failure. */
    static String message(Throwable e) {
        Throwable c = unwrap(e);
        if (c instanceof StackOverflowError) return "the game tree is too deep to process";
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
