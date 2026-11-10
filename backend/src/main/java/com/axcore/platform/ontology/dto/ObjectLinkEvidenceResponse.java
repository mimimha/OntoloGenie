package com.axcore.platform.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** 078의 evidence 항목. 관계 종류와 열람 권한에 따라 공개 가능한 근거만 담는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObjectLinkEvidenceResponse(
        Map<String, Object> sourceKey,
        Object rawValue,
        Object transformedValue,
        String quote,
        String event,
        String timeExpression) {}
