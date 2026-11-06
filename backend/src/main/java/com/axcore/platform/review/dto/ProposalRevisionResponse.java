package com.axcore.platform.review.dto;

import com.axcore.platform.review.domain.ProposalRevision;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalRevisionResponse(
        int revision,
        Map<String, Object> payload,
        Map<String, Object> evidence,
        BigDecimal aiConfidence,
        Long createdBy,
        Instant createdAt) {

    public ProposalRevisionResponse {
        if (evidence == null) {
            evidence = Map.of();
        }
    }

    public static ProposalRevisionResponse from(ProposalRevision revision) {
        return new ProposalRevisionResponse(
                revision.getRevision(),
                revision.getPayload(),
                revision.getEvidence(),
                revision.getAiConfidence(),
                revision.getCreatedBy(),
                revision.getCreatedAt());
    }
}
