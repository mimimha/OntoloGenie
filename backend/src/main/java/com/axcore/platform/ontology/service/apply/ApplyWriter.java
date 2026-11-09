package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.dto.ObjectMappingCreateRequest;
import com.axcore.platform.mapping.dto.ObjectMappingResponse;
import com.axcore.platform.mapping.dto.PropertyMappingCreateRequest;
import com.axcore.platform.mapping.dto.PropertyMappingWriteResponse;
import com.axcore.platform.mapping.dto.TransformStepPayload;
import com.axcore.platform.mapping.service.ObjectMappingService;
import com.axcore.platform.mapping.service.PropertyMappingService;
import com.axcore.platform.ontology.domain.DefinitionSource;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.dto.PropertyCreateRequest;
import com.axcore.platform.ontology.dto.PropertyResponse;
import com.axcore.platform.ontology.service.PropertyService;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 속성·객체 매핑·속성 매핑을 만든다. 따로 온 후보(PROPERTY·OBJECT_MAPPING·PROPERTY_MAPPING)와 AI 묶음 객체 후보(OBJECT_TYPE에
 * 함께 온 properties·objectMapping)가 같이 쓴다. 실제 저장과 검증은 기존 등록 서비스(027·035·038)가 한다.
 */
@Component
@RequiredArgsConstructor
class ApplyWriter {

    private final ApplySupport support;
    private final PropertyService propertyService;
    private final ObjectMappingService objectMappingService;
    private final PropertyMappingService propertyMappingService;

    /** 속성 하나. payload 칸은 API 027과 같고 이름만 후보 형식(isRequired 생략 = false)이다. */
    Written property(
            ObjectType type, Map<String, Object> payload, String field, DefinitionSource source) {
        Boolean required = support.optionalBoolean(payload, "isRequired", field);
        PropertyCreateRequest request =
                support.valid(
                        new PropertyCreateRequest(
                                support.text(payload, "code", field),
                                support.text(payload, "name", field),
                                support.text(payload, "dataType", field),
                                required != null && required,
                                support.optionalBoolean(payload, "isIdentifier", field),
                                support.optionalText(payload, "unit", field),
                                support.optionalObject(payload, "constraints", field),
                                support.optionalText(payload, "sensitivity", field),
                                support.optionalText(payload, "temporalRole", field),
                                support.optionalText(payload, "description", field)),
                        field);
        PropertyResponse created =
                support.call(field, () -> propertyService.create(type.getId(), request, source));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("objectTypeId", type.getId());
        after.put("code", created.code());
        after.put("dataType", request.dataType());
        return new Written(
                created.id(),
                ChangeEntry.insert("property_definition", Map.of("id", created.id()), after));
    }

    /** 객체 매핑 하나. 켜진 매핑으로 만들어 그 표의 전체 다시 읽기를 요청한다(감시 작업이 첫 적재를 넣는다). */
    WrittenMapping objectMapping(
            ObjectType type, Map<String, Object> payload, String field, DefinitionSource source) {
        SourceResource resource =
                support.resource(
                        support.text(payload, "sourceId", field),
                        support.text(payload, "resourceKey", field),
                        field);
        ObjectMappingCreateRequest request =
                support.valid(
                        new ObjectMappingCreateRequest(
                                resource.getId(),
                                type.getId(),
                                support.texts(payload, "keyColumns", field),
                                null,
                                true),
                        field);
        ObjectMappingResponse created =
                support.call(field, () -> objectMappingService.create(request, source));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sourceResourceId", resource.getId());
        after.put("objectTypeId", type.getId());
        after.put("keyColumns", request.keyColumns());
        return new WrittenMapping(
                created.id(),
                resource.getId(),
                ChangeEntry.insert("object_source_mapping", Map.of("id", created.id()), after));
    }

    /**
     * 속성 매핑 하나. 원천 컬럼은 {@code {sourceId, resourceKey, column}}로 가리키고, 그 자원에서 이 객체 유형을 만드는 켜진 객체 매핑이
     * 부모다.
     */
    WrittenMapping propertyMapping(
            ObjectType type,
            PropertyDefinition property,
            Map<String, Object> sourceColumn,
            List<Map<String, Object>> transformSteps,
            String field) {
        String columnField = field + ".sourceColumn";
        SourceResource resource =
                support.resource(
                        support.text(sourceColumn, "sourceId", columnField),
                        support.text(sourceColumn, "resourceKey", columnField),
                        columnField);
        SourceColumn column =
                support.column(
                        resource,
                        support.text(sourceColumn, "column", columnField),
                        columnField + ".column");
        ObjectSourceMapping parent = support.activeObjectMapping(resource, type, columnField);
        PropertyMappingCreateRequest request =
                support.valid(
                        new PropertyMappingCreateRequest(
                                parent.getId(),
                                resource.getId(),
                                property.getId(),
                                column.getId(),
                                null,
                                transformSteps.stream().map(TransformStepPayload::from).toList()),
                        field);
        PropertyMappingWriteResponse created =
                support.call(field, () -> propertyMappingService.create(request));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sourceMappingId", parent.getId());
        after.put("propertyDefinitionId", property.getId());
        after.put("sourceColumnId", column.getId());
        return new WrittenMapping(
                created.id(),
                resource.getId(),
                ChangeEntry.insert("property_source_mapping", Map.of("id", created.id()), after));
    }

    record Written(long id, ChangeEntry change) {}

    record WrittenMapping(long id, long sourceResourceId, ChangeEntry change) {}
}
