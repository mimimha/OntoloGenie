package com.axcore.platform.source.service;

import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.mapping.domain.ObjectSourceMapping;
import com.axcore.platform.mapping.domain.PropertySourceMapping;
import com.axcore.platform.mapping.repository.ObjectSourceMappingRepository;
import com.axcore.platform.mapping.repository.PropertySourceMappingRepository;
import com.axcore.platform.ontology.domain.ObjectType;
import com.axcore.platform.ontology.domain.PropertyDefinition;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import com.axcore.platform.ontology.repository.PropertyDefinitionRepository;
import com.axcore.platform.source.domain.FullLoadPlan;
import com.axcore.platform.source.domain.FullLoadPlan.MappingPlan;
import com.axcore.platform.source.domain.FullLoadPlan.PropertyPlan;
import com.axcore.platform.source.domain.SourceColumn;
import com.axcore.platform.source.domain.SourceResource;
import com.axcore.platform.source.repository.SourceColumnRepository;
import com.axcore.platform.source.repository.SourceResourceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원천 테이블의 켜진 매핑으로 전체 읽기 계획({@link FullLoadPlan})을 만든다(Jira 197). 지금 적재가 지원하지 않는 매핑(행 조건, 문서 경로)이나
 * 원천에서 없어진 컬럼을 가리키는 매핑이 있으면 작업을 실패시킨다. 일부만 적재하면 빠진 속성이 조용히 비기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class FullLoadPlanner {

    private final SourceResourceRepository resourceRepository;
    private final ObjectSourceMappingRepository mappingRepository;
    private final PropertySourceMappingRepository propertyMappingRepository;
    private final SourceColumnRepository columnRepository;
    private final PropertyDefinitionRepository propertyRepository;
    private final ObjectTypeRepository objectTypeRepository;

    @Transactional(readOnly = true)
    public FullLoadPlan plan(long resourceId) {
        SourceResource resource =
                resourceRepository
                        .findById(resourceId)
                        .orElseThrow(
                                () -> new IllegalStateException("원천 테이블이 없습니다: " + resourceId));
        if (!resource.isReadable()) {
            throw new QueuedJobFailure(
                    "SYNC 계정이 읽을 수 없는 원천 테이블입니다: %s".formatted(resource.getResourceKey()));
        }
        List<ObjectSourceMapping> mappings =
                mappingRepository.findBySourceResourceIdAndActiveTrueOrderById(resourceId);
        if (mappings.isEmpty()) {
            return new FullLoadPlan(
                    resourceId, resource.getResourceKey(), resource.getSyncStrategy(), List.of());
        }
        List<PropertySourceMapping> propertyMappings =
                propertyMappingRepository.findBySourceMappingIdInOrderById(
                        mappings.stream().map(ObjectSourceMapping::getId).toList());
        Map<Long, SourceColumn> columns =
                byId(
                        columnRepository.findAllById(
                                propertyMappings.stream()
                                        .map(PropertySourceMapping::getSourceColumnId)
                                        .filter(id -> id != null)
                                        .collect(Collectors.toSet())),
                        SourceColumn::getId);
        Map<Long, ObjectType> objectTypes =
                byId(
                        objectTypeRepository.findAllById(
                                mappings.stream()
                                        .map(ObjectSourceMapping::getObjectTypeId)
                                        .collect(Collectors.toSet())),
                        ObjectType::getId);
        Set<Long> propertyIds = new HashSet<>();
        propertyMappings.forEach(p -> propertyIds.add(p.getPropertyDefinitionId()));
        objectTypes.values().stream()
                .map(ObjectType::getDisplayPropertyId)
                .filter(id -> id != null)
                .forEach(propertyIds::add);
        Map<Long, PropertyDefinition> properties =
                byId(propertyRepository.findAllById(propertyIds), PropertyDefinition::getId);

        List<MappingPlan> plans = new ArrayList<>();
        for (ObjectSourceMapping mapping : mappings) {
            if (mapping.getRowFilter() != null && !mapping.getRowFilter().isEmpty()) {
                throw new QueuedJobFailure(
                        "행 조건이 있는 매핑(%d)은 아직 적재할 수 없습니다.".formatted(mapping.getId()));
            }
            ObjectType type = objectTypes.get(mapping.getObjectTypeId());
            List<PropertyPlan> propertyPlans = new ArrayList<>();
            for (PropertySourceMapping propertyMapping : propertyMappings) {
                if (!propertyMapping.getSourceMappingId().equals(mapping.getId())) {
                    continue;
                }
                propertyPlans.add(
                        propertyPlan(
                                resource,
                                propertyMapping,
                                columns.get(propertyMapping.getSourceColumnId()),
                                properties.get(propertyMapping.getPropertyDefinitionId())));
            }
            PropertyDefinition display =
                    type.getDisplayPropertyId() == null
                            ? null
                            : properties.get(type.getDisplayPropertyId());
            plans.add(
                    new MappingPlan(
                            mapping.getId(),
                            mapping.getRowVersion(),
                            type.getId(),
                            type.getRowVersion(),
                            mapping.getKeyColumns(),
                            display == null ? null : display.getCode(),
                            propertyPlans));
        }
        return new FullLoadPlan(
                resourceId, resource.getResourceKey(), resource.getSyncStrategy(), plans);
    }

    private static PropertyPlan propertyPlan(
            SourceResource resource,
            PropertySourceMapping mapping,
            SourceColumn column,
            PropertyDefinition property) {
        if (column == null) {
            throw new QueuedJobFailure(
                    "표 컬럼이 아닌 속성 매핑(%d)은 아직 적재할 수 없습니다.".formatted(mapping.getId()));
        }
        if (!column.isPresent()) {
            throw new QueuedJobFailure(
                    "속성 매핑(%d)이 가리키는 컬럼 %s가 원천 테이블 %s에 없습니다. 매핑을 고친 뒤 다시 수집해 주세요."
                            .formatted(
                                    mapping.getId(),
                                    column.getColumnName(),
                                    resource.getResourceKey()));
        }
        return new PropertyPlan(
                mapping.getId(),
                mapping.getRowVersion(),
                property.getId(),
                property.getCode(),
                property.getDataType(),
                property.getRowVersion(),
                column.getColumnName(),
                steps(mapping.getTransformSteps()));
    }

    // 저장할 때 TransformStepsValidator로 확인한 값이다. 그래도 형식이 깨졌으면 IllegalArgumentException이 그대로 난다.
    private static List<TransformStep> steps(List<Map<String, Object>> stored) {
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .map(
                        step ->
                                new TransformStep(
                                        TransformOperation.valueOf((String) step.get("op")),
                                        step.get("arg")))
                .toList();
    }

    private static <T> Map<Long, T> byId(Iterable<T> values, Function<T, Long> id) {
        Map<Long, T> map = new HashMap<>();
        values.forEach(value -> map.put(id.apply(value), value));
        return map;
    }
}
