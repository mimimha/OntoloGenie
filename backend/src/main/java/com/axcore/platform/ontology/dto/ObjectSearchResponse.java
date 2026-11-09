package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ObjectSearchResponse(
        long id,
        long objectTypeId,
        String objectTypeName,
        String displayName,
        Map<String, Object> properties,
        List<String> restrictedPropertyCodes,
        Status status,
        Long sourceMappingId,
        Map<String, Object> sourceKey,
        Instant firstCollectedAt,
        Instant lastSyncedAt,
        Instant dataAsOf) {}
