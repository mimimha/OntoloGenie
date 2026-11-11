package com.axcore.platform.source.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관리자 수동 다시 읽기(API 073, S15P31C106-216·217)의 접수. JdbcClient: Entity가 없는 작업 표(sync_run,
 * sync_run_resource)에 조건부 insert·잠금을 하고, 대상 검사에 매핑(object_source_mapping)을 한 SQL로 join한다.
 */
@Repository
@RequiredArgsConstructor
public class SyncRunRequestRepository {

    private final JdbcClient jdbc;

    /** 켜진 매핑이 있는 이 원천의 테이블. ID 순서다. */
    public List<Long> findMappedResources(long dataSourceId) {
        return jdbc.sql(
                        """
                        select r.id from source_resource r
                        where r.data_source_id = :source
                          and exists (select 1 from object_source_mapping m
                                      where m.source_resource_id = r.id and m.is_active)
                        order by r.id
                        """)
                .param("source", dataSourceId)
                .query(Long.class)
                .list();
    }

    /** 요청한 ID 중 이 원천에 속한 테이블과 켜진 매핑 여부. 다른 원천의 테이블이나 없는 ID는 나오지 않는다. */
    public List<RequestedResource> findRequestedResources(long dataSourceId, List<Long> ids) {
        return jdbc.sql(
                        """
                        select r.id,
                               exists (select 1 from object_source_mapping m
                                       where m.source_resource_id = r.id and m.is_active) as mapped
                        from source_resource r
                        where r.data_source_id = :source and r.id = any(:ids)
                        """)
                .param("source", dataSourceId)
                .param("ids", ids.toArray(Long[]::new))
                .query(
                        (rs, row) ->
                                new RequestedResource(rs.getLong("id"), rs.getBoolean("mapped")))
                .list();
    }

    /**
     * 원천 접속 정보가 바뀌어 커넥터를 맞추는 중(cdc_offset.recovery_reason = SOURCE_CHANGED)인 원천의 CDC 테이블. 재연결(API
     * cdc/reconnected) 때 모두 다시 읽기 표시가 붙으므로 그 전에는 읽지 않는다. 감시 작업(CdcReloadRepository)과 같은 조건이다.
     */
    public List<Long> findWaitingReconnect(List<Long> ids) {
        return jdbc.sql(
                        """
                        select r.id from source_resource r
                        where r.id = any(:ids) and r.sync_strategy = 'CDC'
                          and exists (select 1 from cdc_offset o
                                      where o.data_source_id = r.data_source_id
                                        and o.recovery_reason = 'SOURCE_CHANGED')
                        order by r.id
                        """)
                .param("ids", ids.toArray(Long[]::new))
                .query(Long.class)
                .list();
    }

    /**
     * 이 테이블의 대기·실행 중인 테이블 행과 그 실행의 상태. 조건은 대기·실행 중 유일 조건(uq_sync_run_resource_active)과 같다. 조건이 다르면
     * 조회는 없다고 하는데 넣기는 충돌해 끝없이 다시 시도하게 된다. 테이블 행을 잠가, 배치가 끝내는 중이면 끝날 때까지 기다린 뒤 다시 판단한다.
     */
    public Optional<ActiveRun> findActiveRunForUpdate(long resourceId) {
        return jdbc.sql(
                        """
                        select r.sync_run_id, s.status from sync_run_resource r
                        join sync_run s on s.id = r.sync_run_id
                        where r.source_resource_id = :resource and r.status in ('QUEUED', 'RUNNING')
                        for update of r
                        """)
                .param("resource", resourceId)
                .query((rs, row) -> new ActiveRun(rs.getLong(1), rs.getString(2)))
                .optional();
    }

    /** trigger_kind MANUAL의 빈 실행을 QUEUED로 넣는다. */
    public long insertManualRun(long dataSourceId, long requestedBy, Instant requestedAt) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, requested_by, requested_at)
                        values (:source, 'MANUAL', :requestedBy, :requestedAt)
                        returning id
                        """)
                .param("source", dataSourceId)
                .param("requestedBy", requestedBy)
                .param("requestedAt", Timestamp.from(requestedAt))
                .query(Long.class)
                .single();
    }

    /**
     * 테이블 하나를 FULL·QUEUED로 실행에 넣는다. 다른 실행이 먼저 넣었으면(uq_sync_run_resource_active) 넣지 않고 false를 돌려준다.
     */
    public boolean insertFullResource(long syncRunId, long dataSourceId, long resourceId) {
        return jdbc.sql(
                                """
                                insert into sync_run_resource
                                    (sync_run_id, data_source_id, source_resource_id, mode)
                                values (:run, :source, :resource, 'FULL')
                                on conflict (source_resource_id) where status in ('QUEUED', 'RUNNING')
                                do nothing
                                """)
                        .param("run", syncRunId)
                        .param("source", dataSourceId)
                        .param("resource", resourceId)
                        .update()
                == 1;
    }

    /** 모든 테이블이 다른 실행에 합류해 비게 된 이번 요청의 실행을 지운다. 같은 트랜잭션에서 방금 넣은 행이라 다른 곳에서 본 적이 없다. */
    public void deleteRun(long syncRunId) {
        jdbc.sql("delete from sync_run where id = :id").param("id", syncRunId).update();
    }

    /** 테이블별 수집 기록. 테이블 행의 시작 시각은 끝날 때 채우므로, 그 전에는 실행의 시작 시각을 쓴다. 원천 테이블 ID 순서다. */
    public List<ResourceRun> findResourceRuns(Map<Long, Long> runByResource) {
        return jdbc.sql(
                        """
                        select r.sync_run_id, r.data_source_id, r.source_resource_id, sr.sync_strategy,
                               r.mode, r.status, r.from_lsn, r.to_lsn, r.rows_read, r.rows_upserted,
                               r.rows_deleted,
                               coalesce(r.started_at, s.started_at) as started_at, r.finished_at,
                               r.error_message
                        from unnest(cast(:resources as bigint[]), cast(:runs as bigint[]))
                                 as t(resource_id, run_id)
                        join sync_run_resource r
                          on r.source_resource_id = t.resource_id and r.sync_run_id = t.run_id
                        join sync_run s on s.id = r.sync_run_id
                        join source_resource sr on sr.id = r.source_resource_id
                        order by r.source_resource_id
                        """)
                .param("resources", runByResource.keySet().toArray(Long[]::new))
                .param("runs", runByResource.values().toArray(Long[]::new))
                .query((rs, row) -> resourceRun(rs))
                .list();
    }

    private static ResourceRun resourceRun(ResultSet rs) throws SQLException {
        return new ResourceRun(
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

    public record RequestedResource(long id, boolean mapped) {}

    /**
     * @param runStatus 실행(sync_run)의 상태. 정상이면 QUEUED 또는 RUNNING이다
     */
    public record ActiveRun(long syncRunId, String runStatus) {

        public boolean runActive() {
            return runStatus.equals("QUEUED") || runStatus.equals("RUNNING");
        }
    }

    public record ResourceRun(
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
