package com.axcore.platform.source.domain;

import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.ontology.domain.PropertyDataType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 원천 테이블 하나를 전체 읽기(Jira 197)할 때 쓰는 매핑 묶음. 읽기 전과 저장 직전에 한 번씩 만들어 비교한다. 같지 않으면 읽는 동안 매핑·속성·객체 유형이 바뀐
 * 것이라 저장하지 않는다. 그래서 버전(row_version)도 값에 넣는다.
 *
 * @param mappings 켜진 객체 원천 매핑. 비어 있으면 테이블을 읽지 않는다
 */
public record FullLoadPlan(
        long resourceId, String resourceKey, SyncStrategy strategy, List<MappingPlan> mappings) {

    public FullLoadPlan {
        mappings = List.copyOf(mappings);
    }

    /** SELECT할 컬럼. 키 컬럼을 먼저, 속성 컬럼을 뒤에 둔다. */
    public List<String> columns() {
        Set<String> columns = new LinkedHashSet<>(keyColumns());
        mappings.forEach(m -> m.properties().forEach(p -> columns.add(p.columnName())));
        return List.copyOf(columns);
    }

    /** 원천 키로 쓰는 컬럼. 이 컬럼은 키 문자열({@link SourceKeyText})로도 만든다. */
    public Set<String> keyColumns() {
        Set<String> columns = new LinkedHashSet<>();
        mappings.forEach(m -> columns.addAll(m.keyColumns()));
        return Set.copyOf(columns);
    }

    /**
     * @param displayPropertyCode 객체 유형의 대표 속성 코드. 없으면 null이고 표시 이름은 원천 키 값이다
     */
    public record MappingPlan(
            long mappingId,
            int rowVersion,
            long objectTypeId,
            int objectTypeVersion,
            List<String> keyColumns,
            String displayPropertyCode,
            List<PropertyPlan> properties) {

        public MappingPlan {
            keyColumns = List.copyOf(keyColumns);
            properties = List.copyOf(properties);
        }
    }

    public record PropertyPlan(
            long propertyMappingId,
            int rowVersion,
            String propertyCode,
            PropertyDataType dataType,
            int propertyVersion,
            String columnName,
            List<TransformStep> steps) {

        public PropertyPlan {
            steps = List.copyOf(steps);
        }
    }
}
