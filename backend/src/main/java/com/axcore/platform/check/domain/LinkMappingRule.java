package com.axcore.platform.check.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 승인된 연결 규칙 개정본 하나를 검사할 준비(S15P31C106-190). 판정 엔진 입력과, 실행기가 행을 읽을 객체 유형·원천 자원·행 조건을 함께 담는다.
 *
 * @param input 판정 엔진 입력. 대상은 이 규칙 개정본이고 데이터 기준은 이 규칙이 읽는 원천 자원만 남긴 것이다
 * @param sourceType 관계의 출발 객체 유형
 * @param targetType 관계의 도착 객체 유형
 * @param sourceResourceIds 출발 객체 유형에 매핑된(활성) 원천 자원
 * @param targetResourceIds 도착 객체 유형에 매핑된(활성) 원천 자원
 * @param sourceFilter 출발 행 조건({@code link_mapping.source_filter}). 비어 있으면 모든 행
 * @param targetFilter 도착 행 조건({@code link_mapping.target_filter}). 비어 있으면 모든 행
 */
public record LinkMappingRule(
        CheckRuleInput input,
        ObjectTypeRef sourceType,
        ObjectTypeRef targetType,
        List<Long> sourceResourceIds,
        List<Long> targetResourceIds,
        Map<String, Object> sourceFilter,
        Map<String, Object> targetFilter) {

    public LinkMappingRule {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");
        if (input.target().linkMappingId() == null) {
            throw new IllegalArgumentException("승인된 연결 규칙의 입력이어야 합니다.");
        }
        sourceResourceIds = List.copyOf(sourceResourceIds);
        targetResourceIds = List.copyOf(targetResourceIds);
        // 행 조건 값에 null이 있을 수 있어 Map.copyOf 대신 감싼다.
        sourceFilter = Collections.unmodifiableMap(new LinkedHashMap<>(sourceFilter));
        targetFilter = Collections.unmodifiableMap(new LinkedHashMap<>(targetFilter));
    }

    public long linkMappingId() {
        return input.target().linkMappingId();
    }

    /** 객체 유형. 조건의 속성 이름은 {@code code.속성코드}다. */
    public record ObjectTypeRef(long id, String code) {

        public ObjectTypeRef {
            Objects.requireNonNull(code, "code");
        }
    }
}
