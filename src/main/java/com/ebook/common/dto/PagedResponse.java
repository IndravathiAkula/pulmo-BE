package com.ebook.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.function.Function;

/**
 * Standard pagination envelope returned when a list endpoint receives pagination query params.
 * <p>Shape: {@code { content, page, size, totalElements, totalPages }}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static <E, R> PagedResponse<R> of(List<E> content, long totalElements, PageRequest request,
                                             Function<E, R> mapper) {
        int size = request.getSize();
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return PagedResponse.<R>builder()
                .content(content.stream().map(mapper).toList())
                .page(request.getPage())
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }
}
