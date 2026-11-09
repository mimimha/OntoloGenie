package com.axcore.platform.ontology.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 승인된 연결 규칙 개정본(link_mapping, FN-RUL-01). 개정본은 만든 뒤 내용을 바꾸지 않는다(DB 트리거가 막는다). 지금은 조회만 한다. 개정본
 * 저장·폐기(RETIRED)는 186·189에서 더한다.
 *
 * <p>sourceFilter·targetFilter는 출발·도착 객체 조건이다. 형식은 아직 정하지 않아 저장된 JSON을 그대로 둔다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkMapping {

    @Id private Long id;

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

    private Instant createdAt;

    private Long createdBy;
}
