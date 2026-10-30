package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builder for a tool's JSON Schema ({@code type: object}, no additional properties). */
final class Schema {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    static Schema object() {
        return new Schema();
    }

    Schema str(String name, String description) {
        return prop(name, Json.obj("type", "string", "description", description));
    }

    Schema choice(String name, String description, String... values) {
        JsonObject p = Json.obj("type", "string", "description", description);
        p.add("enum", Json.arr(java.util.List.of(values)));
        return prop(name, p);
    }

    Schema integer(String name, String description, int min, int max) {
        return prop(name, Json.obj("type", "integer", "description", description, "minimum", min, "maximum", max));
    }

    Schema bool(String name, String description) {
        return prop(name, Json.obj("type", "boolean", "description", description));
    }

    Schema strings(String name, String description) {
        return prop(name, Json.obj("type", "array", "description", description, "items", Json.obj("type", "string")));
    }

    Schema ints(String name, String description) {
        return prop(name, Json.obj("type", "array", "description", description, "items", Json.obj("type", "integer")));
    }

    /** An object mapping names to string values (optionally null). */
    Schema stringMap(String name, String description, boolean nullableValues) {
        JsonObject values = nullableValues
            ? Json.obj("type", Json.arr(java.util.List.of("string", "null")))
            : Json.obj("type", "string");
        return prop(name, Json.obj("type", "object", "description", description, "additionalProperties", values));
    }

    Schema prop(String name, JsonObject schema) {
        properties.add(name, schema);
        return this;
    }

    Schema required(String... names) {
        for (String n : names) required.add(n);
        return this;
    }

    /** Adds every property of another schema (shared argument groups). */
    Schema with(Schema other) {
        other.properties.entrySet().forEach(e -> properties.add(e.getKey(), e.getValue()));
        return this;
    }

    JsonObject build() {
        JsonObject o = Json.obj("type", "object", "properties", properties);
        if (!required.isEmpty()) o.add("required", required);
        o.addProperty("additionalProperties", false);
        return o;
    }
}
