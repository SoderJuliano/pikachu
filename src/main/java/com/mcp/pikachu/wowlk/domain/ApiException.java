package com.mcp.pikachu.wowlk.domain;

/** Erro de negocio que vira o envelope {@code { "error": { "code", "message" } }}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
