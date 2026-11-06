package com.axcore.platform.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Immutable
@Table(name = "proposal_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalOption {

    /** AI 분석 결과로 받은 첫 개정본(revision 1)의 선택지. */
    public static ProposalOption ai(
            long proposalItemId,
            int optionNo,
            Map<String, Object> payload,
            Map<String, Object> evidence,
            BigDecimal aiConfidence) {
        return new ProposalOption(proposalItemId, 1, optionNo, payload, evidence, aiConfidence);
    }

    /** 관리자가 만들거나 고친 후보의 현재 revision에 붙는 선택지. */
    public static ProposalOption manual(
            ProposalItem item,
            int optionNo,
            Map<String, Object> payload,
            Map<String, Object> evidence) {
        return new ProposalOption(
                item.getId(), item.getRevision(), optionNo, payload, evidence, null);
    }

    private ProposalOption(
            long proposalItemId,
            int revision,
            int optionNo,
            Map<String, Object> payload,
            Map<String, Object> evidence,
            BigDecimal aiConfidence) {
        this.proposalItemId = proposalItemId;
        this.revision = revision;
        this.optionNo = optionNo;
        this.payload = payload;
        this.evidence = evidence;
        this.aiConfidence = aiConfidence;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long proposalItemId;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private int optionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    @Column(precision = 5, scale = 4)
    private BigDecimal aiConfidence;
}
