package com.axcore.platform.issue.dto;

import jakarta.validation.constraints.Min;

/** API 070 재검증 이력 페이지 조건. */
public record IssueVerificationSearchRequest(
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}
