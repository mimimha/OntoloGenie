package com.axcore.platform.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 078의 evidence 항목. 관계 종류와 열람 권한에 따라 공개 가능한 근거만 담는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObjectLinkEvidenceResponse(
        Map<String, Object> sourceKey,
        Object rawValue,
        Object transformedValue,
        String quote,
        String event,
        String timeExpression,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
                @Schema(
                        description =
                                "조건 번호 순서의 규칙 비교 근거. 양쪽 변환 전 값을 확인할 수 있는 조건만 포함하며, 근거 열람 권한이 없으면 생략한다.")
                List<Comparison> comparisons,
        @Schema(description = "저장된 근거 내용이 열람 권한 때문에 가려진 경우에만 true") boolean evidenceRestricted) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Comparison(
            @Schema(description = "연결 규칙의 비교 조건 순번") int sequenceNo,
            @Schema(description = "비교 조건의 출발 객체 속성 코드") String sourceProperty,
            @Schema(description = "출발 속성에 현재 매핑된 원천 컬럼 이름") String sourceColumn,
            @Schema(description = "연결 규칙을 적용하기 전의 출발 객체 속성값") String sourceValue,
            @Schema(description = "비교 조건의 도착 객체 속성 코드") String targetProperty,
            @Schema(description = "도착 속성에 현재 매핑된 원천 컬럼 이름") String targetColumn,
            @Schema(description = "연결 규칙을 적용하기 전의 도착 객체 속성값. 도착 변환이 없는 구형 근거는 매칭 값을 사용한다.")
                    String targetValue,
            @Schema(description = "연결 규칙을 적용한 뒤 양쪽에서 일치한 값") String matchedValue) {}
}
