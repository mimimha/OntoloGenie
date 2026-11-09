package com.axcore.platform.source.domain;

import java.util.Objects;

/**
 * 원천 행을 객체로 만들 때 생긴 데이터 불량 하나(V12 {@code source_load_failure}, 결정 문서 9.2). 원천 값은 담지 않는다. 키와 실패한 컬럼만
 * 남긴다.
 *
 * @param columnName 실패한 원천 컬럼. 키 실패면 그 키 컬럼
 * @param propertyDefinitionId 속성 값 실패면 그 속성. 키 실패면 null
 * @param reason 실패 이유
 */
public record LoadFailure(String columnName, Long propertyDefinitionId, Reason reason) {

    public LoadFailure {
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(reason, "reason");
        if (reason.isKey() != (propertyDefinitionId == null)) {
            throw new IllegalArgumentException("키 실패에는 속성이 없고, 값 실패에는 속성이 있어야 합니다.");
        }
    }

    public static LoadFailure key(String columnName, Reason reason) {
        return new LoadFailure(columnName, null, reason);
    }

    public static LoadFailure value(String columnName, long propertyDefinitionId, Reason reason) {
        return new LoadFailure(columnName, propertyDefinitionId, reason);
    }

    /** {@code source_load_failure.reason}. */
    public enum Reason {
        /** 키 값이 없다. 객체를 만들지 않는다. */
        KEY_MISSING,
        /** 키 컬럼의 원천 형식을 해석하지 못한다. 객체를 만들지 않는다. */
        KEY_UNSUPPORTED,
        /** 값을 속성 자료형으로 바꾸지 못했다. 그 속성만 뺀다. */
        VALUE_CONVERSION,
        /** 지원하지 않는 원천 형식(bytea·배열·interval 등)이다. 그 속성만 뺀다. */
        UNSUPPORTED_TYPE;

        public boolean isKey() {
            return this == KEY_MISSING || this == KEY_UNSUPPORTED;
        }
    }
}
