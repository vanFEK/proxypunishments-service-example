package net.vanfek.proxybans.service.api.v1;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validated paging window.
 *
 * <p>Bounds are enforced here rather than trusted from the caller: an unbounded {@code limit} turns
 * one request into a full-table read, and a negative {@code offset} reaches the database as a syntax
 * error rather than as an empty page.
 *
 * <p>Written as an explicit check instead of bean-validation annotations so the rule travels with
 * the value and holds wherever it is constructed, not only when Spring proxies the controller.
 */
record Paging(int limit, int offset) {

    static final int MAX_LIMIT = 100;
    static final int DEFAULT_LIMIT = 50;

    Paging {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT + ", got " + limit
            );
        }

        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must not be negative, got " + offset);
        }
    }

}
