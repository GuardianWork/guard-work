package com.guardwork.backend.common.utils;

import com.guardwork.backend.common.PageResponse;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class PaginationUtils {

    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 20;

    private PaginationUtils() {
        // Utility class
    }

    public static Pageable validateAndBound(Pageable pageable) {
        return validateAndBound(pageable, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
    }

    public static Pageable validateAndBound(Pageable pageable, int defaultSize, int maxSize) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, defaultSize);
        }

        int size = pageable.getPageSize();
        if (size <= 0) {
            size = defaultSize;
        } else if (size > maxSize) {
            size = maxSize;
        }

        int page = pageable.getPageNumber();

        if (page < 0) {
            page = 0;
        }

        if (page == pageable.getPageNumber() && size == pageable.getPageSize()) {
            return pageable;
        }

        return PageRequest.of(page, size, pageable.getSort());
    }

    public static <T, R> PageResponse<R> toPageResponse(Page<T> page, Function<T, R> mapper) {
        if (page == null) {
            return null;
        }
        List<R> content = page.getContent().stream()
                .map(mapper)
                .toList();
        return PageResponse.of(page, content);
    }
}
