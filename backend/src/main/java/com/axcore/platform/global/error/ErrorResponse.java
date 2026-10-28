package com.axcore.platform.global.error;

import java.util.Map;

/** 오류 응답 공통 형식 (개발 가이드 8.3). */
public record ErrorResponse(Body error) {

    public static ErrorResponse of(
            ErrorCode errorCode, Map<String, Object> details, String requestId) {
        return of(errorCode, errorCode.message(), details, requestId);
    }

    public static ErrorResponse of(
            ErrorCode errorCode, String message, Map<String, Object> details, String requestId) {
        return new ErrorResponse(new Body(errorCode.code(), message, details, requestId));
    }

    public record Body(
            String code, String message, Map<String, Object> details, String requestId) {}
}
