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
import org.hibernate.annotations.Immutable;

/** 후보 결정(승인·거절·보류)과 사유. 결정할 때마다 한 행을 추가하는 이력이므로 저장 후 바꾸지 않는다. */
@Getter
@Entity
@Immutable
@Table(name = "review_decision")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDecision {

    /** 잠긴 후보의 현재 revision을 결정 대상 revision으로 남긴다. */
    public static ReviewDecision record(
            ProposalItem item,
            String decision,
            Long selectedOptionId,
            String reason,
            long decidedBy,
            Instant decidedAt) {
        ReviewDecision record = new ReviewDecision();
        record.proposalItemId = item.getId();
        record.itemRevision = item.getRevision();
        record.decision = decision;
        record.selectedOptionId = selectedOptionId;
        record.reason = reason;
        record.decidedBy = decidedBy;
        record.decidedAt = decidedAt;
        return record;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long proposalItemId;

    @Column(nullable = false)
    private int itemRevision;

    @Column(nullable = false, length = 20)
    private String decision;

    private Long selectedOptionId;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private Long decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;
}
