package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.ProposalItemKind;
import com.axcore.platform.ontology.dto.ApplyCommand;
import com.axcore.platform.ontology.dto.ApplyResult;
import com.axcore.platform.ontology.dto.LinkTypeCreateRequest;
import com.axcore.platform.ontology.dto.LinkTypeResponse;
import com.axcore.platform.ontology.repository.LinkTypeRepository;
import com.axcore.platform.ontology.service.LinkTypeService;
import com.axcore.platform.ontology.service.ProposalApplier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LINK_TYPE 후보 반영(Jira 189 1단계). 양 끝 유형은 {@code sourceTypeCode}·{@code targetTypeCode}로 가리킨다. 관계 유형
 * 설명은 DB가 필수로 요구하는데 AI 후보는 빈 설명을 보낼 수 있어, 비어 있으면 이름을 설명으로 쓴다.
 */
@Component
@RequiredArgsConstructor
class LinkTypeApplier implements ProposalApplier {

    private static final String FIELD = "payload";

    private final ApplySupport support;
    private final LinkTypeService linkTypeService;
    private final LinkTypeRepository linkTypes;

    @Override
    public ProposalItemKind kind() {
        return ProposalItemKind.LINK_TYPE;
    }

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Map<String, Object> payload = command.payload();
        String code = support.text(payload, "code", FIELD);
        if (linkTypes.existsByCode(code)) {
            throw ApplySupport.exists(FIELD + ".code", code);
        }
        String name = support.text(payload, "name", FIELD);
        ObjectType sourceType =
                support.objectType(
                        support.text(payload, "sourceTypeCode", FIELD), FIELD + ".sourceTypeCode");
        ObjectType targetType =
                support.objectType(
                        support.text(payload, "targetTypeCode", FIELD), FIELD + ".targetTypeCode");
        String description = support.optionalText(payload, "description", FIELD);
        LinkTypeCreateRequest request =
                support.valid(
                        new LinkTypeCreateRequest(
                                code,
                                name,
                                sourceType.getId(),
                                targetType.getId(),
                                support.text(payload, "cardinality", FIELD),
                                description == null || description.isBlank() ? name : description),
                        FIELD);
        LinkTypeResponse created =
                support.call(
                        FIELD,
                        () ->
                                linkTypeService.create(
                                        request,
                                        DefinitionSource.fromProposal(
                                                command.origin(), command.proposalItemId())));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", code);
        after.put("sourceTypeId", sourceType.getId());
        after.put("targetTypeId", targetType.getId());
        after.put("cardinality", request.cardinality());
        return new ApplyResult(
                "link_type",
                created.id(),
                List.of(),
                List.of(),
                Map.of("linkTypeCode", code),
                List.of(ChangeEntry.insert("link_type", Map.of("id", created.id()), after)));
    }
}
