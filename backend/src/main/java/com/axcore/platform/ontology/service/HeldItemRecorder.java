package com.axcore.platform.ontology.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 승인한 관계 규칙에 맞지 않는 개별 건을 보류 건(held_item)으로 남긴다(189 → 238). held_item은 검토(review) 도메인의 표라서, 규칙 반영기는
 * 이 인터페이스로만 쓰고 구현은 검토 도메인이 한다(.ai/rules/database.md: 다른 도메인 표는 그 도메인이 바꾼다).
 */
public interface HeldItemRecorder {

    /** 관계 유형에 아직 열린(HELD) 보류 건이 있는 출발 객체. 이 객체들은 새로 만들지 않는다. */
    Set<Long> openSourceObjectIds(long linkTypeId);

    /**
     * 관계 유형의 열린(HELD) 보류 건 중, 출발 객체에 이제 그 관계 유형의 규칙 연결(근거 규칙 무관)이 있는 것을 APPROVED로 닫는다(새 규칙 개정본 승인으로
     * 연결됨). 자동 해소(AUTO_RESOLVED)는 수집 실행이 있어야 해서 승인 경로에서는 쓸 수 없다.
     *
     * @param linkBySource 출발 객체 → 그 출발을 잇는 규칙 연결 ID
     * @param resolvedBy 규칙을 승인한 계정
     * @return 닫은 보류 건 ID
     */
    List<Long> closeLinkedByRule(
            long linkTypeId, Map<Long, Long> linkBySource, long resolvedBy, String note);

    /** 보류 건들을 HELD로 만들고 ID를 같은 순서로 돌려준다. 부르는 쪽 트랜잭션 안에서 쓴다. */
    List<Long> holdAll(List<NewHeldItem> items);

    /**
     * @param proposalItemId 규칙을 반영한 후보
     * @param linkMappingId 반영한 규칙 개정본
     * @param linkTypeId 만들어야 했던 관계 유형
     * @param sourceObjectId 연결되지 않은 출발 객체
     * @param rawValue 비교에 쓴 원래 값
     * @param transformedValue 변환 뒤 값. 변환에 실패했으면 null
     * @param holdReason 보류 이유
     */
    record NewHeldItem(
            long proposalItemId,
            long linkMappingId,
            long linkTypeId,
            long sourceObjectId,
            String rawValue,
            String transformedValue,
            String holdReason) {}
}
