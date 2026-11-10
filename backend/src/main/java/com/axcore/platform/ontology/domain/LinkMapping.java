package com.axcore.platform.ontology.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 승인된 연결 규칙 개정본(link_mapping, FN-RUL-01). 개정본은 만든 뒤 내용을 바꾸지 않는다(DB 트리거가 막는다). 개정본 저장·폐기(RETIRED)는
 * 규칙 반영(189·186, LinkRuleApplier)이 한다.
 *
 * <p>sourceFilter·targetFilter는 출발·도착 객체 조건이다. 형식은 아직 정하지 않아 저장된 JSON을 그대로 둔다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long linkTypeId;

    private String code;

    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> sourceFilter;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> targetFilter;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    private LinkMappingStatus status;

    private Instant retiredAt;

    @Enumerated(EnumType.STRING)
    private DefinitionOrigin origin;

    private Long proposalItemId;

    // DB 기본값 now()(트랜잭션 시작 시각)가 채운다. 비교 항목 트리거가 이 값으로 "같은 트랜잭션에서 만든 개정본"인지 본다.
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Long createdBy;

    private LinkMapping(
            long linkTypeId,
            String code,
            int revision,
            Map<String, Object> sourceFilter,
            Map<String, Object> targetFilter,
            String description,
            DefinitionSource source,
            long createdBy) {
        this.linkTypeId = linkTypeId;
        this.code = code;
        this.revision = revision;
        this.sourceFilter = new LinkedHashMap<>(sourceFilter);
        this.targetFilter = new LinkedHashMap<>(targetFilter);
        this.description = description;
        this.status = LinkMappingStatus.ACTIVE;
        this.origin = source.origin();
        this.proposalItemId = source.proposalItemId();
        this.createdBy = createdBy;
    }

    /**
     * 승인한 규칙의 새 개정본(189). 같은 코드의 이전 ACTIVE 개정본은 먼저 {@link #retire}해야 한다(코드마다 ACTIVE는 하나,
     * uq_link_mapping_active). 비교 항목({@link LinkMappingCondition})은 같은 트랜잭션에서만 넣을 수 있다(DB 트리거).
     */
    public static LinkMapping revision(
            long linkTypeId,
            String code,
            int revision,
            Map<String, Object> sourceFilter,
            Map<String, Object> targetFilter,
            String description,
            DefinitionSource source,
            long createdBy) {
        return new LinkMapping(
                linkTypeId,
                code,
                revision,
                sourceFilter,
                targetFilter,
                description,
                source,
                createdBy);
    }

    /** 새 개정본으로 대체한다. 내용은 바꾸지 않고 상태와 폐기 시각만 남긴다(DB 트리거가 그 밖의 변경을 막는다). */
    public void retire(Instant now) {
        this.status = LinkMappingStatus.RETIRED;
        this.retiredAt = now;
    }
}
