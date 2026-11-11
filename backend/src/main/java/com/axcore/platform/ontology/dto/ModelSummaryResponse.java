package com.axcore.platform.ontology.dto;

import com.axcore.platform.ontology.domain.LinkCardinality;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDataType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.domain.TemporalRole;
import java.util.List;
import java.util.Map;

/**
 * API 043 AI·Agent용 모델 요약. AI 분석(FastAPI)과 Agent가 같은 구조를 쓴다. 조회 범위 밖의 정의는 빠진다({@link
 * com.axcore.platform.ontology.service.ModelSummaryService}).
 */
public record ModelSummaryResponse(
        List<ObjectTypeSummary> objectTypes,
        List<PropertySummary> properties,
        List<LinkTypeSummary> linkTypes,
        List<RuleSummary> activeRules) {

    public ModelSummaryResponse {
        objectTypes = List.copyOf(objectTypes);
        properties = List.copyOf(properties);
        linkTypes = List.copyOf(linkTypes);
        activeRules = List.copyOf(activeRules);
    }

    public record ObjectTypeSummary(Long id, String code, String name, String description) {

        public static ObjectTypeSummary from(ObjectType type) {
            return new ObjectTypeSummary(
                    type.getId(), type.getCode(), type.getName(), type.getDescription());
        }
    }

    public record PropertySummary(
            Long id,
            Long objectTypeId,
            String code,
            String name,
            PropertyDataType dataType,
            String description,
            PropertySensitivity sensitivity,
            TemporalRole temporalRole) {

        public static PropertySummary from(PropertyDefinition property) {
            return new PropertySummary(
                    property.getId(),
                    property.getObjectTypeId(),
                    property.getCode(),
                    property.getName(),
                    property.getDataType(),
                    property.getDescription(),
                    property.getSensitivity(),
                    property.getTemporalRole());
        }
    }

    public record LinkTypeSummary(
            Long id,
            String code,
            String name,
            Long sourceTypeId,
            Long targetTypeId,
            LinkCardinality cardinality,
            String description) {

        public static LinkTypeSummary from(LinkType linkType) {
            return new LinkTypeSummary(
                    linkType.getId(),
                    linkType.getCode(),
                    linkType.getName(),
                    linkType.getSourceTypeId(),
                    linkType.getTargetTypeId(),
                    linkType.getCardinality(),
                    linkType.getDescription());
        }
    }

    /** 승인된 ACTIVE 규칙 개정본. 조건 형식은 API 047과 같다. */
    public record RuleSummary(
            Long id,
            String code,
            int revision,
            Long linkTypeId,
            Map<String, Object> sourceFilter,
            Map<String, Object> targetFilter,
            List<ConditionSummary> conditions) {

        public RuleSummary {
            conditions = List.copyOf(conditions);
        }

        public static RuleSummary from(LinkMapping rule, List<ConditionSummary> conditions) {
            return new RuleSummary(
                    rule.getId(),
                    rule.getCode(),
                    rule.getRevision(),
                    rule.getLinkTypeId(),
                    rule.getSourceFilter(),
                    rule.getTargetFilter(),
                    conditions);
        }
    }

    public record ConditionSummary(
            int sequenceNo,
            Long sourcePropertyId,
            Long targetPropertyId,
            String operator,
            List<Map<String, Object>> sourceTransforms,
            List<Map<String, Object>> targetTransforms) {

        public static ConditionSummary from(LinkMappingCondition condition) {
            return new ConditionSummary(
                    condition.getSequenceNo(),
                    condition.getSourcePropertyId(),
                    condition.getTargetPropertyId(),
                    condition.getOperator(),
                    condition.getSourceTransforms(),
                    condition.getTargetTransforms());
        }
    }
}
