package com.axcore.platform.mapping.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * API 040 객체 매핑 시험 변환 요청. 저장 전 매핑 초안과 읽을 행 수.
 *
 * @param rowLimit 읽을 최대 행 수(1~20). 비우면 20
 */
public record MappingPreviewRequest(
        @NotNull(message = "mapping은 필수입니다.") @Valid Draft mapping,
        @Min(value = 1, message = "rowLimit은 1 이상이어야 합니다.")
                @Max(value = 20, message = "rowLimit은 20 이하여야 합니다.")
                Integer rowLimit) {

    public static final int DEFAULT_ROW_LIMIT = 20;

    public int rowLimitOrDefault() {
        return rowLimit == null ? DEFAULT_ROW_LIMIT : rowLimit;
    }

    /**
     * 저장 전 객체 매핑 초안.
     *
     * @param rowFilter 행 조건. 비우면 모든 행
     * @param propertyMappings 속성 매핑 초안. 비우면 빈 목록
     */
    public record Draft(
            @NotNull(message = "objectTypeId는 필수입니다.")
                    @Min(value = 1, message = "objectTypeId는 1 이상이어야 합니다.")
                    Long objectTypeId,
            @NotEmpty(message = "keyColumns는 1개 이상이어야 합니다.")
                    List<@NotBlank(message = "keyColumns에 빈 이름이 있습니다.") String> keyColumns,
            Map<String, Object> rowFilter,
            List<@NotNull(message = "propertyMappings에 빈 항목이 있습니다.") @Valid PropertyDraft>
                    propertyMappings) {

        public Map<String, Object> rowFilterOrEmpty() {
            return rowFilter == null ? Map.of() : rowFilter;
        }

        public List<PropertyDraft> propertyMappingsOrEmpty() {
            return propertyMappings == null ? List.of() : propertyMappings;
        }
    }

    /**
     * 속성 매핑 초안 하나. 변환 단계는 038·039와 같은 {@code {"op", "arg"}} 형식이다.
     *
     * @param sourceColumnId 표 컬럼. sourcePath와 둘 중 하나만
     * @param sourcePath 문서 필드 경로. 지금은 받지 않는다(422, 전체 읽기도 아직 못 읽는다)
     */
    public record PropertyDraft(
            @NotNull(message = "propertyDefinitionId는 필수입니다.")
                    @Min(value = 1, message = "propertyDefinitionId는 1 이상이어야 합니다.")
                    Long propertyDefinitionId,
            Long sourceColumnId,
            String sourcePath,
            List<@NotNull(message = "transformSteps에 빈 단계가 있습니다.") TransformStepPayload>
                    transformSteps) {

        @AssertTrue(message = "sourceColumnId와 sourcePath 중 정확히 하나만 보내 주세요.")
        public boolean isSourceExactlyOne() {
            return (sourceColumnId == null) != (sourcePath == null);
        }
    }
}
