package com.mcp.pikachu.donation.web;

import com.mcp.pikachu.wowlk.domain.ApiException;
import com.mcp.pikachu.wowlk.domain.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Mesmo envelope de erro do launcher ({@code { "error": { "code", "message" } }}), so para as rotas de doacao. */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DonationController.class)
public class DonationExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.code().status);
        if (e.retryAfterSeconds() != null) b.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        return b.body(envelope(e.code(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> badBody(Exception e) {
        return ResponseEntity.badRequest().body(envelope(ErrorCode.VALIDATION, "Malformed request."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(Exception e) {
        return ResponseEntity.status(405).body(envelope(ErrorCode.NOT_FOUND, "Method not allowed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("[donation] erro inesperado: {}", e.toString());
        return ResponseEntity.status(500).body(envelope(ErrorCode.INTERNAL, "The server had a problem. Try again later."));
    }

    private static Map<String, Object> envelope(ErrorCode code, String message) {
        return Map.of("error", Map.of("code", code.name(), "message", message));
    }
}
