package com.axcore.platform.issue.dto;

import jakarta.validation.constraints.Min;

/** API 069 페이지 조건. 명세에 조회 파라미터가 없어 다른 목록 API와 같은 page·size만 받는다. */
public record IssueHistorySearchRequest(
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.") Integer size) {}
