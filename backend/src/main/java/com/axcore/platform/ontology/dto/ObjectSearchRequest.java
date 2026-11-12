package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ObjectSearchRequest(
        String q,
        @Min(1) Long objectTypeId,
        Status status,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        Map<String, String> propertyValues,
        String dateProperty,
        String from,
        String to) {

    public ObjectSearchRequest {
        propertyValues =
                propertyValues == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(propertyValues));
    }

    public ObjectSearchRequest(
            String q, Long objectTypeId, Status status, Integer page, Integer size) {
        this(q, objectTypeId, status, page, size, Map.of(), null, null, null);
    }

    public boolean hasFilters() {
        return !propertyValues.isEmpty() || dateProperty != null || from != null || to != null;
    }

    public enum Status {
        ACTIVE,
        DELETED_AT_SOURCE
    }
}
