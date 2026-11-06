package com.axcore.platform.analysis.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalysisRunResponse(
        long id,
        String runKind,
        String status,
        List<Target> targets,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> resultSummary,
        String errorMessage,
        long requestedBy,
        Map<String, Object> analysisRange,
        Long basedOnSyncRunId,
        boolean joined) {
    /** 조회한 실행에 합류 여부를 붙인다. joined=true면 같은 대상의 활성 실행에 합류한 응답이다. */
    public static AnalysisRunResponse of(AnalysisResponse run, boolean joined) {
        return new AnalysisRunResponse(
                run.id(),
                run.runKind(),
                run.status(),
                run.targets(),
                run.requestedAt(),
                run.startedAt(),
                run.finishedAt(),
                run.resultSummary(),
                run.errorMessage(),
                run.requestedBy(),
                run.analysisRange(),
                run.basedOnSyncRunId(),
                joined);
    }

    public record Target(long sourceResourceId) {}
}
