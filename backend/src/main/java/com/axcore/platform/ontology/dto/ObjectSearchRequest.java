package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ObjectSearchRequest(
        String q,
        @Min(1) Long objectTypeId,
        Status status,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {

    public enum Status {
        ACTIVE,
        DELETED_AT_SOURCE
    }
}
