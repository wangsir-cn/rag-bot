package com.example.ragbot.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class JsonbUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private JsonbUtils() {}

    public static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map == null ? Map.of() : map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize jsonb metadata", e);
        }
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse jsonb metadata: " + json, e);
        }
    }
}
