package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** What a tool returns: readable text, optional structured JSON, and whether it failed. */
record ToolResult(String text, JsonObject structured, boolean isError) {

    static ToolResult of(String text) {
        return new ToolResult(text, null, false);
    }

    static ToolResult of(String text, JsonObject structured) {
        return new ToolResult(text, structured, false);
    }

    static ToolResult error(String message) {
        return new ToolResult(message, null, true);
    }

    /** The MCP {@code CallToolResult}. */
    JsonObject toJson() {
        JsonArray content = new JsonArray();
        content.add(Json.obj("type", "text", "text", text));
        JsonObject o = Json.obj("content", content);
        if (structured != null) o.add("structuredContent", structured);
        if (isError) o.addProperty("isError", true);
        return o;
    }
}
