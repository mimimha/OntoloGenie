package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * FULL_COMPARE 테이블 주기 수집(S15P31C106-318)이 어떤 테이블에 언제 실행을 넣는지 실제 PostgreSQL로 확인한다. 원천·원천 테이블·매핑은 행으로
 * 직접 넣는다. 같은 DB를 다른 시험과 함께 쓰므로 이 시험이 만든 원천의 결과만 본다.
 */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class ScheduledSyncServiceTests {

    // 주기 실행이 시험 도중 작업을 넣거나 가져가지 않게 막는다. ScheduledSyncJob은 시험 설정(schedule-watch.enabled=false)으로 꺼져
    // 있다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired ScheduledSyncService service;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    private long typeId;

    @BeforeEach
    void prepare() {
        typeId =
                jdbc.sql(
                                "insert into object_type (code, name) values ('T-' || gen_random_uuid(), '작업지시') returning id")
                        .query(Long.class)
                        .single();
    }

    @AfterEach
    void removeQueuedRuns() {
        jdbc.sql(
                        """
                        delete from sync_run
                        where trigger_kind in ('SCHEDULED', 'MANUAL') and status = 'QUEUED'
                        """)
                .update();
    }

    @Test
    void queuesOnlyFullCompareTablesAsScheduledRun() {
        long source = source(true, 10);
        long view = mapped(source, "erp.v_stock", "FULL_COMPARE");
        long cdc = mapped(source, "erp.work_order", "CDC");

        List<Long> queued = service.queueDueSyncs();

        long runId = queuedRun(view);
        assertThat(queued).contains(runId);
        assertThat(activeRuns(cdc)).as("CDC 테이블은 넣지 않는다").isZero();
        assertThat(
                        jdbc.sql(
                                        """
                                        select data_source_id, trigger_kind, status, requested_by
                                        from sync_run where id = :id
                                        """)
                                .param("id", runId)
                                .query()
                                .singleRow())
                .containsEntry("data_source_id", source)
                .containsEntry("trigger_kind", "SCHEDULED")
                .containsEntry("status", "QUEUED")
                .containsEntry("requested_by", null);
        assertThat(
                        jdbc.sql(
                                        """
                                        select mode, status from sync_run_resource
                                        where sync_run_id = :id
                                        """)
                                .param("id", runId)
                                .query()
                                .listOfRows())
                .containsExactly(Map.of("mode", "FULL", "status", "QUEUED"));
        Map<String, Object> audit = auditLine(runId);
        assertThat(audit)
                .containsEntry("event_kind", "SYNC_RUN")
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("entry_point", "BATCH")
                .containsEntry("status", "SUCCEEDED");
        Map<?, ?> result = json.readValue((String) audit.get("result"), Map.class);
        assertThat(result.get("status")).isEqualTo("QUEUED");
        assertThat(result.get("sourceResourceIds")).isEqualTo(List.of((int) view));
        assertThat(result.get("joinedSourceResourceIds")).isEqualTo(List.of());
    }

    @Test
    void queuesAgainOnlyAfterIntervalSinceLastScheduledRun() {
        long recent = source(true, 10);
        long recentView = mapped(recent, "erp.v_recent", "FULL_COMPARE");
        finishedScheduledRun(recent, recentView, "9 minutes");
        long old = source(true, 10);
        long oldView = mapped(old, "erp.v_old", "FULL_COMPARE");
        finishedScheduledRun(old, oldView, "11 minutes");

        service.queueDueSyncs();

        assertThat(activeRuns(recentView)).isZero();
        assertThat(activeRuns(oldView)).isOne();
    }

    @Test
    void doesNotQueueTwiceWithinInterval() {
        long source = source(true, 10);
        long view = mapped(source, "erp.v_stock", "FULL_COMPARE");

        service.queueDueSyncs();
        // 앞 실행이 끝나도 주기가 지나기 전에는 다시 넣지 않는다.
        jdbc.sql("delete from sync_run where data_source_id = :s and status = 'QUEUED'")
                .param("s", source)
                .update();
        finishedScheduledRun(source, view, "1 minute");
        service.queueDueSyncs();

        assertThat(activeRuns(view)).isZero();
        assertThat(scheduledRuns(source)).isOne();
    }

    @Test
    void skipsDisabledSourcesAndTablesWithoutActiveMapping() {
        long disabled = source(false, 10);
        long disabledView = mapped(disabled, "erp.v_disabled", "FULL_COMPARE");
        long enabled = source(true, 10);
        long inactive = resource(enabled, "erp.v_inactive", "FULL_COMPARE");
        mapping(inactive, false);
        long unmapped = resource(enabled, "erp.v_unmapped", "FULL_COMPARE");

        service.queueDueSyncs();

        assertThat(activeRuns(disabledView)).isZero();
        assertThat(activeRuns(inactive)).isZero();
        assertThat(activeRuns(unmapped)).isZero();
        assertThat(scheduledRuns(enabled)).as("넣을 테이블이 없으면 실행을 남기지 않는다").isZero();
    }

    @Test
    void joinsTableAlreadyWaitingInAnotherRun() {
        long source = source(true, 10);
        long waiting = mapped(source, "erp.v_waiting", "FULL_COMPARE");
        long free = mapped(source, "erp.v_free", "FULL_COMPARE");
        long manualRun = queuedManualRun(source, waiting);

        service.queueDueSyncs();

        assertThat(queuedRun(waiting)).as("기다리는 실행에 합류한다").isEqualTo(manualRun);
        long runId = queuedRun(free);
        assertThat(runId).isNotEqualTo(manualRun);
        Map<?, ?> result = json.readValue((String) auditLine(runId).get("result"), Map.class);
        assertThat(result.get("sourceResourceIds")).isEqualTo(List.of((int) free));
        assertThat(result.get("joinedSourceResourceIds")).isEqualTo(List.of((int) waiting));
    }

    @Test
    void leavesNoRunWhenEveryTableIsAlreadyWaiting() {
        long source = source(true, 10);
        long waiting = mapped(source, "erp.v_waiting", "FULL_COMPARE");
        queuedManualRun(source, waiting);

        service.queueDueSyncs();

        assertThat(activeRuns(waiting)).isOne();
        assertThat(scheduledRuns(source)).isZero();
    }

    private long source(boolean enabled, Integer intervalMinutes) {
        return jdbc.sql(
                        """
                        insert into data_source (code, name, source_kind, sync_enabled,
                                                 sync_interval_minutes)
                        values (:code, '주기 수집 시험 원천', 'POSTGRES', :enabled, :interval)
                        returning id
                        """)
                .param("code", "SS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .param("enabled", enabled)
                .param("interval", intervalMinutes)
                .query(Long.class)
                .single();
    }

    private long mapped(long sourceId, String key, String strategy) {
        long id = resource(sourceId, key, strategy);
        mapping(id, true);
        return id;
    }

    private long resource(long sourceId, String key, String strategy) {
        return jdbc.sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy)
                        values (:s, :key, :kind, true, :strategy) returning id
                        """)
                .param("s", sourceId)
                .param("key", key)
                .param("kind", strategy.equals("CDC") ? "TABLE" : "VIEW")
                .param("strategy", strategy)
                .query(Long.class)
                .single();
    }

    private void mapping(long resourceId, boolean active) {
        jdbc.sql(
                        """
                        insert into object_source_mapping (source_resource_id, object_type_id, key_columns,
                                                           is_active)
                        values (:r, :t, '["id"]'::jsonb, :active)
                        """)
                .param("r", resourceId)
                .param("t", typeId)
                .param("active", active)
                .update();
    }

    private void finishedScheduledRun(long sourceId, long resourceId, String ago) {
        long runId =
                jdbc.sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, status, requested_at,
                                                      started_at, finished_at)
                                values (:s, 'SCHEDULED', 'SUCCEEDED', now() - cast(:ago as interval),
                                        now() - cast(:ago as interval), now() - cast(:ago as interval))
                                returning id
                                """)
                        .param("s", sourceId)
                        .param("ago", ago)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode,
                                                       status, started_at, finished_at)
                        values (:run, :s, :r, 'FULL', 'SUCCEEDED', now() - cast(:ago as interval),
                                now() - cast(:ago as interval))
                        """)
                .param("run", runId)
                .param("s", sourceId)
                .param("r", resourceId)
                .param("ago", ago)
                .update();
    }

    /** 관리자가 073으로 넣어 아직 기다리는 실행. */
    private long queuedManualRun(long sourceId, long resourceId) {
        long admin =
                jdbc.sql(
                                """
                                insert into account (login_id, display_name, password_hash, role_id,
                                                     must_change_password)
                                values ('schedule-' || gen_random_uuid(), '주기 시험 관리자', 'x',
                                        (select id from role where code = 'ADMIN'), false)
                                returning id
                                """)
                        .query(Long.class)
                        .single();
        long runId =
                jdbc.sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, requested_by)
                                values (:s, 'MANUAL', :by) returning id
                                """)
                        .param("s", sourceId)
                        .param("by", admin)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode)
                        values (:run, :s, :r, 'FULL')
                        """)
                .param("run", runId)
                .param("s", sourceId)
                .param("r", resourceId)
                .update();
        return runId;
    }

    private long queuedRun(long resourceId) {
        return jdbc.sql(
                        """
                        select sync_run_id from sync_run_resource
                        where source_resource_id = :r and status = 'QUEUED'
                        """)
                .param("r", resourceId)
                .query(Long.class)
                .single();
    }

    private long activeRuns(long resourceId) {
        return jdbc.sql(
                        """
                        select count(*) from sync_run_resource
                        where source_resource_id = :r and status in ('QUEUED', 'RUNNING')
                        """)
                .param("r", resourceId)
                .query(Long.class)
                .single();
    }

    private long scheduledRuns(long sourceId) {
        return jdbc.sql(
                        """
                        select count(*) from sync_run
                        where data_source_id = :s and trigger_kind = 'SCHEDULED'
                        """)
                .param("s", sourceId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> auditLine(long runId) {
        return jdbc.sql(
                        """
                        select event_kind, actor_type, entry_point, status, result::text as result
                        from action_execution where target_type = 'sync_run' and target_id = :id
                        """)
                .param("id", runId)
                .query()
                .singleRow();
    }
}
