package com.axcore.platform.check.dto;

import java.util.List;

public record ReverificationComparisonResponse(
        long comparedToRunId, Coverage coverage, boolean hasBaselinePassEvidence, List<Row> rows) {

    public enum Coverage {
        PARTIAL,
        UNAVAILABLE
    }

    public enum Category {
        RESOLVED,
        REMAINING,
        NEW
    }

    public record Evidence(
            long linkMappingId,
            String classification,
            String rawValue,
            String transformedValue,
            String reason) {}

    public record Row(
            Category category,
            String ruleCode,
            long objectInstanceId,
            Evidence before,
            Evidence after) {}
}
