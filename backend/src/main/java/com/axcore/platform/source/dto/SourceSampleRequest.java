package com.axcore.platform.source.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record SourceSampleRequest(@Min(1) @Max(20) Integer rowLimit) {

    public static final int DEFAULT_ROW_LIMIT = 20;
}
