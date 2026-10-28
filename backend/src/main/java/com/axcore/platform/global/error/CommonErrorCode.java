package com.axcore.platform.global.error;

import org.springframework.http.HttpStatus;

/** 모든 도메인이 함께 쓰는 오류 코드. 메시지는 API 명세서의 공통 오류 예시를 따른다. */
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 필드를 확인해 주세요."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증 정보가 없거나 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 실행할 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 항목을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    CONFLICT(HttpStatus.CONFLICT, "중복·상태·버전 충돌"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "요청한 버전과 현재 버전이 다릅니다."),
    IN_USE(HttpStatus.CONFLICT, "다른 데이터가 참조하고 있어 처리할 수 없습니다."),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "업무 규칙에 맞지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류");

    private final HttpStatus status;
    private final String message;

    CommonErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** 프레임워크가 정한 HTTP 상태를 공통 코드로 바꾼다. 표에 없는 4xx는 400, 5xx는 500으로 맞춘다. */
    public static CommonErrorCode fromStatus(int status) {
        return switch (status) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            default -> status >= 400 && status < 500 ? VALIDATION_FAILED : INTERNAL_ERROR;
        };
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
