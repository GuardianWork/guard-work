package com.guardwork.backend.common;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> of(List<T> content, int pageNumber, int pageSize, long totalElements) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;
        boolean first = pageNumber == 0;
        boolean last = totalPages == 0 || pageNumber >= totalPages - 1;
        return new PageResponse<>(content, pageNumber, pageSize, totalElements, totalPages, first, last);
    }
}
