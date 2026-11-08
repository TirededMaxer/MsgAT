// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bounded streaming parsing before materializing attacker-controlled JSON. */
public final class BoundedJson {
    private BoundedJson() {}

    public static JsonObject parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 192 * 1024) throw invalid();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                JsonElement value = read(reader, 0);
                if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
                return value.getAsJsonObject();
            }
        } catch (IOException | IllegalStateException error) { throw invalid(); }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 12) throw invalid();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    if (object.size() >= 32) throw invalid();
                    String key = reader.nextName();
                    if (key.length() > 64 || object.has(key)) throw invalid();
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (array.size() >= 1024) throw invalid();
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> {
                String value = reader.nextString();
                if (value.length() > 128 * 1024) throw invalid();
                yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String value = reader.nextString();
                if (!value.matches("-?(0|[1-9][0-9]{0,18})")) throw invalid();
                try { yield new JsonPrimitive(Long.parseLong(value)); }
                catch (NumberFormatException error) { throw invalid(); }
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid();
        };
    }

    public static void fields(JsonObject object, String... names) {
        if (!object.keySet().equals(Set.of(names))) throw invalid();
    }

    public static String string(JsonObject object, String name, int maximum) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        String result = value.getAsString();
        if (result.isEmpty() || result.length() > maximum) throw invalid();
        return result;
    }

    public static long integer(JsonObject object, String name, long minimum, long maximum) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
        long result = value.getAsLong();
        if (result < minimum || result > maximum) throw invalid();
        return result;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Malformed or oversized JSON request"); }
}
