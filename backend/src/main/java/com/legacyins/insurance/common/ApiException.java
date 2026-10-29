package com.legacyins.insurance.common;

import java.util.List;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final List<String> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<String> getDetails() {
        return details;
    }

    public static ApiException notFound(String entity, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND, entity + " " + id + " not found");
    }

    public static ApiException rule(String message) {
        return new ApiException(ErrorCode.BUSINESS_RULE, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION, message);
    }

    public static ApiException duplicate(String message) {
        return new ApiException(ErrorCode.DUPLICATE, message);
    }
}
