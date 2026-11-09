package com.axcore.platform.ontology.dto;

import java.util.List;

/**
 * 연결 재계산 결과. 부르는 쪽(197·198·189)이 실행 기록에 건수를 남기거나, 보류 건 처리(238)에 넘길 때 쓴다.
 *
 * @param activatedLinkIds 새로 만들었거나 비활성에서 되살린 연결
 * @param updatedLinkIds 이미 활성이었지만 근거 규칙 개정본이나 비교값이 바뀐 연결
 * @param inactivatedLinkIds 더는 성립하지 않아 비활성으로 바꾼 연결
 * @param skippedRuleIds 출발·도착 조건(filter) 형식을 읽지 못해 계산하지 않은 규칙 개정본
 */
public record LinkRecomputeResult(
        List<Long> activatedLinkIds,
        List<Long> updatedLinkIds,
        List<Long> inactivatedLinkIds,
        List<Long> skippedRuleIds) {

    public LinkRecomputeResult {
        activatedLinkIds = List.copyOf(activatedLinkIds);
        updatedLinkIds = List.copyOf(updatedLinkIds);
        inactivatedLinkIds = List.copyOf(inactivatedLinkIds);
        skippedRuleIds = List.copyOf(skippedRuleIds);
    }

    public static LinkRecomputeResult empty() {
        return new LinkRecomputeResult(List.of(), List.of(), List.of(), List.of());
    }
}
