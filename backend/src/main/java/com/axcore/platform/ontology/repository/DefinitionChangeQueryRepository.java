package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.dto.DefinitionChangeResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 044 정의 변경 이력(Jira 187). 정의 테이블의 변경 전후(entity_change)에 그 변경을 남긴 실행(action_execution)의 실행자·시각과
 * Action 코드를 붙인다.
 *
 * <p>JdbcClient: 감사 두 표와 Action 정의를 잇고 jsonb 행 키(row_key의 id)로 거르는 조회라, 감사 이력 조회(096)와 같은 기준으로
 * 쓴다(조회 쿼리 기준 초안 2-2, S15P31C106-294). 조건은 SQL을 이어 붙이지 않고 모두 바인딩한다(빈 조건은 {@code is null}로 통과).
 */
@Repository
@RequiredArgsConstructor
public class DefinitionChangeQueryRepository {

    /** 044가 보여 주는 정의 테이블. 직접 편집(187)과 후보 승인 반영이 entity_change에 남기는 정의 테이블이다. */
    public static final List<String> DEFINITION_TABLES =
            List.of(
                    "object_type",
                    "property_definition",
                    "link_type",
                    "object_source_mapping",
                    "property_source_mapping",
                    "link_mapping");

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static final String FROM_WHERE =
            """
            from entity_change c
            join action_execution e on e.id = c.action_execution_id
            left join action_definition d on d.id = e.action_definition_id
            where c.table_name = any(:tables)
              and (cast(:tableName as varchar) is null or c.table_name = cast(:tableName as varchar))
              and (cast(:rowId as varchar) is null or c.row_key ->> 'id' = cast(:rowId as varchar))
              and (cast(:from as timestamptz) is null or e.requested_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null or e.requested_at < cast(:to as timestamptz))
            """;

    private static final String COUNT_SQL = "select count(*) " + FROM_WHERE;

    private static final String PAGE_SQL =
            """
            select c.id, c.action_execution_id, c.table_name, c.row_key::text as row_key,
                   c.change_kind, c.before_data::text as before_data,
                   c.after_data::text as after_data, e.actor_id, e.actor_type,
                   e.requested_at, d.code as action_code
            """
                    + FROM_WHERE
                    + """
                    order by e.requested_at desc, c.id desc
                    limit :limit offset :offset
                    """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<DefinitionChangeResponse> search(
            String tableName, Long rowId, Instant from, Instant to, Pageable pageable) {
        long total =
                bind(jdbc.sql(COUNT_SQL), tableName, rowId, from, to).query(Long.class).single();
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        List<DefinitionChangeResponse> rows =
                bind(jdbc.sql(PAGE_SQL), tableName, rowId, from, to)
                        .param("limit", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query(this::row)
                        .list();
        return new PageImpl<>(rows, pageable, total);
    }

    private static JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec spec, String tableName, Long rowId, Instant from, Instant to) {
        return spec.param("tables", DEFINITION_TABLES.toArray(String[]::new))
                .param("tableName", tableName, Types.VARCHAR)
                .param("rowId", rowId == null ? null : rowId.toString(), Types.VARCHAR)
                .param(
                        "from",
                        from == null ? null : from.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param(
                        "to",
                        to == null ? null : to.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private DefinitionChangeResponse row(ResultSet rs, int rowNum) throws SQLException {
        Timestamp requestedAt = rs.getTimestamp("requested_at");
        return new DefinitionChangeResponse(
                rs.getLong("id"),
                rs.getLong("action_execution_id"),
                rs.getString("table_name"),
                object(rs.getString("row_key")),
                rs.getString("change_kind"),
                object(rs.getString("before_data")),
                object(rs.getString("after_data")),
                rs.getObject("actor_id", Long.class),
                rs.getString("actor_type"),
                requestedAt == null ? null : requestedAt.toInstant(),
                rs.getString("action_code"));
    }

    private Map<String, Object> object(String value) {
        return value == null ? null : json.readValue(value, JSON_OBJECT);
    }
}
