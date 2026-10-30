package com.oriondb.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Collection;
import java.util.Map;

/** Small JSON helpers over Gson's tree model. */
final class Json {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Json() {}

    /** An object from alternating keys and values; null values are skipped. */
    static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) o.add((String) kv[i], of(kv[i + 1]));
        }
        return o;
    }

    static JsonArray arr(Collection<?> items) {
        JsonArray a = new JsonArray();
        for (Object x : items) a.add(of(x));
        return a;
    }

    /** Converts strings, numbers, booleans, collections, maps and JSON trees; anything else via Gson. */
    static JsonElement of(Object v) {
        return switch (v) {
            case null -> JsonNull.INSTANCE;
            case JsonElement e -> e;
            case String s -> new JsonPrimitive(s);
            case Number n -> new JsonPrimitive(n);
            case Boolean b -> new JsonPrimitive(b);
            case Collection<?> c -> arr(c);
            case int[] ints -> {
                JsonArray a = new JsonArray();
                for (int x : ints) a.add(x);
                yield a;
            }
            case Map<?, ?> m -> {
                JsonObject o = new JsonObject();
                m.forEach((k, x) -> o.add(String.valueOf(k), of(x)));
                yield o;
            }
            default -> GSON.toJsonTree(v);
        };
    }

    static String write(JsonElement e) {
        return GSON.toJson(e);
    }
}
