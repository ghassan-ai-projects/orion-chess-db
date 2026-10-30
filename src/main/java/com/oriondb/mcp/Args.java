package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Typed, validating access to a tool call's arguments. Problems throw {@link ToolException}. */
final class Args {
    private final JsonObject o;

    Args(JsonObject o) {
        this.o = o == null ? new JsonObject() : o;
    }

    /** Rejects arguments the schema does not declare and missing required ones. */
    void validate(JsonObject schema) {
        JsonObject props = schema.getAsJsonObject("properties");
        for (String key : o.keySet()) {
            if (!props.has(key)) {
                throw new ToolException("unknown argument '" + key + "'; expected one of: " + new TreeSet<>(props.keySet()));
            }
        }
        JsonArray required = schema.getAsJsonArray("required");
        if (required != null) {
            for (JsonElement r : required) {
                if (!present(r.getAsString())) throw new ToolException("missing required argument '" + r.getAsString() + "'");
            }
        }
    }

    JsonObject raw() {
        return o;
    }

    /** True if the argument is given and not null. */
    boolean has(String name) {
        return present(name);
    }

    private boolean present(String name) {
        return o.has(name) && !o.get(name).isJsonNull();
    }

    String str(String name) {
        if (!present(name)) return null;
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive()) throw type(name, "a string");
        return e.getAsString();
    }

    String str(String name, String fallback) {
        String s = str(name);
        return s == null || s.isBlank() ? fallback : s;
    }

    String requireStr(String name) {
        String s = str(name);
        if (s == null || s.isBlank()) throw new ToolException("'" + name + "' is required");
        return s;
    }

    Integer optInt(String name) {
        if (!present(name)) return null;
        JsonElement e = o.get(name);
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            try {
                double d = p.isNumber() ? p.getAsDouble() : Double.parseDouble(p.getAsString().strip());
                if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) return (int) d;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw type(name, "an integer");
    }

    int integer(String name, int fallback, int min, int max) {
        Integer v = optInt(name);
        if (v == null) return fallback;
        if (v < min || v > max) throw new ToolException("'" + name + "' must be between " + min + " and " + max + " (got " + v + ")");
        return v;
    }

    int requireInt(String name) {
        Integer v = optInt(name);
        if (v == null) throw new ToolException("'" + name + "' is required");
        return v;
    }

    Boolean optBool(String name) {
        if (!present(name)) return null;
        JsonElement e = o.get(name);
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) {
                switch (p.getAsString().strip().toLowerCase()) {
                    case "true", "yes" -> { return true; }
                    case "false", "no" -> { return false; }
                    default -> { }
                }
            }
        }
        throw type(name, "a boolean");
    }

    boolean bool(String name, boolean fallback) {
        Boolean b = optBool(name);
        return b == null ? fallback : b;
    }

    /** A list of strings; a single string is split on commas. */
    List<String> strings(String name) {
        List<String> out = new ArrayList<>();
        if (!present(name)) return out;
        JsonElement e = o.get(name);
        if (e.isJsonPrimitive()) {
            for (String s : e.getAsString().split(",")) if (!s.isBlank()) out.add(s.strip());
            return out;
        }
        if (!e.isJsonArray()) throw type(name, "an array of strings");
        for (JsonElement x : e.getAsJsonArray()) {
            if (!x.isJsonPrimitive()) throw type(name, "an array of strings");
            if (!x.getAsString().isBlank()) out.add(x.getAsString().strip());
        }
        return out;
    }

    /** A list of integers; a single integer (or "1,2,3") is accepted too. */
    List<Integer> ints(String name) {
        List<Integer> out = new ArrayList<>();
        if (!present(name)) return out;
        JsonElement e = o.get(name);
        List<JsonElement> items = new ArrayList<>();
        if (e.isJsonArray()) e.getAsJsonArray().forEach(items::add);
        else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            for (String s : e.getAsString().split(",")) if (!s.isBlank()) items.add(new JsonPrimitive(s.strip()));
        } else items.add(e);
        for (JsonElement x : items) {
            try {
                double d = x.getAsDouble();
                if (d != Math.rint(d)) throw new NumberFormatException();
                out.add((int) d);
            } catch (RuntimeException ex) {
                throw type(name, "an array of integers");
            }
        }
        return out;
    }

    JsonObject obj(String name) {
        if (!present(name)) return null;
        JsonElement e = o.get(name);
        if (!e.isJsonObject()) throw type(name, "an object");
        return e.getAsJsonObject();
    }

    JsonArray array(String name) {
        if (!present(name)) return null;
        JsonElement e = o.get(name);
        if (!e.isJsonArray()) throw type(name, "an array");
        return e.getAsJsonArray();
    }

    /** An object of string values (null values kept as null). */
    Map<String, String> stringMap(String name) {
        JsonObject m = obj(name);
        Map<String, String> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonNull()) out.put(e.getKey(), null);
            else if (v.isJsonPrimitive()) out.put(e.getKey(), v.getAsString());
            else throw type(name + "." + e.getKey(), "a string or null");
        }
        return out;
    }

    private static ToolException type(String name, String what) {
        return new ToolException("'" + name + "' must be " + what);
    }
}
