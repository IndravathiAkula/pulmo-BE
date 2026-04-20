package com.ebook.common.dto;

import com.ebook.common.exception.ValidationException;

import java.util.Set;

/**
 * Optional pagination + sort request derived from query params.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code page} — zero-based; defaults to {@code 0}.</li>
 *   <li>{@code size} — defaults to {@link #DEFAULT_SIZE}; capped at {@link #MAX_SIZE}.</li>
 *   <li>{@code sort} — {@code field,dir} (e.g. {@code createdAt,desc}); validated against an allow-list per call site.</li>
 * </ul>
 *
 * <p>{@link #parse(Integer, Integer, String, Set, String)} returns {@code null} when {@code page} and {@code size}
 * are both null — callers treat that as "no pagination requested" (legacy unbounded behavior).
 */
public final class PageRequest {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final int page;
    private final int size;
    private final String sortField;
    private final SortDirection sortDirection;

    public enum SortDirection { ASC, DESC }

    private PageRequest(int page, int size, String sortField, SortDirection sortDirection) {
        this.page = page;
        this.size = size;
        this.sortField = sortField;
        this.sortDirection = sortDirection;
    }

    /**
     * @return a {@code PageRequest} when pagination was requested by the client; {@code null} otherwise (legacy mode).
     */
    public static PageRequest parse(Integer page, Integer size, String sort,
                                    Set<String> allowedSortFields, String defaultSortField) {
        if (page == null && size == null && (sort == null || sort.isBlank())) {
            return null;
        }

        int normalizedPage = page == null ? 0 : page;
        int normalizedSize = size == null ? DEFAULT_SIZE : size;

        if (normalizedPage < 0) {
            throw new ValidationException("page must be >= 0");
        }
        if (normalizedSize <= 0 || normalizedSize > MAX_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_SIZE);
        }

        String sortField = defaultSortField;
        SortDirection direction = SortDirection.DESC;

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String requestedField = parts[0].trim();
            if (!allowedSortFields.contains(requestedField)) {
                throw new ValidationException(
                        "sort field '" + requestedField + "' is not allowed. Allowed: " + allowedSortFields);
            }
            sortField = requestedField;

            if (parts.length > 1) {
                String dir = parts[1].trim().toUpperCase();
                if (!"ASC".equals(dir) && !"DESC".equals(dir)) {
                    throw new ValidationException("sort direction must be 'asc' or 'desc'");
                }
                direction = SortDirection.valueOf(dir);
            }
        }

        return new PageRequest(normalizedPage, normalizedSize, sortField, direction);
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public String getSortField() {
        return sortField;
    }

    public SortDirection getSortDirection() {
        return sortDirection;
    }

    /** JPQL-safe ORDER BY fragment (sortField is allow-listed; direction is enum). */
    public String orderByClause(String entityAlias) {
        return "ORDER BY " + entityAlias + "." + sortField + " " + sortDirection.name();
    }
}
