package com.intellitwin.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Uniform JSON errors; never leaks stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> body(HttpStatus s, String msg, Object details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.value());
        m.put("error", msg);
        if (details != null) m.put("details", details);
        return ResponseEntity.status(s).body(m);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason() == null ? "error" : e.getReason(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        var details = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        return body(HttpStatus.BAD_REQUEST, "validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "malformed JSON body", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        if (e instanceof ErrorResponse er) {   // Spring MVC's own 400/404/405/415 etc. keep their status
            HttpStatus st = HttpStatus.valueOf(er.getStatusCode().value());
            return body(st, st.getReasonPhrase().toLowerCase(), null);
        }
        log.error("unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error", null);
    }
}
