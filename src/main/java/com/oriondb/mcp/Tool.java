package com.oriondb.mcp;

import com.google.gson.JsonObject;
import com.oriondb.db.Batch;

/**
 * One MCP tool. Read tools have a {@link Handler}; mutating tools have a
 * {@link Mutation} that runs inside a database batch, so the {@code batch}
 * tool can combine several of them atomically.
 */
record Tool(String name, String title, String description, JsonObject schema, Kind kind, Handler handler,
            Mutation mutation) {

    enum Kind { READ, WRITE, DESTRUCTIVE }

    @FunctionalInterface
    interface Handler {
        ToolResult call(Args args) throws Exception;
    }

    @FunctionalInterface
    interface Mutation {
        ToolResult apply(Batch batch, Args args) throws Exception;
    }

    static Tool read(String name, String title, String description, Schema schema, Handler handler) {
        return new Tool(name, title, description, schema.build(), Kind.READ, handler, null);
    }

    /** A tool that mutates through its own handler (not usable inside {@code batch}). */
    static Tool write(String name, String title, String description, Schema schema, Handler handler) {
        return new Tool(name, title, description, schema.build(), Kind.WRITE, handler, null);
    }

    static Tool mutation(String name, String title, String description, Schema schema, boolean destructive,
                         Mutation mutation) {
        return new Tool(name, title, description, schema.build(), destructive ? Kind.DESTRUCTIVE : Kind.WRITE, null, mutation);
    }

    boolean batchable() {
        return mutation != null;
    }

    /** The entry for {@code tools/list}. */
    JsonObject toJson() {
        JsonObject o = Json.obj("name", name, "title", title, "description", description, "inputSchema", schema);
        o.add("annotations", Json.obj("title", title,
            "readOnlyHint", kind == Kind.READ,
            "destructiveHint", kind == Kind.DESTRUCTIVE,
            "idempotentHint", kind == Kind.READ,
            "openWorldHint", false));
        return o;
    }
}
