package com.ebook.common.storage;

/**
 * Normalizes any user-supplied "file URL" down to a bare storage key
 * ({@code covers/abc.jpg}, {@code previews/x.png}, etc.).
 *
 * <p>The backend no longer constructs absolute URLs for stored files — the
 * frontend owns origin assembly. This helper exists so we can defensively
 * accept legacy absolute URLs from old DB rows and FE callers, including
 * shapes with doubled context paths like {@code /ebook/ebook/files/covers/x.jpg}.
 *
 * <p>Strategy: find the last occurrence of a known kind prefix and slice from
 * there. Returns {@code null} if the input contains none of the known prefixes.
 */
public final class FileKeyUtil {

    private static final String[] PREFIXES = {"covers/", "previews/", "books/", "profiles/"};

    private FileKeyUtil() {}

    public static String toKey(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;

        int bestIdx = -1;
        for (String p : PREFIXES) {
            int idx = trimmed.lastIndexOf(p);
            if (idx > bestIdx) bestIdx = idx;
        }
        if (bestIdx < 0) return null;
        return trimmed.substring(bestIdx);
    }
}
