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
}
