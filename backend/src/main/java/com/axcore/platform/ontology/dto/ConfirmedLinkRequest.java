package com.axcore.platform.ontology.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 관리자가 확정한 연결 하나를 저장하라는 요청(Jira 329). 보류 건 승인(238)과 문서 연결 반영(DOCUMENT_LINK)이 만든다.
 *
 * @param confirmedBy 승인한 관리자. {@code object_link.confirmed_by}
 * @param proposalItemId 근거 후보. {@code object_link.proposal_item_id}. 보류 건 승인이면 보류 건의 후보, 없으면 null
 * @param evidence 연결 근거. 문서 연결은 {@code expression_kind}(OBSERVED·ESTIMATED)가 있어야 한다
 */
public record ConfirmedLinkRequest(
        long linkTypeId,
        long sourceObjectId,
        long targetObjectId,
        long confirmedBy,
        Long proposalItemId,
        Map<String, Object> evidence) {

    public ConfirmedLinkRequest {
        if (linkTypeId < 1 || sourceObjectId < 1 || targetObjectId < 1 || confirmedBy < 1) {
            throw new IllegalArgumentException("관계 유형·객체·승인자 ID는 1 이상입니다.");
        }
        if (proposalItemId != null && proposalItemId < 1) {
            throw new IllegalArgumentException("proposalItemId는 1 이상입니다.");
        }
        evidence =
                evidence == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
