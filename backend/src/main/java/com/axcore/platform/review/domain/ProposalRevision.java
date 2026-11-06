package com.axcore.platform.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 후보의 revision별 불변 내용. 만든 뒤 수정·삭제하지 않는다(DB 트리거가 막는다).
 *
 * <p>키를 직접 정하므로 {@link Persistable}로 새 행임을 알린다. 그러지 않으면 저장할 때 merge가 먼저 SELECT를 한다.
 *
 * <p>created_at을 정하지 않은 개정본(관리자 작성·수정)은 INSERT에서 그 컬럼을 빼서 DB 기본값 now()를 쓴다({@link DynamicInsert}).
 * JdbcClient로 저장하던 때와 같은 값이다.
 */
@Getter
@Entity
@Immutable
@DynamicInsert
@IdClass(ProposalRevisionId.class)
@Table(name = "proposal_revision")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalRevision implements Persistable<ProposalRevisionId> {

    /** AI 분석 결과로 받은 첫 개정본. 작성자는 없다. */
    public static ProposalRevision ai(
            long proposalItemId,
            Map<String, Object> payload,
            Map<String, Object> evidence,
            BigDecimal aiConfidence,
            Instant createdAt) {
        return new ProposalRevision(
                proposalItemId, 1, payload, evidence, aiConfidence, null, createdAt);
    }

    /** 관리자가 만들거나 고친 후보의 현재 내용을 개정본으로 남긴다. 작성 시각은 DB 기본값을 쓴다. */
    public static ProposalRevision snapshot(ProposalItem item, long createdBy) {
        return new ProposalRevision(
                item.getId(),
                item.getRevision(),
                item.getPayload(),
                item.getEvidence(),
                null,
                createdBy,
                null);
    }

    private ProposalRevision(
            long proposalItemId,
            int revision,
            Map<String, Object> payload,
            Map<String, Object> evidence,
            BigDecimal aiConfidence,
            Long createdBy,
            Instant createdAt) {
        this.proposalItemId = proposalItemId;
        this.revision = revision;
        this.payload = payload;
        this.evidence = evidence;
        this.aiConfidence = aiConfidence;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    @Id private long proposalItemId;

    @Id private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    @Column(precision = 5, scale = 4)
    private BigDecimal aiConfidence;

    /** DB에서 NOT NULL DEFAULT now()다. null로 저장하면 INSERT에서 빠지므로 여기서는 nullable로 둔다. */
    @Column(updatable = false)
    private Instant createdAt;

    private Long createdBy;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    @Override
    public ProposalRevisionId getId() {
        return new ProposalRevisionId(proposalItemId, revision);
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }
}
