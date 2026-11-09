package com.axcore.platform.ontology.domain;

import java.util.Objects;

/**
 * 정의·매핑을 만든 출처. 관리자가 직접 만들면 {@link #MANUAL}, 승인한 후보를 반영해 만들면 그 후보 ID를 함께 남긴다(Jira 189). AI 후보면 DB가
 * proposal_item_id를 필수로 요구한다(origin = AI_PROPOSAL).
 */
public record DefinitionSource(DefinitionOrigin origin, Long proposalItemId) {

    public static final DefinitionSource MANUAL =
            new DefinitionSource(DefinitionOrigin.MANUAL, null);

    public DefinitionSource {
        Objects.requireNonNull(origin, "origin");
        if (origin == DefinitionOrigin.AI_PROPOSAL && proposalItemId == null) {
            throw new IllegalArgumentException("AI 후보에서 만든 정의는 후보 ID가 있어야 합니다.");
        }
    }

    /** 승인한 후보에서 만든 정의. AI 후보면 AI_PROPOSAL, 관리자가 등록한 후보면 MANUAL이고 둘 다 후보 ID를 남긴다. */
    public static DefinitionSource fromProposal(ProposalOrigin origin, long proposalItemId) {
        return new DefinitionSource(origin.definitionOrigin(), proposalItemId);
    }
}
