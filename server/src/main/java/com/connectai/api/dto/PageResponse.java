package com.connectai.api.dto;

import java.util.List;

/** Simple page envelope used by list endpoints. page is zero-based. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
