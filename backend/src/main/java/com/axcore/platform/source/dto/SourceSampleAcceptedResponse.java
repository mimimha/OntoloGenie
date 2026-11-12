package com.axcore.platform.source.dto;

import java.time.Instant;

public record SourceSampleAcceptedResponse(
        long previewId, String status, boolean joined, Instant requestedAt, Instant expiresAt) {}
