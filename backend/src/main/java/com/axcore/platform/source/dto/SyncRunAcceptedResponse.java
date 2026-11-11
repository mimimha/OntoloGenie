package com.axcore.platform.source.dto;

import com.axcore.platform.source.repository.SyncRunRequestRepository.ResourceRun;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * API 073 접수 결과. syncRunId·status는 이번 요청으로 새로 만든 실행의 값이며, 모든 테이블이 기존 실행에 합류했으면 null이다. 테이블별 실제 담당
 * 실행은 resources[].syncRunId다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SyncRunAcceptedResponse(
        Long syncRunId,
        long dataSourceId,
        String triggerKind,
        String status,
        long requestedBy,
        Instant requestedAt,
        boolean joinedExisting,
        List<Resource> resources) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Resource(
            long syncRunId,
            boolean joined,
            long dataSourceId,
            long sourceResourceId,
            String mode,
            String status,
            Long fromLsn,
            Long toLsn,
            long rowsRead,
            long rowsUpserted,
            long rowsDeleted,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage) {

        public static Resource of(ResourceRun run, boolean joined) {
            return new Resource(
                    run.syncRunId(),
                    joined,
                    run.dataSourceId(),
                    run.sourceResourceId(),
                    run.mode(),
                    run.status(),
                    run.fromLsn(),
                    run.toLsn(),
                    run.rowsRead(),
                    run.rowsUpserted(),
                    run.rowsDeleted(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.errorMessage());
        }
    }
}
