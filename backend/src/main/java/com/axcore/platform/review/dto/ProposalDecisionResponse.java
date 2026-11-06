package com.axcore.platform.review.dto;

import com.axcore.platform.review.domain.ReviewDecision;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProposalDecisionResponse(
        long id,
        long proposalItemId,
        int itemRevision,
        String decision,
        Long selectedOptionId,
        String reason,
        long decidedBy,
        Instant decidedAt) {

    public static ProposalDecisionResponse from(ReviewDecision decision) {
        return new ProposalDecisionResponse(
                decision.getId(),
                decision.getProposalItemId(),
                decision.getItemRevision(),
                decision.getDecision(),
                decision.getSelectedOptionId(),
                decision.getReason(),
                decision.getDecidedBy(),
                decision.getDecidedAt());
    }
}
