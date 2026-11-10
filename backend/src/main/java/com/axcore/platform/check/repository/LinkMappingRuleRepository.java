package com.axcore.platform.check.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 승인된 연결 규칙 개정본과 그 비교 조건을 읽는다(S15P31C106-190). 규칙이 읽는 원천 자원은 매핑 도메인의 JPA Repository({@code
 * ObjectSourceMappingRepository})로 읽는다.
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): 다른 도메인 테이블을 한 번의 SQL로 join하는 읽기 전용 조회다.
 * 규칙 개정본과 관계 유형(관계 수), 비교 조건과 양쪽 속성·객체 유형 코드(엔진이 찾는 이름 {@code 객체유형코드.속성코드})를 각각 한 문장으로 읽는다. 쓰지 않는다.
 * 규칙 ID만 고르는 조회는 JPA({@code LinkMappingRepository})를 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class LinkMappingRuleRepository {

    private final JdbcClient jdbc;

    public Optional<RuleRow> findRule(long linkMappingId) {
        return jdbc.sql(
                        """
                        select m.id, m.status, t.cardinality, t.source_type_id, t.target_type_id,
                               st.code as source_type_code, tt.code as target_type_code,
                               m.source_filter::text as source_filter,
                               m.target_filter::text as target_filter
                        from link_mapping m
                        join link_type t on t.id = m.link_type_id
                        join object_type st on st.id = t.source_type_id
                        join object_type tt on tt.id = t.target_type_id
                        where m.id = :id
                        """)
                .param("id", linkMappingId)
                .query(
                        (rs, rowNum) ->
                                new RuleRow(
                                        rs.getLong("id"),
                                        rs.getString("status"),
                                        rs.getString("cardinality"),
                                        rs.getLong("source_type_id"),
                                        rs.getLong("target_type_id"),
                                        rs.getString("source_type_code"),
                                        rs.getString("target_type_code"),
                                        rs.getString("source_filter"),
                                        rs.getString("target_filter")))
                .optional();
    }

    /** 비교 조건. 속성은 판정 엔진이 원천 행 값에서 찾는 이름({@code 객체유형코드.속성코드})으로 돌려준다. */
    public List<ConditionRow> findConditions(long linkMappingId) {
        return jdbc.sql(
                        """
                        select c.sequence_no, c.operator,
                               sot.code || '.' || sp.code as source_property,
                               tot.code || '.' || tp.code as target_property,
                               c.source_transforms::text as source_transforms,
                               c.target_transforms::text as target_transforms
                        from link_mapping_condition c
                        join property_definition sp on sp.id = c.source_property_id
                        join object_type sot on sot.id = sp.object_type_id
                        join property_definition tp on tp.id = c.target_property_id
                        join object_type tot on tot.id = tp.object_type_id
                        where c.link_mapping_id = :id
                        order by c.sequence_no
                        """)
                .param("id", linkMappingId)
                .query(
                        (rs, rowNum) ->
                                new ConditionRow(
                                        rs.getInt("sequence_no"),
                                        rs.getString("operator"),
                                        rs.getString("source_property"),
                                        rs.getString("target_property"),
                                        rs.getString("source_transforms"),
                                        rs.getString("target_transforms")))
                .list();
    }

    public record RuleRow(
            long id,
            String status,
            String cardinality,
            long sourceTypeId,
            long targetTypeId,
            String sourceTypeCode,
            String targetTypeCode,
            String sourceFilter,
            String targetFilter) {}

    public record ConditionRow(
            int sequenceNo,
            String operator,
            String sourceProperty,
            String targetProperty,
            String sourceTransforms,
            String targetTransforms) {}
}
