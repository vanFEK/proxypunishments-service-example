package net.vanfek.proxypunishments.service.api.v1;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<Map<String, Object>> handleValidation(WebExchangeBindException exception) {
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, "Request validation failed");

        body.put("errors", exception.getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList());

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, "Request validation failed");

        body.put("errors", exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "message", violation.getMessage()
                ))
                .toList());

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<Map<String, Object>> handleBadInput(ServerWebInputException exception) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, String.valueOf(exception.getReason())));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> handleStatusException(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());

        return ResponseEntity.status(status).body(body(status, String.valueOf(exception.getReason())));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException exception) {
        LOGGER.warn("Conflicting write rejected by the database", exception);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "Conflicting concurrent modification, retry the request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled exception while serving a request", exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error, see the service log"));
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", this.clock.instant());
        body.put("status", status.value());
        body.put("message", message);

        return body;
    }

}
