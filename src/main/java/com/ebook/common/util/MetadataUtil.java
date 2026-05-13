package com.ebook.common.util;

import com.ebook.common.exception.InternalServerException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds compact JSON metadata strings for audit logs.
 *
 * <p>Uses Jackson so that null values, control characters (newlines inside user-agent
 * strings, etc.), and non-ASCII input are encoded correctly. The earlier hand-rolled
 * escaping threw NPE on null values and produced invalid JSON when a value contained
 * a newline or tab (P2 #28).
 */
public final class MetadataUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataUtil() {
    }

    public static String build(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have an even number of elements");
        }
        // LinkedHashMap preserves the call-site order so audit logs stay stable/grep-friendly.
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = keyValues[i];
            if (key == null) {
                throw new IllegalArgumentException("metadata key at index " + i + " is null");
            }
            // Null values survive as JSON null — the audit consumer can tell "missing" from empty.
            map.put(key, keyValues[i + 1]);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new InternalServerException("Failed to serialize audit metadata", e);
        }
    }
}
