package com.axcore.platform.source.dto;

import com.axcore.platform.source.repository.SyncRunQueryRepository.Resource;
import com.axcore.platform.source.repository.SyncRunQueryRepository.Run;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * API 074 목록 항목과 075 응답. 구간·방식·건수는 resources[]에 있다. requestedBy는 SCHEDULED·CDC·RECOVERY이면 null이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SyncRunResponse(
        long id,
        long syncRunId,
        long dataSourceId,
        String triggerKind,
        String status,
        Long requestedBy,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        List<ResourceResponse> resources) {

    public static SyncRunResponse of(Run run, List<Resource> resources) {
        return new SyncRunResponse(
                run.id(),
                run.id(),
                run.dataSourceId(),
                run.triggerKind(),
                run.status(),
                run.requestedBy(),
                run.requestedAt(),
                run.startedAt(),
                run.finishedAt(),
                run.errorMessage(),
                resources.stream().map(ResourceResponse::of).toList());
    }

    /** 테이블 하나의 수집 기록. INCREMENTAL은 CDC 소비 묶음이고, 그 밖의 실행은 모두 FULL이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResourceResponse(
            long syncRunId,
            long dataSourceId,
            long sourceResourceId,
            String syncStrategy,
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

        static ResourceResponse of(Resource resource) {
            return new ResourceResponse(
                    resource.syncRunId(),
                    resource.dataSourceId(),
                    resource.sourceResourceId(),
                    resource.syncStrategy(),
                    resource.mode(),
                    resource.status(),
                    resource.fromLsn(),
                    resource.toLsn(),
                    resource.rowsRead(),
                    resource.rowsUpserted(),
                    resource.rowsDeleted(),
                    resource.startedAt(),
                    resource.finishedAt(),
                    resource.errorMessage());
        }
    }
}
