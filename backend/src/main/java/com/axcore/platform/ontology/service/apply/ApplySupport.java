package com.axcore.platform.ontology.service.apply;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.ontology.domain.ApplyErrorCode;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 반영기들이 같이 쓰는 것(Jira 189): payload 값 읽기, 요청 검증, 후보의 code를 DB ID로 바꾸기, 기존 서비스 오류를 반영 오류 코드로 바꾸기.
 *
 * <p>후보는 정의를 code로, 원천을 {@code sourceId}(data_source.code)·{@code
 * resourceKey}(source_resource.resource_key)로 가리킨다. 아직 없는 정의·원천을 가리키면 {@code
 * APPLY_REFERENCE_MISSING}이다(선행 후보를 먼저 승인해야 한다).
 */
@Component
@RequiredArgsConstructor
class ApplySupport {

    private final Validator validator;
    private final ObjectTypeRepository objectTypes;
    private final PropertyDefinitionRepository properties;
    private final DataSourceRepository dataSources;
    private final SourceResourceRepository resources;
    private final SourceColumnRepository columns;
    private final ObjectSourceMappingRepository objectMappings;

    // ---- payload 읽기 -------------------------------------------------------------------------

    String text(Map<String, Object> payload, String key, String field) {
        if (!(payload.get(key) instanceof String value) || value.isBlank()) {
            throw invalid(field + "." + key, "비어 있지 않은 문자열이어야 합니다.");
        }
        return value;
    }

    String optionalText(Map<String, Object> payload, String key, String field) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid(field + "." + key, "문자열이어야 합니다.");
        }
        return text;
    }

    Boolean optionalBoolean(Map<String, Object> payload, String key, String field) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean bool)) {
            throw invalid(field + "." + key, "true 또는 false여야 합니다.");
        }
        return bool;
    }

    List<String> optionalTexts(Map<String, Object> payload, String key, String field) {
        Object value = payload.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalid(field + "." + key, "문자열 목록이어야 합니다.");
        }
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String text) || text.isBlank()) {
                throw invalid(field + "." + key + "[" + i + "]", "비어 있지 않은 문자열이어야 합니다.");
            }
            texts.add(text);
        }
        return texts;
    }

    List<String> texts(Map<String, Object> payload, String key, String field) {
        List<String> texts = optionalTexts(payload, key, field);
        if (texts.isEmpty()) {
            throw invalid(field + "." + key, "하나 이상 지정해야 합니다.");
        }
        return texts;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> optionalObject(Map<String, Object> payload, String key, String field) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(field + "." + key, "JSON 객체여야 합니다.");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> optionalObjects(
            Map<String, Object> payload, String key, String field) {
        Object value = payload.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalid(field + "." + key, "JSON 객체 목록이어야 합니다.");
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw invalid(field + "." + key + "[" + i + "]", "JSON 객체여야 합니다.");
            }
            objects.add((Map<String, Object>) map);
        }
        return objects;
    }

    // ---- 검증·오류 ---------------------------------------------------------------------------

    /** 컨트롤러가 하던 Bean Validation을 반영 경로에서도 한다. 첫 오류(필드 이름순)를 {@code field.필드}로 돌려준다. */
    <T> T valid(T request, String field) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return request;
        }
        ConstraintViolation<T> first =
                violations.stream()
                        .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                        .orElseThrow();
        throw invalid(field + "." + first.getPropertyPath(), first.getMessage());
    }

    /**
     * 기존 등록 서비스(022·027·031·035·038)를 부르고, 그 서비스가 던진 오류를 반영 오류 코드로 바꾼다. 중복(409)은 {@code
     * APPLY_TARGET_EXISTS}, 없음(404)은 {@code APPLY_REFERENCE_MISSING}, 입력 오류(400·422)는 {@code
     * APPLY_PAYLOAD_INVALID}다. 예외를 다시 던지므로 트랜잭션은 롤백된다.
     */
    <T> T call(String field, Supplier<T> work) {
        try {
            return work.get();
        } catch (BusinessException e) {
            if (e.getErrorCode() instanceof ApplyErrorCode) {
                throw e;
            }
            Map<String, Object> details = new LinkedHashMap<>(e.getDetails());
            Object inner = details.get("field");
            details.put("field", inner == null ? field : field + "." + inner);
            ApplyErrorCode code =
                    e.getErrorCode() == CommonErrorCode.CONFLICT
                            ? ApplyErrorCode.APPLY_TARGET_EXISTS
                            : e.getErrorCode() == CommonErrorCode.RESOURCE_NOT_FOUND
                                    ? ApplyErrorCode.APPLY_REFERENCE_MISSING
                                    : ApplyErrorCode.APPLY_PAYLOAD_INVALID;
            throw new BusinessException(code, e.getMessage(), details);
        }
    }

    static BusinessException invalid(String field, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("reason", reason);
        return new BusinessException(ApplyErrorCode.APPLY_PAYLOAD_INVALID, details);
    }

    static BusinessException exists(String field, String code) {
        return new BusinessException(
                ApplyErrorCode.APPLY_TARGET_EXISTS, Map.of("field", field, "code", code));
    }

    static BusinessException missing(String field, String code, String requiredKind) {
        return new BusinessException(
                ApplyErrorCode.APPLY_REFERENCE_MISSING,
                Map.of("field", field, "code", code, "requiredKind", requiredKind));
    }

    // ---- code → ID -------------------------------------------------------------------------

    ObjectType objectType(String code, String field) {
        return objectTypes.findByCode(code).orElseThrow(() -> missing(field, code, "OBJECT_TYPE"));
    }

    PropertyDefinition property(ObjectType type, String code, String field) {
        return properties
                .findByObjectTypeIdAndCode(type.getId(), code)
                .orElseThrow(() -> missing(field, type.getCode() + "." + code, "PROPERTY"));
    }

    /** 원천 자원. 원천과 자원은 후보가 만들지 않으므로 없으면 원천 카탈로그(013·014)를 먼저 채워야 한다. */
    SourceResource resource(String sourceCode, String resourceKey, String field) {
        DataSource source =
                dataSources
                        .findByCode(sourceCode)
                        .orElseThrow(() -> missing(field + ".sourceId", sourceCode, "DATA_SOURCE"));
        return resources
                .findByDataSourceIdAndResourceKey(source.getId(), resourceKey)
                .orElseThrow(
                        () ->
                                missing(
                                        field + ".resourceKey",
                                        sourceCode + "/" + resourceKey,
                                        "SOURCE_RESOURCE"));
    }

    SourceColumn column(SourceResource resource, String columnName, String field) {
        return columns.findBySourceResourceIdAndColumnName(resource.getId(), columnName)
                .orElseThrow(
                        () ->
                                missing(
                                        field,
                                        resource.getResourceKey() + "." + columnName,
                                        "SOURCE_COLUMN"));
    }

    /** 원천 자원에서 그 객체 유형을 만드는 켜진 매핑. 속성 매핑의 부모다. */
    ObjectSourceMapping activeObjectMapping(
            SourceResource resource, ObjectType type, String field) {
        return objectMappings
                .findBySourceResourceIdAndActiveTrueOrderById(resource.getId())
                .stream()
                .filter(mapping -> mapping.getObjectTypeId().equals(type.getId()))
                .findFirst()
                .orElseThrow(
                        () ->
                                missing(
                                        field,
                                        type.getCode() + "@" + resource.getResourceKey(),
                                        "OBJECT_MAPPING"));
    }
}
