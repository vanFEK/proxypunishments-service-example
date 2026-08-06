package net.vanfek.proxybans.service.api.v1;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Bounds are enforced here rather than trusted from the caller, wherever a Paging is constructed.
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
