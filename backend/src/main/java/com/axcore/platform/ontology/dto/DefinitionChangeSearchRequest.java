package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * API 044 조회 조건. 비어 있는 조건으로는 거르지 않는다. 기간은 변경을 남긴 실행의 requestedAt 기준이며 from은 포함, to는 포함하지 않는다.
 * rowId는 정의 테이블의 PK(id)이며 tableName과 함께 보낸다.
 */
public record DefinitionChangeSearchRequest(
        @Size(max = 63, message = "tableName은 63자 이하여야 합니다.") String tableName,
        @Min(value = 1, message = "rowId는 1 이상이어야 합니다.") Long rowId,
        Instant from,
        Instant to,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(value = 100, message = "size는 100 이하여야 합니다.")
                Integer size) {}
