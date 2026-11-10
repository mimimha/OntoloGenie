package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.DefinitionOrigin;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API 명세의 RuleRevision(046·047). 연결 규칙 개정본(link_mapping) 하나와 그 비교 항목. 퇴역 시각·설명·생성자·원본 제안처럼 비어 있을 수
 * 있는 칸도 null로 내보낸다(명세 예시와 같음).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuleRevisionResponse(
        long id,
        String code,
        int revision,
        long linkTypeId,
        String status,
        List<Condition> conditions,
        Map<String, Object> sourceFilter,
        Map<String, Object> targetFilter,
        Instant retiredAt,
        String description,
        Instant createdAt,
        Long createdBy,
        DefinitionOrigin origin,
        Long proposalItemId) {

    /**
     * @param conditions 이 개정본의 비교 항목. 비교 순서대로
     */
    public static RuleRevisionResponse of(LinkMapping rule, List<LinkMappingCondition> conditions) {
        return new RuleRevisionResponse(
                rule.getId(),
                rule.getCode(),
                rule.getRevision(),
                rule.getLinkTypeId(),
                rule.getStatus().name(),
                conditions.stream().map(Condition::from).toList(),
                rule.getSourceFilter(),
                rule.getTargetFilter(),
                rule.getRetiredAt(),
                rule.getDescription(),
                rule.getCreatedAt(),
                rule.getCreatedBy(),
                rule.getOrigin(),
                rule.getProposalItemId());
    }

    /** EQ 비교 항목 하나. 항목끼리는 AND다. 변환 단계가 없으면 빈 배열이다(041 형식). */
    public record Condition(
            int sequenceNo,
            long sourcePropertyId,
            long targetPropertyId,
            String operator,
            List<Map<String, Object>> sourceTransforms,
            List<Map<String, Object>> targetTransforms) {

        static Condition from(LinkMappingCondition condition) {
            return new Condition(
                    condition.getSequenceNo(),
                    condition.getSourcePropertyId(),
                    condition.getTargetPropertyId(),
                    condition.getOperator(),
                    condition.getSourceTransforms() == null
                            ? List.of()
                            : condition.getSourceTransforms(),
                    condition.getTargetTransforms() == null
                            ? List.of()
                            : condition.getTargetTransforms());
        }
    }
}
