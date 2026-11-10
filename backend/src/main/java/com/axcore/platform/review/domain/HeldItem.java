package com.axcore.platform.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 승인한 관계 규칙에 맞지 않아 보류한 개별 건(FN-REV-05·07). 후보와 규칙 중 적어도 하나를 가리킨다. */
@Getter
@Entity
@Table(name = "held_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HeldItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long proposalItemId;

    private Long linkMappingId;

    @Column(nullable = false)
    private Long linkTypeId;

    @Column(nullable = false)
    private Long sourceObjectId;

    @Column(columnDefinition = "text")
    private String rawValue;

    @Column(columnDefinition = "text")
    private String transformedValue;

    @Column(nullable = false, columnDefinition = "text")
    private String holdReason;

    @Column(nullable = false, length = 20)
    private String status;

    private Long resolvedLinkId;

    private Long resolvedBy;

    private Long resolvedSyncRunId;

    private Instant resolvedAt;

    @Column(columnDefinition = "text")
    private String resolutionNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 승인한 관계 규칙에 맞지 않는 개별 건(189 규칙 반영). 처리 전 상태(HELD)로 만든다. */
    public static HeldItem held(
            long proposalItemId,
            long linkMappingId,
            long linkTypeId,
            long sourceObjectId,
            String rawValue,
            String transformedValue,
            String holdReason,
            Instant now) {
        HeldItem item = new HeldItem();
        item.proposalItemId = proposalItemId;
        item.linkMappingId = linkMappingId;
        item.linkTypeId = linkTypeId;
        item.sourceObjectId = sourceObjectId;
        item.rawValue = rawValue;
        item.transformedValue = transformedValue;
        item.holdReason = holdReason;
        item.status = "HELD";
        item.createdAt = now;
        return item;
    }

    /** 새 규칙 개정본 승인으로 출발 객체가 규칙 연결로 이어졌다(189). 그 연결을 근거로 남기고 승인자가 닫은 것으로 기록한다. */
    public void approveByRule(long linkId, long approvedBy, String note, Instant now) {
        this.status = "APPROVED";
        this.resolvedLinkId = linkId;
        this.resolvedBy = approvedBy;
        this.resolvedAt = now;
        this.resolutionNote = note;
    }
}
