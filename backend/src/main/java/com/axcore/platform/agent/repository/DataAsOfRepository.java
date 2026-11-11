package com.axcore.platform.agent.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Agent 답변의 기준 시점(정책 문서 4.1·4.2, 145)을 구할 원천 표의 확인 정보를 읽는다. 객체·관계·매핑·원천 표·CDC 소비 상태·수집 기록은 각각 다른
 * 도메인의 테이블이라, 한 번의 SQL로 join해 읽기만 한다(DB 규칙의 "다른 도메인 테이블을 한 번의 SQL로 join하는 조회").
 */
@Repository
@RequiredArgsConstructor
public class DataAsOfRepository {
    /** 결과 객체에서 원천 표까지 이어 표마다 확인 정보를 붙인다. %s에는 객체 id를 고르는 SQL이 들어간다. */
    private static final String TABLES_OF =
            """
            with objects as (%s),
                 mapped as (
                    select osm.source_resource_id, bool_and(osm.is_active) as mapping_active
                      from objects o
                      join object_instance oi on oi.id = o.id
                      join object_source_mapping osm on osm.id = oi.source_mapping_id
                     group by osm.source_resource_id)
            select sr.id as source_resource_id, sr.sync_strategy, sr.loaded_lsn, m.mapping_active,
                   co.data_source_id is not null as has_cdc_offset,
                   co.last_message_at, co.healthy_since,
                   case when sr.sync_strategy = 'FULL_COMPARE' then
                        (select max(run.started_at)
                           from sync_run_resource run
                          where run.source_resource_id = sr.id
                            and run.mode = 'FULL'
                            and run.status = 'SUCCEEDED')
                   end as last_full_started_at
              from mapped m
              join source_resource sr on sr.id = m.source_resource_id
              left join lateral (
                    select c.data_source_id, c.last_message_at, c.healthy_since
                      from cdc_offset c
                     where c.data_source_id = sr.data_source_id
                     order by c.updated_at desc
                     limit 1) co on true
             order by sr.id
            """;

    private final JdbcClient jdbc;

    /**
     * 원천 표 하나의 확인 정보.
     *
     * @param syncStrategy CDC 또는 FULL_COMPARE
     * @param loadedLsn CDC 표를 마지막으로 전체 읽기한 원천 LSN. NULL이면 첫 적재 전이거나 다시 읽기를 기다리거나 읽는 중이다
     * @param mappingActive 결과 객체를 이 표에서 가져오는 매핑이 모두 켜져 있는가. 꺼진 매핑은 더 수집하지 않아 표의 확인 시각이 그 객체에 맞지 않는다
     * @param hasCdcOffset 그 원천의 CDC 소비 상태 행이 있는가. 없으면 소비자가 아직 시작하지 않았다
     * @param lastMessageAt 그 원천에서 마지막으로 받은 변경·heartbeat 메시지 시각(플랫폼 시계)
     * @param healthySince 수집이 정상이 된 시각. NULL이면 지금 비정상이거나 복구가 필요하다
     * @param lastFullStartedAt 전체 비교 수집 표의 마지막 성공한 전체 읽기가 시작된 시각. 전체 읽기는 시작할 때의 스냅샷 기준이다. CDC 표는
     *     NULL이다
     */
    public record SourceTable(
            long sourceResourceId,
            String syncStrategy,
            Long loadedLsn,
            boolean mappingActive,
            boolean hasCdcOffset,
            Instant lastMessageAt,
            Instant healthySince,
            Instant lastFullStartedAt) {}

    /** 객체들이 수집된 원천 표. 원천 매핑이 없는 객체(플랫폼이 만든 객체)와 없는 객체는 표가 없어 빠진다. */
    public List<SourceTable> tablesOfObjects(Collection<Long> objectIds) {
        if (objectIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        TABLES_OF.formatted(
                                "select oi.id from object_instance oi where oi.id in (:objectIds)"))
                .param("objectIds", objectIds)
                .query(DataAsOfRepository::sourceTable)
                .list();
    }

    /** 관계 한 건의 양 끝 객체가 수집된 원천 표. 관계가 없으면 빈 목록이다. */
    public List<SourceTable> tablesOfLink(long linkId) {
        return jdbc.sql(
                        TABLES_OF.formatted(
                                """
                                select l.source_object_id as id from object_link l where l.id = :linkId
                                union
                                select l.target_object_id from object_link l where l.id = :linkId
                                """))
                .param("linkId", linkId)
                .query(DataAsOfRepository::sourceTable)
                .list();
    }

    private static SourceTable sourceTable(ResultSet rs, int rowNum) throws SQLException {
        return new SourceTable(
                rs.getLong("source_resource_id"),
                rs.getString("sync_strategy"),
                rs.getObject("loaded_lsn", Long.class),
                rs.getBoolean("mapping_active"),
                rs.getBoolean("has_cdc_offset"),
                instant(rs, "last_message_at"),
                instant(rs, "healthy_since"),
                instant(rs, "last_full_started_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
