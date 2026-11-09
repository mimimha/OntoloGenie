package com.axcore.platform.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.domain.QueuedJobType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 멈춘 작업 정리(S15P31C106-218)를 실제 PostgreSQL로 확인한다. 기준은 기본값(sync_run heartbeat 5분, 연결 검사 10분, 정합성 검사
 * 30분)이다. 같은 DB를 다른 시험과 함께 쓰므로 이 시험이 만든 행의 상태만 본다.
 */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class StaleJobCleanerTests {

    // 주기 실행이 시험 도중 작업을 가져가거나 먼저 정리하지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean StaleJobCleanupJob staleJobCleanupJob;

    @Autowired StaleJobCleaner cleaner;
    @Autowired JdbcClient jdbc;

    private long accountId;
    private long sourceId;

    @BeforeEach
    void prepare() {
        accountId =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values (:loginId, '관리자', 'not-used',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .param("loginId", "admin-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
        sourceId =
                jdbc.sql(
                                """
                                insert into data_source (code, name, source_kind)
                                values (:code, '정리 시험 원천', 'POSTGRES') returning id
                                """)
                        .param(
                                "code",
                                "SJ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .query(Long.class)
                        .single();
    }

    @Test
    void failsSyncRunWhoseHeartbeatStoppedAndFreesItsTable() {
        long table = table("erp.work_order");
        long runId = runningSyncRun("10 minutes", "6 minutes");
        addResource(runId, table);

        cleaner.failStale(QueuedJobType.SYNC_RUN);

        Map<String, Object> run = row("sync_run", runId);
        assertThat(run).containsEntry("status", "FAILED");
        assertThat((String) run.get("error_message")).contains("5분").contains("시간 초과");
        assertThat(
                        jdbc.sql(
                                        """
                                        select status, error_message from sync_run_resource
                                        where sync_run_id = :run and source_resource_id = :r
                                        """)
                                .param("run", runId)
                                .param("r", table)
                                .query()
                                .singleRow())
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", run.get("error_message"));
        Map<String, Object> audit = auditLine("sync_run", runId);
        assertThat(audit)
                .containsEntry("event_kind", "SYNC_RUN")
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("entry_point", "BATCH")
                .containsEntry("status", "FAILED");
        assertThat((String) audit.get("result")).contains("\"reason\": \"TIMEOUT\"");
        // 대기·실행 중 유일 조건이 풀려 같은 테이블을 다시 넣을 수 있다.
        addResource(queuedSyncRun(), table);
    }

    @Test
    void keepsSyncRunWhileHeartbeatIsFresh() {
        long alive = runningSyncRun("10 minutes", "10 seconds");
        long justStarted = runningSyncRun("1 minute", null);
        long neverBeat = runningSyncRun("6 minutes", null);

        cleaner.failStale(QueuedJobType.SYNC_RUN);

        assertThat(row("sync_run", alive)).containsEntry("status", "RUNNING");
        assertThat(row("sync_run", justStarted)).containsEntry("status", "RUNNING");
        assertThat(row("sync_run", neverBeat)).containsEntry("status", "FAILED");
    }

    @Test
    void failsConnectionCheckByStartTimeAndAllowsNewCheck() {
        long recent = runningCheck("2 minutes");
        assertThat(cleaner.failStale(QueuedJobType.SOURCE_CHECK)).doesNotContain(recent);
        assertThat(row("source_connection_check", recent)).containsEntry("status", "RUNNING");

        jdbc.sql(
                        "update source_connection_check set started_at = now() - interval '11 minutes' where id = :id")
                .param("id", recent)
                .update();

        cleaner.failStale(QueuedJobType.SOURCE_CHECK);

        assertThat(row("source_connection_check", recent)).containsEntry("status", "FAILED");
        assertThat(auditLine("source_connection_check", recent))
                .containsEntry("event_kind", "SOURCE_CHECK")
                .containsEntry("status", "FAILED");
        // 같은 원천에 새 검사를 넣을 수 있다(uq_source_connection_check_active).
        jdbc.sql(
                        """
                        insert into source_connection_check (data_source_id, requested_by)
                        values (:s, :a)
                        """)
                .param("s", sourceId)
                .param("a", accountId)
                .update();
    }

    @Test
    void failsCheckRunWithoutHandlerAndLeavesQueuedRows() {
        String workKey = "sj-" + UUID.randomUUID();
        long stale =
                jdbc.sql(
                                """
                                insert into check_run (trigger_kind, work_key, status, requested_at, started_at)
                                values ('CANDIDATE', :k, 'RUNNING', now() - interval '40 minutes',
                                        now() - interval '31 minutes')
                                returning id
                                """)
                        .param("k", workKey)
                        .query(Long.class)
                        .single();
        long queued =
                jdbc.sql(
                                """
                                insert into check_run (trigger_kind, work_key, requested_at)
                                values ('CANDIDATE', :k, now() - interval '2 hours') returning id
                                """)
                        .param("k", "sj-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();

        cleaner.failStale(QueuedJobType.CHECK_RUN);

        assertThat(row("check_run", stale)).containsEntry("status", "FAILED");
        assertThat(row("check_run", queued)).containsEntry("status", "QUEUED");
        jdbc.sql("delete from check_run where id = :id").param("id", queued).update();
    }

    private long table(String key) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy)
                        values (:s, :key, 'TABLE', true, 'CDC') returning id
                        """)
                .param("s", sourceId)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    /** started·heartbeat는 지금보다 얼마 전인지(PostgreSQL interval). heartbeat가 null이면 아직 갱신 전이다. */
    private long runningSyncRun(String started, String heartbeat) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, status, requested_at, started_at,
                                              heartbeat_at)
                        values (:s, 'SCHEDULED', 'RUNNING', now() - cast(:started as interval),
                                now() - cast(:started as interval),
                                now() - cast(:heartbeat as interval))
                        returning id
                        """)
                .param("s", sourceId)
                .param("started", started)
                .param("heartbeat", heartbeat)
                .query(Long.class)
                .single();
    }

    private long queuedSyncRun() {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind) values (:s, 'SCHEDULED')
                        returning id
                        """)
                .param("s", sourceId)
                .query(Long.class)
                .single();
    }

    private void addResource(long runId, long resourceId) {
        jdbc.sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode,
                                                       status, started_at)
                        select :run, :s, :r, 'FULL', case when s.status = 'RUNNING' then 'RUNNING' else 'QUEUED' end,
                               s.started_at
                        from sync_run s where s.id = :run
                        """)
                .param("run", runId)
                .param("s", sourceId)
                .param("r", resourceId)
                .update();
    }

    private long runningCheck(String started) {
        return jdbc.sql(
                        """
                        insert into source_connection_check (data_source_id, requested_by, status,
                                                             requested_at, started_at)
                        values (:s, :a, 'RUNNING', now() - cast(:started as interval),
                                now() - cast(:started as interval))
                        returning id
                        """)
                .param("s", sourceId)
                .param("a", accountId)
                .param("started", started)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> row(String table, long id) {
        return jdbc.sql("select status, error_message from %s where id = :id".formatted(table))
                .param("id", id)
                .query()
                .singleRow();
    }

    private Map<String, Object> auditLine(String targetType, long id) {
        return jdbc.sql(
                        """
                        select event_kind, actor_type, entry_point, status, result::text as result
                        from action_execution where target_type = :t and target_id = :id
                        """)
                .param("t", targetType)
                .param("id", id)
                .query()
                .singleRow();
    }
}
