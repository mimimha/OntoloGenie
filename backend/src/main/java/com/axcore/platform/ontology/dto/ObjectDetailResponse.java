package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 077 객체 상세. 민감 값은 076과 같이 null로 가리고 코드와 표시 이름은 유지한다. */
public record ObjectDetailResponse(
        long id,
        long objectTypeId,
        String objectTypeName,
        String displayName,
        Map<String, Object> properties,
        Map<String, String> propertyNames,
        List<String> restrictedPropertyCodes,
        Status status,
        Long sourceMappingId,
        String sourceSystemName,
        String sourceResourceKey,
        Map<String, Object> sourceKey,
        Instant firstCollectedAt,
        Instant lastSyncedAt,
        Instant dataAsOf) {}
