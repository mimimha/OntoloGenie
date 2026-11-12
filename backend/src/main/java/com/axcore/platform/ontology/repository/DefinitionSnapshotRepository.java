package com.axcore.platform.ontology.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정의 테이블 한 행을 DB 컬럼 그대로(snake_case) 읽는다. 정의 직접 편집의 감사 기록 변경 전후(entity_change)에 쓴다(Jira 187).
 *
 * <p>JdbcClient: 다섯 정의 테이블의 행을 Entity 필드가 아니라 DB 컬럼 이름 그대로 남기려고 {@code to_jsonb}로 읽는다. 후보 승인이 남기는
 * 변경 전후도 테이블·컬럼 이름 기준이라 044에서 같은 모양으로 보인다. 테이블 이름은 바인딩 변수로 넘길 수 없어 테이블마다 SQL을 따로 둔다.
 */
@Repository
@RequiredArgsConstructor
public class DefinitionSnapshotRepository {

    private static final TypeReference<LinkedHashMap<String, Object>> ROW =
            new TypeReference<>() {};

    private static final Map<String, String> SQL =
            Map.of(
                    "object_type",
                    "select to_jsonb(t)::text from object_type t where t.id = :id",
                    "property_definition",
                    "select to_jsonb(t)::text from property_definition t where t.id = :id",
                    "link_type",
                    "select to_jsonb(t)::text from link_type t where t.id = :id",
                    "object_source_mapping",
                    "select to_jsonb(t)::text from object_source_mapping t where t.id = :id",
                    "property_source_mapping",
                    "select to_jsonb(t)::text from property_source_mapping t where t.id = :id");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /** 행이 없으면 빈 값이다. 정의 테이블이 아니면 IllegalArgumentException이다. */
    public Optional<Map<String, Object>> find(String table, long id) {
        String sql = SQL.get(table);
        if (sql == null) {
            throw new IllegalArgumentException("정의 테이블이 아닙니다: " + table);
        }
        return jdbc.sql(sql)
                .param("id", id)
                .query(String.class)
                .optional()
                .map(text -> json.readValue(text, ROW));
    }
}
