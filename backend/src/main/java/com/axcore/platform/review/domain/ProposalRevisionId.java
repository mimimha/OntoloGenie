package com.axcore.platform.review.domain;

import java.io.Serializable;

/** {@link ProposalRevision}의 복합 키(proposal_item_id, revision). */
public record ProposalRevisionId(long proposalItemId, int revision) implements Serializable {}
