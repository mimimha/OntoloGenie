package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.SourceKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전체 읽기 작업(sync_run·sync_run_resource, Jira 197)의 기록과 원천 테이블의 적재 상태를 다룬다. 두 작업 표는 Entity를 두지 않는 작업
 * 표라(QueuedJobRepository와 같은 이유) JdbcClient로 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class SyncRunRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Optional<Target> findTarget(long syncRunId) {
        return jdbc.sql(
                        """
                        select d.id, d.source_kind, d.connection_config
                        from sync_run s join data_source d on d.id = s.data_source_id
                        where s.id = :id
                        """)
                .param("id", syncRunId)
                .query(
                        (rs, row) ->
                                new Target(
                                        rs.getLong("id"),
                                        SourceKind.valueOf(rs.getString("source_kind")),
                                        json.readValue(
                                                rs.getString("connection_config"),
                                                new TypeReference<Map<String, Object>>() {})))
                .optional();
    }

    /** 이 실행이 전체 읽기할 원천 테이블. ID 순서다. */
    public List<Long> findQueuedFullResources(long syncRunId) {
        return jdbc.sql(
                        """
                        select source_resource_id from sync_run_resource
                        where sync_run_id = :id and mode = 'FULL' and status = 'QUEUED'
                        order by source_resource_id
                        """)
                .param("id", syncRunId)
                .query(Long.class)
                .list();
    }

    /** 실행 행을 잠그고 아직 RUNNING인지 본다. 오래 멈춰 정리(FAILED)된 실행이 늦게 돌아와 객체를 쓰지 못하게 한다. 원천 테이블 행보다 먼저 잠근다. */
    public boolean lockIfRunning(long syncRunId) {
        return jdbc.sql("select status = 'RUNNING' from sync_run where id = :id for update")
                .param("id", syncRunId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * 원천 테이블 행을 ID 순서로 잠근다. 매핑을 바꾸는 API도 같은 행을 잠그고 다시 읽기 표시(loaded_lsn = NULL)를 하므로, 저장하는 동안 매핑이
     * 바뀌면 그 표시는 이 저장 뒤에 남는다.
     */
    public void lockResources(List<Long> resourceIds) {
        jdbc.sql(
                        """
                        select id from source_resource where id = any(:ids)
                        order by id for update
                        """)
                .param("ids", resourceIds.toArray(Long[]::new))
                .query(Long.class)
                .list();
    }

    /**
     * CDC 테이블의 끝난 세대와 읽기 시점 LSN을 올린다. 이보다 새 세대가 이미 끝났으면 그대로 둔다. FULL_COMPARE 테이블은 이 칸을 쓰지 않는다(V11
     * ck_source_resource_load_gen).
     */
    public void markLoaded(long resourceId, long generation, long lsn) {
        jdbc.sql(
                        """
                        update source_resource
                        set full_load_done_gen = :gen, loaded_lsn = :lsn
                        where id = :id and sync_strategy = 'CDC'
                          and (full_load_done_gen is null or full_load_done_gen < :gen)
                        """)
                .param("id", resourceId)
                .param("gen", generation)
                .param("lsn", lsn)
                .update();
    }

    /** 테이블 하나의 읽기를 성공으로 끝낸다. 시작 시각은 실행의 시작 시각이다. */
    public void finishResource(
            long syncRunId, long resourceId, Counts counts, Long toLsn, Instant finishedAt) {
        jdbc.sql(
                        """
                        update sync_run_resource r
                        set status = 'SUCCEEDED', started_at = s.started_at, finished_at = :finishedAt,
                            rows_read = :read, rows_upserted = :upserted, rows_deleted = :deleted,
                            to_lsn = :toLsn
                        from sync_run s
                        where s.id = r.sync_run_id and r.sync_run_id = :run
                          and r.source_resource_id = :resource and r.status = 'QUEUED'
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("read", counts.rowsRead())
                .param("upserted", counts.rowsUpserted())
                .param("deleted", counts.rowsDeleted())
                .param("toLsn", toLsn)
                .param("run", syncRunId)
                .param("resource", resourceId)
                .update();
    }

    /** RUNNING인 실행만 SUCCEEDED로 바꾼다. */
    public void finish(long syncRunId, Instant finishedAt) {
        jdbc.sql(
                        """
                        update sync_run set status = 'SUCCEEDED', finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("id", syncRunId)
                .update();
    }

    /**
     * 실행이 실패하면 아직 끝나지 않은 테이블 행도 FAILED로 바꾼다. 남겨 두면 대기·실행 중 유일 조건(uq_sync_run_resource_active) 때문에 그
     * 테이블을 다시 수집할 수 없다.
     */
    public void failResources(long syncRunId, String errorMessage, Instant finishedAt) {
        jdbc.sql(
                        """
                        update sync_run_resource r
                        set status = 'FAILED', started_at = coalesce(r.started_at, s.started_at, :finishedAt),
                            finished_at = :finishedAt, error_message = :message
                        from sync_run s
                        where s.id = r.sync_run_id and r.sync_run_id = :run
                          and r.status in ('QUEUED', 'RUNNING')
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("message", errorMessage)
                .param("run", syncRunId)
                .update();
    }

    public record Target(
            long dataSourceId, SourceKind sourceKind, Map<String, Object> connectionConfig) {}

    /**
     * @param rowsRead 원천에서 읽은 행 수
     * @param rowsUpserted 객체에 쓴 행 수(값이 같아 스냅샷·세대만 갱신한 행 포함)
     * @param rowsDeleted 이번 읽기에 나오지 않아 삭제 표시한 객체 수
     */
    public record Counts(int rowsRead, int rowsUpserted, int rowsDeleted) {}
}
