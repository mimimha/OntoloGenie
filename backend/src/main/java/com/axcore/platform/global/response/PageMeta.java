package com.axcore.platform.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.springframework.data.domain.Page;

/**
 * 목록 응답의 페이지 정보. scope는 조회 범위를 알려 줘야 하는 목록만 채운다(예: 096 실행·감사 이력의 ALL·MINE). cdcLastAppliedAt은 원천 수집
 * 이력(074)만 채운다(CDC로 마지막 반영한 원천 트랜잭션의 원천 커밋 시각). 비어 있으면 응답에 나오지 않는다.
 */
public record PageMeta(
        int page,
        int size,
        long totalElements,
        boolean hasNext,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scope,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant cdcLastAppliedAt) {

    public PageMeta(int page, int size, long totalElements, boolean hasNext) {
        this(page, size, totalElements, hasNext, null, null);
    }

    public static PageMeta from(Page<?> page) {
        return from(page, null);
    }

    public static PageMeta from(Page<?> page, String scope) {
        return new PageMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext(),
                scope,
                null);
    }

    /** API 074. 이 원천의 CDC 마지막 반영 시각을 함께 알려 준다. */
    public PageMeta withCdcLastAppliedAt(Instant cdcLastAppliedAt) {
        return new PageMeta(page, size, totalElements, hasNext, scope, cdcLastAppliedAt);
    }
}
