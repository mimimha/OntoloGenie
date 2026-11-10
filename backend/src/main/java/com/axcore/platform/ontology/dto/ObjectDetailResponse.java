package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 077 객체 상세. properties와 propertyNames에는 열람 가능한 속성만 담는다. */
public record ObjectDetailResponse(
        long id,
        long objectTypeId,
        String objectTypeName,
        String displayName,
        Map<String, Object> properties,
        Map<String, String> propertyNames,
        Status status,
        Long sourceMappingId,
        String sourceSystemName,
        String sourceResourceKey,
        Map<String, Object> sourceKey,
        Instant firstCollectedAt,
        Instant lastSyncedAt,
        Instant dataAsOf) {

    /** Explorer의 기존 응답 필드는 유지하되 숨긴 속성의 존재나 코드는 노출하지 않는다. */
    @JsonProperty("restrictedPropertyCodes")
    public List<String> restrictedPropertyCodes() {
        return List.of();
    }
}
