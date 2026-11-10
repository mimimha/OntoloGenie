package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** API 046 쪽 조건. 명세에 조회 파라미터가 없지만 응답에 meta가 있어 다른 목록 API처럼 page·size만 받는다. */
public record RuleRevisionSearchRequest(
        @Min(value = 0, message = "page는 0 이상이어야 합니다.") Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(value = 100, message = "size는 100 이하여야 합니다.")
                Integer size) {}
