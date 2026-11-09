package com.axcore.platform.ontology.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 연결 규칙 개정본의 속성 비교 항목(link_mapping_condition). 항목끼리 AND다. 규칙을 만든 트랜잭션에서만 추가하고 바꾸거나 지우지 않는다(DB 트리거).
 * 변환 단계는 저장된 JSON 배열을 그대로 둔다({@code [{"op": "UPPER", "arg": null}]}).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkMappingCondition {

    @Id private Long id;

    private Long linkMappingId;

    private Long sourcePropertyId;

    private Long targetPropertyId;

    private int sequenceNo;

    private String operator;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> sourceTransforms;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> targetTransforms;

    private Instant createdAt;

    private Long createdBy;
}
