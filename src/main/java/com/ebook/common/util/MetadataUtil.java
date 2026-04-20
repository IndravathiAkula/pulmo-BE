package com.ebook.common.util;

/**
 * Builds compact JSON metadata strings for audit logs.
 * Escapes quotes and backslashes to prevent malformed JSON.
 */
public final class MetadataUtil {

    private MetadataUtil() {
    }

    public static String build(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have an even number of elements");
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(keyValues[i]))
              .append("\":\"").append(escape(keyValues[i + 1])).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
