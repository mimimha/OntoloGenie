package com.axcore.platform.issue.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** API 070 문제 재검증 기록과 이 기록을 남긴 검사 실행의 상태. */
public record IssueVerificationResponse(
        long issueId,
        long checkRunId,
        String triggerKind,
        String status,
        String outcome,
        JsonNode detail,
        Instant checkedAt) {}
