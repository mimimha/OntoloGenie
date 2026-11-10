package com.axcore.platform.issue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** API 069 상태 이력 한 행. 검사가 처음 등록한 행은 beforeStatus·changedBy·note가 null이다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IssueHistoryResponse(
        long id,
        long issueId,
        String beforeStatus,
        String afterStatus,
        Instant changedAt,
        Long changedBy,
        Long checkRunId,
        String note) {}
