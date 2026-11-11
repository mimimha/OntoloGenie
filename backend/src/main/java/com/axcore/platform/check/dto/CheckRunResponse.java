package com.axcore.platform.check.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API 059·060·061의 검사 실행 하나. nullable 필드도 null로 내보낸다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CheckRunResponse(
        long id,
        String triggerKind,
        String status,
        Map<String, Object> scope,
        Long comparedToRunId,
        Instant requestedAt,
        Long requestedBy,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        Long syncRunId,
        DataBasis dataBasis) {

    /** 검사 입력 기준. 원천 자원별 마지막 성공 수집 실행이다. */
    public record DataBasis(List<Resource> resources) {}

    /**
     * @param lastSyncRunId 이 자원의 마지막 성공 수집 실행. 성공한 재검증은 실제 검사 시점 기준이며, 수집 이력이 없으면 null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Resource(long sourceResourceId, Long lastSyncRunId) {}
}
