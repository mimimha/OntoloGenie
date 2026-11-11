package com.axcore.platform.source.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 수집 실행 조회(API 074·075, S15P31C106-219). JdbcClient: Entity가 없는 작업 표(sync_run, sync_run_resource)와
 * CDC 소비 상태(cdc_offset)를 읽고, 조건에 따라 SQL 모양이 바뀐다. 테이블별 기록은 테이블의 수집
 * 방식(source_resource.sync_strategy)을 join해 함께 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class SyncRunQueryRepository {

    private static final String RUN_COLUMNS =
            """
            select s.id, s.data_source_id, s.trigger_kind, s.status, s.requested_by, s.requested_at,
                   s.started_at, s.finished_at, s.error_message
            from sync_run s
            """;

    private final JdbcClient jdbc;

    /**
     * 원천의 수집 실행. 접수 시각이 늦은 순서다. triggerKind를 주지 않으면 CDC 묶음은 뺀다(수가 많다). 기간은 requestedAt 기준이며 from은
     * 포함, to는 포함하지 않는다.
     */
    public List<Run> search(long dataSourceId, Filter filter, long offset, int limit) {
        Where where = where(dataSourceId, filter);
        Map<String, Object> params = new HashMap<>(where.params());
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(
                        RUN_COLUMNS
                                + where.sql()
                                + " order by s.requested_at desc, s.id desc limit :limit offset :offset")
                .params(params)
                .query((rs, row) -> run(rs))
                .list();
    }

    public long count(long dataSourceId, Filter filter) {
        Where where = where(dataSourceId, filter);
        return jdbc.sql("select count(*) from sync_run s" + where.sql())
                .params(where.params())
                .query(Long.class)
                .single();
    }

    public Optional<Run> find(long syncRunId) {
        return jdbc.sql(RUN_COLUMNS + " where s.id = :id")
                .param("id", syncRunId)
                .query((rs, row) -> run(rs))
                .optional();
    }

    /**
     * 실행들의 테이블별 기록. 실행 ID, 원천 테이블 ID 순서다. 테이블 행의 시작 시각은 끝날 때 채우므로, 그 전에는 실행의 시작 시각을 쓴다(073 접수 응답과
     * 같다).
     */
    public List<Resource> findResources(Collection<Long> syncRunIds) {
        if (syncRunIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        select r.sync_run_id, r.data_source_id, r.source_resource_id, sr.sync_strategy,
                               r.mode, r.status, r.from_lsn, r.to_lsn, r.rows_read, r.rows_upserted,
                               r.rows_deleted, coalesce(r.started_at, s.started_at) as started_at,
                               r.finished_at, r.error_message
                        from sync_run_resource r
                        join sync_run s on s.id = r.sync_run_id
                        join source_resource sr on sr.id = r.source_resource_id
                        where r.sync_run_id = any(:ids)
                        order by r.sync_run_id, r.source_resource_id
                        """)
                .param("ids", syncRunIds.toArray(Long[]::new))
                .query((rs, row) -> resource(rs))
                .list();
    }

    /**
     * 이 원천에서 CDC로 마지막 반영한 원천 트랜잭션의 원천 커밋 시각(cdc_offset.last_commit_at, 원천 서버 시계). updated_at은
     * heartbeat·복구 표시 때도 바뀌어 반영 시각으로 쓰지 않는다. CDC 소비 기록이 없거나 아직 반영한 트랜잭션이 없으면 비어 있다.
     */
    public Optional<Instant> findCdcLastAppliedAt(long dataSourceId) {
        return jdbc.sql("select max(last_commit_at) from cdc_offset where data_source_id = :source")
                .param("source", dataSourceId)
                .query((rs, row) -> instant(rs.getTimestamp(1)))
                .optional();
    }

    private static Where where(long dataSourceId, Filter filter) {
        StringBuilder sql = new StringBuilder(" where s.data_source_id = :source");
        Map<String, Object> params = new HashMap<>();
        params.put("source", dataSourceId);
        if (filter.triggerKind() == null) {
            sql.append(" and s.trigger_kind <> 'CDC'");
        } else {
            sql.append(" and s.trigger_kind = :triggerKind");
            params.put("triggerKind", filter.triggerKind());
        }
        if (filter.status() != null) {
            sql.append(" and s.status = :status");
            params.put("status", filter.status());
        }
        if (filter.from() != null) {
            sql.append(" and s.requested_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" and s.requested_at < :to");
            params.put("to", Timestamp.from(filter.to()));
        }
        return new Where(sql.toString(), params);
    }

    private static Run run(ResultSet rs) throws SQLException {
        return new Run(
                rs.getLong("id"),
                rs.getLong("data_source_id"),
                rs.getString("trigger_kind"),
                rs.getString("status"),
                rs.getObject("requested_by", Long.class),
                instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("error_message"));
    }

    private static Resource resource(ResultSet rs) throws SQLException {
        return new Resource(
                rs.getLong("sync_run_id"),
                rs.getLong("data_source_id"),
                rs.getLong("source_resource_id"),
                rs.getString("sync_strategy"),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getObject("from_lsn", Long.class),
                rs.getObject("to_lsn", Long.class),
                rs.getInt("rows_read"),
                rs.getInt("rows_upserted"),
                rs.getInt("rows_deleted"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("error_message"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 코드가 만든 조건 조각과 그 파라미터. 요청 값은 모두 바인딩한다. */
    private record Where(String sql, Map<String, Object> params) {}

    /** 조회 조건. 비어 있는 조건으로는 거르지 않는다(triggerKind는 비면 CDC 제외). */
    public record Filter(String status, String triggerKind, Instant from, Instant to) {}

    public record Run(
            long id,
            long dataSourceId,
            String triggerKind,
            String status,
            Long requestedBy,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage) {}

    public record Resource(
            long syncRunId,
            long dataSourceId,
            long sourceResourceId,
            String syncStrategy,
            String mode,
            String status,
            Long fromLsn,
            Long toLsn,
            int rowsRead,
            int rowsUpserted,
            int rowsDeleted,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage) {}
}
