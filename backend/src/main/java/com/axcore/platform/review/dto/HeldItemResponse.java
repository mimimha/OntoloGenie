package com.axcore.platform.review.dto;

import com.axcore.platform.review.domain.HeldItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record HeldItemResponse(
        long id,
        Long proposalItemId,
        Long linkMappingId,
        long sourceObjectId,
        long linkTypeId,
        String status,
        String rawValue,
        String transformedValue,
        String holdReason,
        Long resolvedLinkId,
        Long resolvedSyncRunId,
        Long resolvedBy,
        Instant resolvedAt,
        String resolutionNote,
        Instant createdAt) {

    public static HeldItemResponse from(HeldItem item) {
        return new HeldItemResponse(
                item.getId(),
                item.getProposalItemId(),
                item.getLinkMappingId(),
                item.getSourceObjectId(),
                item.getLinkTypeId(),
                item.getStatus(),
                item.getRawValue(),
                item.getTransformedValue(),
                item.getHoldReason(),
                item.getResolvedLinkId(),
                item.getResolvedSyncRunId(),
                item.getResolvedBy(),
                item.getResolvedAt(),
                item.getResolutionNote(),
                item.getCreatedAt());
    }
}
