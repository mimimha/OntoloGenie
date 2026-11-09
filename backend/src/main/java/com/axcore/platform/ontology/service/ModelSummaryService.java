package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.PropertySensitivity;
import com.axcore.platform.ontology.dto.ModelSummaryResponse;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ConditionSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.LinkTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.ObjectTypeSummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.PropertySummary;
import com.axcore.platform.ontology.dto.ModelSummaryResponse.RuleSummary;
import com.axcore.platform.ontology.repository.LinkMappingConditionRepository;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI·Agent용 모델 요약(API 043, FN-ONT-09). 부르는 쪽의 조회 범위({@link DataAccess})를 받아 그 범위 안의 정의만 돌려준다. 사용자
 * 권한이 없는 정의를 LLM에 넘기지 않기 위해서다.
 *
 * <ul>
 *   <li>객체 유형: 볼 수 있는 유형만.
 *   <li>속성: 볼 수 있는 유형의 속성. 민감(RESTRICTED) 속성은 그 유형의 민감 열람 권한이 있을 때만.
 *   <li>관계 유형: 양 끝 유형을 모두 볼 수 있을 때만.
 *   <li>ACTIVE 규칙: 관계 유형이 남아 있고, 비교 조건의 속성이 모두 남아 있고, 출발·도착 조건(sourceFilter·targetFilter)이 숨긴 민감
 *       속성을 가리키지 않을 때만. 일부만 빼면 규칙의 뜻이 바뀌므로 규칙 전체를 뺀다.
 * </ul>
 *
 * <p>조건(filter) 형식은 아직 정하지 않았다. 그래서 JSON 안의 문자열(키와 값)이 숨긴 속성 코드와 같거나 {@code 유형코드.속성코드} 꼴로 그 코드로 끝나면
 * 가리킨다고 본다. 넓게 잡아 빼는 쪽으로 틀린다.
 *
 * <p>모든 목록은 ID 순서다(속성은 유형 ID, 속성 ID 순서).
 */
@Service
@RequiredArgsConstructor
public class ModelSummaryService {

    private final ObjectTypeRepository objectTypes;
    private final PropertyDefinitionRepository properties;
    private final LinkTypeRepository linkTypes;
    private final LinkMappingRepository rules;
    private final LinkMappingConditionRepository conditions;

    @Transactional(readOnly = true)
    public ModelSummaryResponse summarize(DataAccess access) {
        List<ObjectTypeSummary> typeSummaries =
                objectTypes.findAll(Sort.by("id")).stream()
                        .filter(type -> access.canView(type.getId()))
                        .map(ObjectTypeSummary::from)
                        .toList();

        List<PropertyDefinition> visibleProperties = new ArrayList<>();
        Map<Long, Set<String>> hiddenCodesByType = new HashMap<>();
        for (PropertyDefinition property : properties.findAll(Sort.by("objectTypeId", "id"))) {
            long typeId = property.getObjectTypeId();
            if (!access.canView(typeId)) {
                continue;
            }
            if (property.getSensitivity() == PropertySensitivity.RESTRICTED
                    && !access.canViewRestricted(typeId)) {
                hiddenCodesByType
                        .computeIfAbsent(typeId, id -> new HashSet<>())
                        .add(property.getCode());
                continue;
            }
            visibleProperties.add(property);
        }
        Set<Long> visiblePropertyIds =
                visibleProperties.stream()
                        .map(PropertyDefinition::getId)
                        .collect(Collectors.toUnmodifiableSet());

        Map<Long, LinkType> visibleLinkTypes = new HashMap<>();
        List<LinkTypeSummary> linkTypeSummaries = new ArrayList<>();
        for (LinkType linkType : linkTypes.findAll(Sort.by("id"))) {
            if (access.canView(linkType.getSourceTypeId())
                    && access.canView(linkType.getTargetTypeId())) {
                visibleLinkTypes.put(linkType.getId(), linkType);
                linkTypeSummaries.add(LinkTypeSummary.from(linkType));
            }
        }

        List<RuleSummary> ruleSummaries =
                activeRules(visibleLinkTypes, visiblePropertyIds, hiddenCodesByType);

        return new ModelSummaryResponse(
                typeSummaries,
                visibleProperties.stream().map(PropertySummary::from).toList(),
                linkTypeSummaries,
                ruleSummaries);
    }

    private List<RuleSummary> activeRules(
            Map<Long, LinkType> visibleLinkTypes,
            Set<Long> visiblePropertyIds,
            Map<Long, Set<String>> hiddenCodesByType) {
        List<LinkMapping> candidates =
                rules.findByStatusOrderById(LinkMappingStatus.ACTIVE).stream()
                        .filter(rule -> visibleLinkTypes.containsKey(rule.getLinkTypeId()))
                        .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LinkMappingCondition>> conditionsByRule =
                conditions
                        .findByLinkMappingIdInOrderByLinkMappingIdAscSequenceNoAsc(
                                candidates.stream().map(LinkMapping::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(LinkMappingCondition::getLinkMappingId));

        List<RuleSummary> result = new ArrayList<>();
        for (LinkMapping rule : candidates) {
            List<LinkMappingCondition> ruleConditions =
                    conditionsByRule.getOrDefault(rule.getId(), List.of());
            boolean conditionsVisible =
                    ruleConditions.stream()
                            .allMatch(
                                    condition ->
                                            visiblePropertyIds.contains(
                                                            condition.getSourcePropertyId())
                                                    && visiblePropertyIds.contains(
                                                            condition.getTargetPropertyId()));
            LinkType linkType = visibleLinkTypes.get(rule.getLinkTypeId());
            Set<String> hiddenSource =
                    hiddenCodesByType.getOrDefault(linkType.getSourceTypeId(), Set.of());
            Set<String> hiddenTarget =
                    hiddenCodesByType.getOrDefault(linkType.getTargetTypeId(), Set.of());
            if (!conditionsVisible
                    || mentionsAny(rule.getSourceFilter(), hiddenSource)
                    || mentionsAny(rule.getTargetFilter(), hiddenTarget)) {
                continue;
            }
            result.add(
                    RuleSummary.from(
                            rule, ruleConditions.stream().map(ConditionSummary::from).toList()));
        }
        return result;
    }

    /** JSON 값 안의 문자열(맵 키 포함)이 코드와 같거나 ".코드"로 끝나면 true다. */
    static boolean mentionsAny(Object json, Set<String> codes) {
        if (json == null || codes.isEmpty()) {
            return false;
        }
        return switch (json) {
            case String text -> codes.stream().anyMatch(code -> refersTo(text, code));
            case Map<?, ?> map ->
                    map.entrySet().stream()
                            .anyMatch(
                                    entry ->
                                            mentionsAny(entry.getKey(), codes)
                                                    || mentionsAny(entry.getValue(), codes));
            case List<?> list -> list.stream().anyMatch(item -> mentionsAny(item, codes));
            default -> false;
        };
    }

    private static boolean refersTo(String text, String code) {
        return text.equals(code) || text.endsWith("." + code);
    }
}
