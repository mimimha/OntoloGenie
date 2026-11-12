package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.LinkMappingStatus;
import jakarta.validation.constraints.Min;

/** 연결 규칙 목록 조건. 비어 있는 조건으로는 거르지 않는다. status는 규칙 코드의 최신 개정본 상태다. */
public record RuleListRequest(
        @Min(value = 1, message = "linkTypeId는 1 이상이어야 합니다.") Long linkTypeId,
        LinkMappingStatus status) {}
