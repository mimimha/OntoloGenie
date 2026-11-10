package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 078 활성 관계의 조회 방향과 페이지 조건. direction 생략 시 BOTH로 조회한다. */
public record ObjectLinkSearchRequest(
        @Min(1) Long linkTypeId,
        Direction direction,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {

    public enum Direction {
        OUT,
        IN,
        BOTH
    }
}
