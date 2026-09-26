package ma.ewallet.common;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paginated results.
 *
 * <p>Spring Data's {@code Page} object has a large, implementation-specific JSON
 * shape that Spring itself warns against exposing. This small record is our own,
 * stable API contract for any paginated list: the items plus what a client
 * needs to display "page 2 of 7".</p>
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
