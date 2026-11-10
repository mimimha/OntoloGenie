package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.mapping.service.TransformStepsValidator;
import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingCondition;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.domain.LinkType;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.dto.LinkRecomputeResult;
import com.axcore.platform.ontology.repository.LinkMappingConditionRepository;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.RuleLinkRepository;
import com.axcore.platform.ontology.repository.RuleLinkRepository.StoredLink;
import com.axcore.platform.ontology.service.HeldItemRecorder;
import com.axcore.platform.ontology.service.HeldItemRecorder.NewHeldItem;
import com.axcore.platform.ontology.service.LinkRecomputeService;
import com.axcore.platform.ontology.service.LinkRecomputeService.LinkRuleNotComputableException;
import com.axcore.platform.ontology.service.LinkRecomputeService.UnlinkedSource;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LINK_RULE 후보 반영(Jira 189 2단계·186). 순서:
 *
 * <ol>
 *   <li>관계 유형을 찾는다({@code linkTypeId} 또는 {@code linkTypeCode}). 없고 AI 묶음 {@code linkType}이 있으면 먼저
 *       만든다.
 *   <li>같은 규칙 코드의 ACTIVE 개정본을 RETIRED로 바꾸고 다음 번호의 개정본과 비교 항목을 만든다(결정 U6, 이전 개정본은 보존).
 *   <li>그 관계 유형의 연결을 다시 계산한다({@link LinkRecomputeService#recomputeRules}). 옛 개정본 연결도 새 개정본 기준으로
 *       바뀐다.
 *   <li>새 규칙으로 연결되지 않은 출발 객체를 보류 건으로 남긴다(2026-11-09 결정 (a), 시나리오 2 W23 → E023). issue는 만들지 않는다(결정
 *       문서 8장: 미연결 issue는 SYNC 검사만).
 * </ol>
 *
 * <p>조건은 {@code conditions[]} {sourceProperty, targetProperty, operator(EQ), sourceTransforms,
 * targetTransforms}로, 속성은 {@code 유형코드.속성코드} 또는 {@code 속성코드}로 가리킨다. 출발·도착 조건(filter)은 {@code {}} 또는
 * {@code {property, equals}}만 받는다(연결 재계산이 읽는 형식).
 */
@Component
@RequiredArgsConstructor
class LinkRuleApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplySupport support;
    private final ApplyWriter writer;
    private final LinkTypeRepository linkTypes;
    private final ObjectTypeRepository objectTypes;
    private final LinkMappingRepository rules;
    private final LinkMappingConditionRepository conditions;
    private final LinkRecomputeService recompute;
    private final HeldItemRecorder heldItems;
    private final RuleLinkRepository ruleLinks;
    private final Clock clock;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.LINK_RULE;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        DefinitionSource source =
                DefinitionSource.fromProposal(command.origin(), command.proposalItemId());
        List<ChangeEntry> changes = new ArrayList<>();
        Instant now = clock.instant();

        // payload 형식을 먼저 본다.
        String code = support.text(payload, "code", FIELD);
        String description = support.optionalText(payload, "description", FIELD);
        List<Map<String, Object>> rawConditions =
                support.optionalObjects(payload, "conditions", FIELD);
        if (rawConditions.isEmpty()) {
            throw ApplySupport.invalid(FIELD + ".conditions", "비교 조건을 하나 이상 지정해야 합니다.");
        }
        Map<String, Object> sourceFilter = filter(payload, "sourceFilter");
        Map<String, Object> targetFilter = filter(payload, "targetFilter");

        LinkType linkType = linkType(payload, source, changes);
        ObjectType sourceType = objectTypes.findById(linkType.getSourceTypeId()).orElseThrow();
        ObjectType targetType = objectTypes.findById(linkType.getTargetTypeId()).orElseThrow();
        requireFilterProperty(sourceFilter, sourceType, FIELD + ".sourceFilter");
        requireFilterProperty(targetFilter, targetType, FIELD + ".targetFilter");
        List<ParsedCondition> parsed = new ArrayList<>();
        for (int i = 0; i < rawConditions.size(); i++) {
            parsed.add(
                    condition(
                            rawConditions.get(i),
                            sourceType,
                            targetType,
                            FIELD + ".conditions[" + i + "]"));
        }

        // 개정본: 같은 코드는 같은 관계 유형이어야 한다(연결의 근거가 다른 관계 유형으로 넘어가지 않게).
        LinkMapping previous =
                rules.findByCodeAndStatus(code, LinkMappingStatus.ACTIVE).orElse(null);
        if (previous != null && !previous.getLinkTypeId().equals(linkType.getId())) {
            throw ApplySupport.invalid(
                    FIELD + ".linkTypeCode", "같은 규칙 코드의 현재 개정본과 관계 유형이 다릅니다: " + code);
        }
        int revision =
                rules.findTopByCodeOrderByRevisionDesc(code)
                        .map(latest -> latest.getRevision() + 1)
                        .orElse(1);
        LinkMapping created =
                support.call(
                        FIELD,
                        () -> {
                            if (previous != null) {
                                previous.retire(now);
                                rules.saveAndFlush(previous);
                            }
                            LinkMapping rule =
                                    rules.saveAndFlush(
                                            LinkMapping.revision(
                                                    linkType.getId(),
                                                    code,
                                                    revision,
                                                    sourceFilter,
                                                    targetFilter,
                                                    description,
                                                    source,
                                                    command.decidedBy()));
                            for (int i = 0; i < parsed.size(); i++) {
                                ParsedCondition c = parsed.get(i);
                                conditions.save(
                                        LinkMappingCondition.of(
                                                rule.getId(),
                                                i + 1,
                                                c.sourcePropertyId(),
                                                c.targetPropertyId(),
                                                c.sourceTransforms(),
                                                c.targetTransforms(),
                                                command.decidedBy()));
                            }
                            conditions.flush();
                            return rule;
                        });
        if (previous != null) {
            changes.add(
                    ChangeEntry.update(
                            "link_mapping",
                            Map.of("id", previous.getId()),
                            Map.of("status", "ACTIVE"),
                            Map.of("status", "RETIRED")));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", code);
        after.put("revision", revision);
        after.put("linkTypeId", linkType.getId());
        after.put("conditionCount", parsed.size());
        changes.add(ChangeEntry.insert("link_mapping", Map.of("id", created.getId()), after));

        LinkRecomputeResult links;
        List<UnlinkedSource> unlinked;
        try {
            links = recompute.recomputeRules(List.of(created.getId()));
            unlinked = recompute.unlinkedSources(created.getId());
        } catch (LinkRuleNotComputableException e) {
            throw ApplySupport.invalid(FIELD, e.getMessage());
        }

        // 이전 개정본 등에서 열어 둔 보류 건 중, 재계산 뒤 이 관계 유형의 규칙 연결이 있는 출발은 닫는다(2026-11-09 결정 (a)).
        // 근거가 이 개정본인 연결만 보지 않는다. 같은 쌍을 원하는 다른 규칙 코드가 근거를 유지하면(소유 규칙 유지) 이번 승인으로
        // 이어졌어도 근거가 다르다. 열어 두면 관리자가 238에서 승인할 때 같은 쌍의 수동 연결을 또 만들려다 충돌한다.
        // 이번 계산에서 생기거나 바뀐 연결을 먼저 근거로 쓴다. 그 밖의 기존 연결(예: 수집이 이미 이었지만 자동 해소가 아직 없어 열린 건)로
        // 닫을 때는 메모를 달리한다. 출발마다 연결이 여럿이면 가장 작은 ID를 골라 실행마다 같게 한다.
        Set<Long> changedLinks = new HashSet<>(links.activatedLinkIds());
        changedLinks.addAll(links.updatedLinkIds());
        Map<Long, Long> changedBySource = new LinkedHashMap<>();
        Map<Long, Long> existingBySource = new LinkedHashMap<>();
        for (StoredLink link : ruleLinks.linksOf(linkType.getId())) {
            if (link.active() && link.isRule()) {
                (changedLinks.contains(link.id()) ? changedBySource : existingBySource)
                        .merge(link.sourceObjectId(), link.id(), Math::min);
            }
        }
        existingBySource.keySet().removeAll(changedBySource.keySet());
        List<Long> closedHeldItemIds = new ArrayList<>();
        closedHeldItemIds.addAll(
                heldItems.closeLinkedByRule(
                        linkType.getId(),
                        changedBySource,
                        command.decidedBy(),
                        "규칙 " + code + " 개정본 " + revision + " 승인으로 연결됨"));
        closedHeldItemIds.addAll(
                heldItems.closeLinkedByRule(
                        linkType.getId(),
                        existingBySource,
                        command.decidedBy(),
                        "규칙 " + code + " 개정본 " + revision + " 승인 때 이미 규칙 연결이 있어 닫음"));

        // 열린 보류 건은 한 번 읽는다(대상 표가 적재 전이면 출발 거의 전부가 보류 후보가 된다).
        Set<Long> open = heldItems.openSourceObjectIds(linkType.getId());
        List<NewHeldItem> toHold = new ArrayList<>();
        for (UnlinkedSource item : unlinked) {
            if (open.contains(item.sourceObjectId())) {
                continue;
            }
            toHold.add(
                    new NewHeldItem(
                            command.proposalItemId(),
                            created.getId(),
                            linkType.getId(),
                            item.sourceObjectId(),
                            item.rawValue(),
                            item.transformedValue(),
                            item.reason()));
        }
        List<Long> heldItemIds = heldItems.holdAll(toHold);
        for (Long id : closedHeldItemIds) {
            changes.add(
                    ChangeEntry.update(
                            "held_item",
                            Map.of("id", id),
                            Map.of("status", "HELD"),
                            Map.of("status", "APPROVED")));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ruleCode", code);
        summary.put("revision", revision);
        summary.put("retiredRevisionId", previous == null ? null : previous.getId());
        summary.put("linksActivated", links.activatedLinkIds().size());
        summary.put("linksUpdated", links.updatedLinkIds().size());
        summary.put("linksInactivated", links.inactivatedLinkIds().size());
        summary.put("heldCount", heldItemIds.size());
        summary.put("heldClosedCount", closedHeldItemIds.size());
        summary.put("closedHeldItemIds", closedHeldItemIds);
        return new ApplyResult(
                "link_mapping", created.getId(), heldItemIds, List.of(), summary, changes);
    }

    /**
     * {@code linkTypeId}(관리자 후보) 또는 {@code linkTypeCode}. 코드로 찾을 수 없고 AI 묶음 {@code linkType}이 있으면
     * 만든다.
     */
    private LinkType linkType(
            Map<String, Object> payload, DefinitionSource source, List<ChangeEntry> changes) {
        Object rawId = payload.get("linkTypeId");
        if (rawId != null) {
            if (!(rawId instanceof Number number) || number.longValue() < 1) {
                throw ApplySupport.invalid(FIELD + ".linkTypeId", "양의 정수 ID여야 합니다.");
            }
            return linkTypes
                    .findById(number.longValue())
                    .orElseThrow(
                            () ->
                                    ApplySupport.missing(
                                            FIELD + ".linkTypeId",
                                            String.valueOf(rawId),
                                            "LINK_TYPE"));
        }
        String code = support.text(payload, "linkTypeCode", FIELD);
        LinkType existing = linkTypes.findByCode(code).orElse(null);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> bundled = support.optionalObject(payload, "linkType", FIELD);
        if (bundled == null) {
            throw ApplySupport.missing(FIELD + ".linkTypeCode", code, "LINK_TYPE");
        }
        if (!code.equals(bundled.get("code"))) {
            throw ApplySupport.invalid(FIELD + ".linkType.code", "linkTypeCode와 같은 관계 유형이어야 합니다.");
        }
        ApplyWriter.Written written = writer.linkType(bundled, FIELD + ".linkType", source);
        changes.add(written.change());
        return linkTypes.findById(written.id()).orElseThrow();
    }

    private ParsedCondition condition(
            Map<String, Object> condition,
            ObjectType sourceType,
            ObjectType targetType,
            String field) {
        PropertyDefinition sourceProperty =
                property(condition, "sourceProperty", sourceType, field);
        PropertyDefinition targetProperty =
                property(condition, "targetProperty", targetType, field);
        String operator = support.optionalText(condition, "operator", field);
        if (operator != null && !"EQ".equals(operator)) {
            throw ApplySupport.invalid(field + ".operator", "비교는 EQ만 지원합니다.");
        }
        return new ParsedCondition(
                sourceProperty.getId(),
                targetProperty.getId(),
                transforms(condition, "sourceTransforms", field),
                transforms(condition, "targetTransforms", field));
    }

    /** {@code 유형코드.속성코드} 또는 {@code 속성코드}. 유형코드를 붙였다면 그 쪽(출발·도착) 유형과 같아야 한다. */
    private PropertyDefinition property(
            Map<String, Object> condition, String key, ObjectType type, String field) {
        String reference = support.text(condition, key, field);
        String code = reference;
        int dot = reference.lastIndexOf('.');
        if (dot >= 0) {
            if (!reference.substring(0, dot).equals(type.getCode())) {
                throw ApplySupport.invalid(
                        field + "." + key, "관계의 출발·도착 객체 유형과 일치하지 않습니다: " + reference);
            }
            code = reference.substring(dot + 1);
        }
        return support.property(type, code, field + "." + key);
    }

    /** 변환 단계를 매핑과 같은 기준으로 검사하고 저장 형식({op, arg})으로 바꾼다. */
    private List<Map<String, Object>> transforms(
            Map<String, Object> condition, String key, String field) {
        List<TransformStepPayload> steps =
                support.steps(condition, key, field).stream()
                        .map(TransformStepPayload::from)
                        .toList();
        support.call(field + "." + key, () -> TransformStepsValidator.parse(steps));
        return steps.stream().map(TransformStepPayload::toStored).toList();
    }

    /** 출발·도착 조건. 없으면 {@code {}}. 형식은 {@code {}} 또는 {@code {property, equals}}만 받는다. */
    private Map<String, Object> filter(Map<String, Object> payload, String key) {
        Map<String, Object> filter = support.optionalObject(payload, key, FIELD);
        if (filter == null || filter.isEmpty()) {
            return Map.of();
        }
        if (filter.size() != 2
                || !(filter.get("property") instanceof String property)
                || property.isBlank()
                || !(filter.get("equals") instanceof String)) {
            throw ApplySupport.invalid(
                    FIELD + "." + key, "조건은 {} 또는 {\"property\": ..., \"equals\": ...}여야 합니다.");
        }
        return new LinkedHashMap<>(filter);
    }

    private void requireFilterProperty(Map<String, Object> filter, ObjectType type, String field) {
        if (filter.isEmpty()) {
            return;
        }
        property(filter, "property", type, field);
    }

    private record ParsedCondition(
            long sourcePropertyId,
            long targetPropertyId,
            List<Map<String, Object>> sourceTransforms,
            List<Map<String, Object>> targetTransforms) {}
}
