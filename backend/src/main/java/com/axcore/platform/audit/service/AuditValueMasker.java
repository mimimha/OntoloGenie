package com.axcore.platform.audit.service;

import com.axcore.platform.action.dto.ActionExecutionDetailResponse.EntityChangeResponse;
import com.axcore.platform.action.repository.ActionDefinitionRepository;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Path;
import com.axcore.platform.audit.service.RecordedPropertyPaths.Section;
import com.axcore.platform.auth.service.DataAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 실행 이력에서 사용자가 볼 수 없는 민감(RESTRICTED) 속성 값을 가린다(API 080·096, S15P31C106-205). 077 객체 상세와 같은 방식이다. 키는
 * 남기고 값만 null로 바꾸며, 가린 속성 코드를 restrictedPropertyCodes에 담는다. 가릴 자리는 기록을 남기는 쪽이 {@link
 * RecordedPropertyPaths}로 알린다.
 */
@Component
class AuditValueMasker {

    private static final String EACH = "*";

    private final Map<String, List<Path>> inputPaths;
    private final Map<String, List<Path>> changePaths;
    private final ActionDefinitionRepository definitions;

    AuditValueMasker(List<RecordedPropertyPaths> sources, ActionDefinitionRepository definitions) {
        List<Path> paths =
                sources.stream()
                        .flatMap(source -> source.recordedPropertyPaths().stream())
                        .toList();
        this.inputPaths = byOwner(paths, Section.INPUT);
        this.changePaths = byOwner(paths, Section.CHANGE);
        this.definitions = definitions;
    }

    List<AuditExecutionResponse> mask(List<AuditExecutionResponse> rows, DataAccess access) {
        Map<Long, String> handlerKeys = handlerKeysOf(rows);
        return rows.stream()
                .map(row -> mask(row, handlerKeys.get(row.actionDefinitionId()), access))
                .toList();
    }

    private AuditExecutionResponse mask(
            AuditExecutionResponse row, String handlerKey, DataAccess access) {
        Set<String> masked = new TreeSet<>();
        Map<String, Object> input =
                blank(
                        row.input(),
                        handlerKey == null ? List.of() : paths(inputPaths, handlerKey),
                        access,
                        masked);
        List<EntityChangeResponse> changes =
                row.entityChanges().stream().map(change -> mask(change, access, masked)).toList();
        return masked.isEmpty() ? row : row.withMaskedValues(input, changes, List.copyOf(masked));
    }

    private EntityChangeResponse mask(
            EntityChangeResponse change, DataAccess access, Set<String> masked) {
        List<Path> paths = paths(changePaths, change.tableName());
        if (paths.isEmpty()) {
            return change;
        }
        return new EntityChangeResponse(
                change.tableName(),
                change.rowKey(),
                change.changeKind(),
                blank(change.beforeData(), paths, access, masked),
                blank(change.afterData(), paths, access, masked));
    }

    /** 원래 값은 바꾸지 않는다. 가릴 자리가 없으면 받은 값을 그대로 돌려준다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> blank(
            Map<String, Object> data, List<Path> paths, DataAccess access, Set<String> masked) {
        List<Path> hidden =
                paths.stream()
                        .filter(
                                path ->
                                        access.hidesValue(
                                                path.objectTypeCode(), path.propertyCode()))
                        .toList();
        if (data == null || hidden.isEmpty()) {
            return data;
        }
        Map<String, Object> copy = (Map<String, Object>) mutableCopy(data);
        for (Path path : hidden) {
            if (blank(copy, path.keys(), 0)) {
                masked.add(path.propertyCode());
            }
        }
        return copy;
    }

    /** 경로 끝의 키가 있으면 그 값을 null로 바꾸고 true를 돌려준다. */
    @SuppressWarnings("unchecked")
    private static boolean blank(Object node, List<String> keys, int depth) {
        String key = keys.get(depth);
        if (EACH.equals(key)) {
            if (!(node instanceof List<?> items)) {
                return false;
            }
            boolean found = false;
            for (Object item : items) {
                found |= blank(item, keys, depth + 1);
            }
            return found;
        }
        if (!(node instanceof Map<?, ?> map) || !map.containsKey(key)) {
            return false;
        }
        if (depth == keys.size() - 1) {
            ((Map<String, Object>) map).put(key, null);
            return true;
        }
        return blank(map.get(key), keys, depth + 1);
    }

    private static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, mutableCopy(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(mutableCopy(item)));
            return copy;
        }
        return value;
    }

    private Map<Long, String> handlerKeysOf(List<AuditExecutionResponse> rows) {
        Set<Long> ids =
                rows.stream()
                        .map(AuditExecutionResponse::actionDefinitionId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (ids.isEmpty() || inputPaths.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> keys = new HashMap<>();
        definitions.findAllById(ids).stream()
                .filter(definition -> definition.getHandlerKey() != null)
                .forEach(definition -> keys.put(definition.getId(), definition.getHandlerKey()));
        return keys;
    }

    private static List<Path> paths(Map<String, List<Path>> paths, String owner) {
        return paths.getOrDefault(owner, List.of());
    }

    private static Map<String, List<Path>> byOwner(List<Path> paths, Section section) {
        return paths.stream()
                .filter(path -> path.section() == section)
                .collect(Collectors.groupingBy(Path::owner));
    }
}
