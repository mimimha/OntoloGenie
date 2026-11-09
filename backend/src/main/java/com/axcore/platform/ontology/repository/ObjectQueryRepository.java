package com.axcore.platform.ontology.repository;

import com.axcore.platform.ontology.dto.ObjectSearchRequest.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Entity를 두지 않고 기존 JDBC로 접근하는 object_instance의 검색과 페이지 조회. */
@Repository
@RequiredArgsConstructor
public class ObjectQueryRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static final String OBJECTS = "from object_instance oi\n";

    // 수집 시의 표시 이름은 매핑 변경 후에도 남을 수 있어 제한 사용자에게는 읽을 수 있는 값으로 다시 만든다.
    private static final String DISPLAY_JOINS =
            """
            join object_type ot on ot.id = oi.object_type_id
            left join property_definition display_property on display_property.id = ot.display_property_id
            cross join lateral (
                select oi.object_type_id <> all(:maskedTypeIds) as can_view_restricted
            ) scope
            left join lateral (
                select jsonb_object_agg(raw_key.key, 'null'::jsonb) as masked_values
                from jsonb_each(oi.source_key) raw_key
                where not scope.can_view_restricted
                  and not coalesce((
                      select bool_and(pd.sensitivity = 'NORMAL')
                      from property_source_mapping pm
                      join source_column sc on sc.id = pm.source_column_id
                      join property_definition pd on pd.id = pm.property_definition_id
                      where pm.source_mapping_id = oi.source_mapping_id
                        and pd.object_type_id = oi.object_type_id
                        and sc.column_name = raw_key.key
                  ), false)
            ) source_mask on true
            cross join lateral (
                select case
                    when scope.can_view_restricted
                         or (oi.source_mapping_id is null
                             and display_property.sensitivity is distinct from 'RESTRICTED')
                    then oi.display_name
                    else left(coalesce(
                        case when display_property.sensitivity = 'NORMAL'
                            then nullif(btrim(oi.properties ->> display_property.code), '') end,
                        (select string_agg(name_key.value, ' / ' order by name_key.key)
                         from jsonb_each_text(oi.source_key) name_key
                         where not coalesce(jsonb_exists(source_mask.masked_values, name_key.key), false)
                           and nullif(btrim(name_key.value), '') is not null),
                        '#' || oi.id
                    ), 300)
                end as display_name
            ) shown
            """;

    private static final String FILTERS =
            """
            where oi.object_type_id in (:visibleTypeIds)
              and oi.status = :status
            """;

    private static final String SEARCH_CONDITION =
            """
              and (
                  shown.display_name ilike :pattern
                  or exists (
                      select 1 from property_definition identifier
                      where identifier.object_type_id = oi.object_type_id
                        and identifier.is_identifier
                        and (scope.can_view_restricted or identifier.sensitivity = 'NORMAL')
                        and (oi.properties ->> identifier.code) ilike :pattern
                  )
              )
            """;

    private static final String SELECT =
            """
            select oi.id, oi.object_type_id, ot.name as object_type_name, shown.display_name,
                   oi.properties::text as properties, oi.status, oi.source_mapping_id,
                   (oi.source_key || coalesce(source_mask.masked_values, '{}'::jsonb))::text as source_key,
                   oi.first_collected_at, oi.last_synced_at,
                   (select case when count(*) = 1 and min(pd.data_type) = 'DATETIME'
                                then min(pd.code) end
                    from property_definition pd
                    where pd.object_type_id = oi.object_type_id and pd.temporal_role = 'AS_OF') as as_of_code
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<ObjectRow> search(
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            Status status,
            String pattern,
            Pageable page) {
        String matches =
                OBJECTS + DISPLAY_JOINS + FILTERS + (pattern == null ? "" : SEARCH_CONDITION);
        List<ObjectRow> rows =
                displayQuery(
                                SELECT + matches + "order by oi.id limit :limit offset :offset",
                                visibleTypeIds,
                                maskedTypeIds,
                                status,
                                pattern)
                        .param("limit", page.getPageSize())
                        .param("offset", page.getOffset())
                        .query((rs, rowNum) -> row(rs))
                        .list();
        JdbcClient.StatementSpec count =
                pattern == null
                        ? scopedQuery(
                                "select count(*) " + OBJECTS + FILTERS, visibleTypeIds, status)
                        : displayQuery(
                                "select count(*) " + matches,
                                visibleTypeIds,
                                maskedTypeIds,
                                status,
                                pattern);
        long total = count.query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    private JdbcClient.StatementSpec displayQuery(
            String sql,
            Collection<Long> visibleTypeIds,
            Collection<Long> maskedTypeIds,
            Status status,
            String pattern) {
        JdbcClient.StatementSpec query =
                scopedQuery(sql, visibleTypeIds, status)
                        .param(
                                "maskedTypeIds",
                                new SqlArrayValue("bigint", maskedTypeIds.toArray()));
        return pattern == null ? query : query.param("pattern", pattern);
    }

    private JdbcClient.StatementSpec scopedQuery(
            String sql, Collection<Long> visibleTypeIds, Status status) {
        return jdbc.sql(sql).param("visibleTypeIds", visibleTypeIds).param("status", status.name());
    }

    private ObjectRow row(ResultSet rs) throws SQLException {
        String sourceKey = rs.getString("source_key");
        OffsetDateTime lastSyncedAt = rs.getObject("last_synced_at", OffsetDateTime.class);
        return new ObjectRow(
                rs.getLong("id"),
                rs.getLong("object_type_id"),
                rs.getString("object_type_name"),
                rs.getString("display_name"),
                json.readValue(rs.getString("properties"), JSON_OBJECT),
                Status.valueOf(rs.getString("status")),
                rs.getObject("source_mapping_id", Long.class),
                sourceKey == null ? null : json.readValue(sourceKey, JSON_OBJECT),
                rs.getObject("first_collected_at", OffsetDateTime.class).toInstant(),
                lastSyncedAt == null ? null : lastSyncedAt.toInstant(),
                rs.getString("as_of_code"));
    }

    public record ObjectRow(
            long id,
            long objectTypeId,
            String objectTypeName,
            String displayName,
            Map<String, Object> properties,
            Status status,
            Long sourceMappingId,
            Map<String, Object> sourceKey,
            Instant firstCollectedAt,
            Instant lastSyncedAt,
            String asOfCode) {}
}
