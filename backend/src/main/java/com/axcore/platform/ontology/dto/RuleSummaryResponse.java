package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 연결 규칙 목록의 한 줄(Jira 186 후속). 규칙 코드마다 최신 개정본의 요약이다. 정의·규칙 이력 화면이 규칙을 고를 때 쓰고, 개정 내역은 046으로 본다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuleSummaryResponse(
        String code,
        int revision,
        long linkTypeId,
        String linkTypeName,
        LinkMappingStatus status,
        String description) {

    public static RuleSummaryResponse of(LinkMapping latest, String linkTypeName) {
        return new RuleSummaryResponse(
                latest.getCode(),
                latest.getRevision(),
                latest.getLinkTypeId(),
                linkTypeName,
                latest.getStatus(),
                latest.getDescription());
    }
}
