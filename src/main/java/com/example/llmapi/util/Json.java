package com.example.llmapi.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over Gson with the project's specific defaults. Single shared
 * {@link Gson} instance keeps allocations down.
 */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json() {
    }

    public static JsonObject parseObject(String body) {
        if (body == null || body.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed == null || !parsed.isJsonObject() ? new JsonObject() : parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    public static String stringify(Object o) {
        return GSON.toJson(o);
    }

    public static String getString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        return obj.get(key).getAsString();
    }

    public static int getInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static Map<String, String> getStringMap(JsonObject obj) {
        Map<String, String> map = new LinkedHashMap<>();
        if (obj == null) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                map.put(entry.getKey(), "");
            } else if (value.isJsonPrimitive()) {
                map.put(entry.getKey(), value.getAsString());
            } else {
                map.put(entry.getKey(), value.toString());
            }
        }
        return map;
    }

    public static List<Integer> getIntList(JsonObject obj, String key) {
        List<Integer> list = new ArrayList<>();
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return list;
        }
        for (JsonElement el : obj.getAsJsonArray()) {
            try {
                list.add(el.getAsInt());
            } catch (Exception ignored) {
            }
        }
        return list;
    }
}
