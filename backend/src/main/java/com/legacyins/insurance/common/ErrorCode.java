package com.legacyins.insurance.common;

import org.springframework.http.HttpStatus;

/** Error taxonomy carried over from the legacy SOAP response envelope. */
public enum ErrorCode {
    AUTH_FAILED(HttpStatus.UNAUTHORIZED),
    NOT_AUTHORIZED(HttpStatus.FORBIDDEN),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED),
    VALIDATION(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE(HttpStatus.CONFLICT),
    BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY),
    SYSTEM(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
