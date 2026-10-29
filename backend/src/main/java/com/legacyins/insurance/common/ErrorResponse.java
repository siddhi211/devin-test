package com.legacyins.insurance.common;

import java.util.List;

public record ErrorResponse(String status, ErrorCode code, String message, List<String> details) {
    public static ErrorResponse of(ErrorCode code, String message, List<String> details) {
        return new ErrorResponse("ERROR", code, message, details == null || details.isEmpty() ? null : details);
    }
}
