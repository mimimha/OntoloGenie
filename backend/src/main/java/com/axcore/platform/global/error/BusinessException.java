package com.axcore.platform.global.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * 업무 규칙 위반을 알리는 예외. 전역 처리기가 오류 코드의 상태로 응답한다. 메시지를 따로 주지 않으면 오류 코드의 기본 메시지를 쓴다. API 명세서가 그 API에 맞는
 * 문구를 정해 두었으면 그 문구를 넘긴다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message(), Map.of());
    }

    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.message(), details);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** Service가 찾은 입력 오류(400). details는 Bean Validation 오류와 같은 {field, reason, errors[]} 형식이다. */
    public static BusinessException invalidField(String field, String reason) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("reason", reason);
        Map<String, Object> details = new LinkedHashMap<>(error);
        details.put("errors", List.of(error));
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, details);
    }
}
