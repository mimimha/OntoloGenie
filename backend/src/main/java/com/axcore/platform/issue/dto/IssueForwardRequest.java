package com.axcore.platform.issue.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** API 068 요청. 문제 행을 바꾸므로 조회한 버전을 함께 보낸다. */
public record IssueForwardRequest(
        @NotNull @Min(value = 0, message = "rowVersion은 0 이상이어야 합니다.") Long rowVersion) {}
