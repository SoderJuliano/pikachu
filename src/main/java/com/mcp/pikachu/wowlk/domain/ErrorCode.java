package com.mcp.pikachu.wowlk.domain;

import org.springframework.http.HttpStatus;

/** Codigos estaveis do contrato do launcher (docs/API_CONTRACT.md). */
public enum ErrorCode {
    VALIDATION(HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN),
    ACCOUNT_BANNED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    /** Novo (o contrato permite codigos novos): usuario ou e-mail ja usados no cadastro. */
    ACCOUNT_EXISTS(HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    LAUNCHER_OUTDATED(HttpStatus.UPGRADE_REQUIRED),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR),
    MAINTENANCE(HttpStatus.SERVICE_UNAVAILABLE);

    public final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }
}
