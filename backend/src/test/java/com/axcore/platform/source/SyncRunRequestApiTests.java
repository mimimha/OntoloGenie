package com.axcore.platform.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.dto.SyncRunAcceptedResponse;
import com.axcore.platform.source.service.SyncRunRequestService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 수동 다시 읽기 접수(API 073, S15P31C106-216)와 테이블별 합류(S15P31C106-217). 실제 읽기는 배치가 하므로 여기서는 작업 표에 들어간 행과
 * 응답을 본다. 원천 테이블과 매핑은 행으로 직접 넣는다.
 */
@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SyncRunRequestApiTests extends SourceApiTestSupport {

    @Autowired SyncRunRequestService service;
    @Autowired DataSource dataSource;

    private LoginSession admin;
    private long sourceId;
    private long typeId;

    @BeforeEach
    void prepare() throws Exception {
        admin = loginAs("ADMIN");
        sourceId = createPostgresSource(admin, uniqueCode());
        typeId =
                jdbcClient
                        .sql(
                                "insert into object_type (code, name) values ('T-' || gen_random_uuid(), '작업지시') returning id")
                        .query(Long.class)
                        .single();
    }

    @Test
    void queuesAllMappedTablesAsOneManualFullRun() throws Exception {
        long workOrder = mappedTable("erp.work_order");
        long equipment = mappedTable("erp.equipment");
        long unmapped = table("erp.memo");
        mapping(table("erp.old_item"), false);

        MvcResult result =
                send(admin, post(syncRunsPath()), null)
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.dataSourceId").value(sourceId))
                        .andExpect(jsonPath("$.data.triggerKind").value("MANUAL"))
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.requestedBy").value(admin.accountId()))
                        .andExpect(jsonPath("$.data.requestedAt").isNotEmpty())
                        .andExpect(jsonPath("$.data.joinedExisting").value(false))
                        .andExpect(jsonPath("$.data.resources.length()").value(2))
                        .andExpect(
                                jsonPath("$.data.resources[0].sourceResourceId").value(workOrder))
                        .andExpect(jsonPath("$.data.resources[0].joined").value(false))
                        .andExpect(jsonPath("$.data.resources[0].mode").value("FULL"))
                        .andExpect(jsonPath("$.data.resources[0].status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.resources[0].fromLsn").value(nullValue()))
                        .andExpect(jsonPath("$.data.resources[0].toLsn").value(nullValue()))
                        .andExpect(jsonPath("$.data.resources[0].rowsRead").value(0))
                        .andExpect(jsonPath("$.data.resources[0].startedAt").value(nullValue()))
                        .andExpect(jsonPath("$.data.resources[0].errorMessage").value(nullValue()))
                        .andExpect(
                                jsonPath("$.data.resources[1].sourceResourceId").value(equipment))
                        .andReturn();
        long runId = longAt(result, "$.data.syncRunId");

        assertThat(longAt(result, "$.data.resources[0].syncRunId")).isEqualTo(runId);
        assertThat(longAt(result, "$.data.resources[1].syncRunId")).isEqualTo(runId);
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        select data_source_id, trigger_kind, status, requested_by
                                        from sync_run where id = :id
                                        """)
                                .param("id", runId)
                                .query()
                                .singleRow())
                .containsEntry("data_source_id", sourceId)
                .containsEntry("trigger_kind", "MANUAL")
                .containsEntry("status", "QUEUED")
                .containsEntry("requested_by", admin.accountId());
        assertThat(queuedResources(runId)).containsExactly(workOrder, equipment);
        assertThat(activeRuns(unmapped)).isZero();
        Map<String, Object> audit = auditLine(runId);
        assertThat(audit)
                .containsEntry("event_kind", "SYNC_RUN")
                .containsEntry("actor_type", "USER")
                .containsEntry("entry_point", "WEB")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("checked_permission_code", "ADMIN");
    }

    @Test
    void joinsActiveRunPerTableAndQueuesOnlyTheRest() throws Exception {
        long workOrder = mappedTable("erp.work_order");
        long equipment = mappedTable("erp.equipment");
        long recoveryRun = activeRun(workOrder, "RECOVERY", "QUEUED");

        MvcResult first =
                send(admin, post(syncRunsPath()), ids(workOrder, equipment))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.joinedExisting").value(true))
                        .andExpect(jsonPath("$.data.resources[0].joined").value(true))
                        .andExpect(jsonPath("$.data.resources[1].joined").value(false))
                        .andReturn();
        long newRun = longAt(first, "$.data.syncRunId");

        assertThat(newRun).isNotEqualTo(recoveryRun);
        assertThat(longAt(first, "$.data.resources[0].syncRunId")).isEqualTo(recoveryRun);
        assertThat(longAt(first, "$.data.resources[1].syncRunId")).isEqualTo(newRun);
        assertThat(queuedResources(newRun)).containsExactly(equipment);
        assertThat(queuedResources(recoveryRun)).containsExactly(workOrder);

        long runsBefore = runCount();
        long auditsBefore = untargetedAuditCount();
        MvcResult again =
                send(admin, post(syncRunsPath()), ids(equipment, workOrder, equipment))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.syncRunId").value(nullValue()))
                        .andExpect(jsonPath("$.data.status").value(nullValue()))
                        .andExpect(jsonPath("$.data.triggerKind").value("MANUAL"))
                        .andExpect(jsonPath("$.data.joinedExisting").value(true))
                        .andExpect(jsonPath("$.data.resources.length()").value(2))
                        .andExpect(jsonPath("$.data.resources[0].joined").value(true))
                        .andExpect(jsonPath("$.data.resources[1].joined").value(true))
                        .andReturn();

        assertThat(longAt(again, "$.data.resources[0].syncRunId")).isEqualTo(recoveryRun);
        assertThat(longAt(again, "$.data.resources[1].syncRunId")).isEqualTo(newRun);
        assertThat(runCount()).isEqualTo(runsBefore);
        assertThat(untargetedAuditCount()).isEqualTo(auditsBefore + 1);
    }

    @Test
    void joinsRunningRunAndQueuesNewOneAfterItEnds() throws Exception {
        long workOrder = mappedTable("erp.work_order");
        long runningRun = activeRun(workOrder, "MANUAL", "RUNNING");

        MvcResult joined =
                send(admin, post(syncRunsPath()), ids(workOrder))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.syncRunId").value(nullValue()))
                        .andExpect(jsonPath("$.data.resources[0].joined").value(true))
                        .andExpect(jsonPath("$.data.resources[0].status").value("QUEUED"))
                        .andExpect(jsonPath("$.data.resources[0].startedAt").isNotEmpty())
                        .andReturn();
        assertThat(longAt(joined, "$.data.resources[0].syncRunId")).isEqualTo(runningRun);

        finish(runningRun);
        MvcResult next =
                send(admin, post(syncRunsPath()), ids(workOrder))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.data.joinedExisting").value(false))
                        .andExpect(jsonPath("$.data.resources[0].joined").value(false))
                        .andReturn();

        assertThat(longAt(next, "$.data.syncRunId")).isNotEqualTo(runningRun);
        assertThat(activeRuns(workOrder)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidTargetsWithoutQueuing() throws Exception {
        long mapped = mappedTable("erp.work_order");
        long unmapped = table("erp.memo");
        long otherSource = createPostgresSource(admin, uniqueCode());
        long otherTable = resource(otherSource, "erp.work_order");
        mapping(otherTable, true);
        long runsBefore = runCount();

        send(admin, post(syncRunsPath()), ids(mapped, otherTable))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("sourceResourceIds"))
                .andExpect(jsonPath("$.error.details.ids[0]").value(otherTable));
        send(admin, post(syncRunsPath()), ids(mapped, 999_999_999L))
                .andExpect(status().isBadRequest());
        send(admin, post(syncRunsPath()), ids(mapped, unmapped))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.ids[0]").value(unmapped));
        send(admin, post(syncRunsPath()), "{\"sourceResourceIds\": []}")
                .andExpect(status().isBadRequest());
        send(admin, post(syncRunsPath()), "{\"sourceResourceIds\": [0]}")
                .andExpect(status().isBadRequest());
        send(
                        admin,
                        post(
                                BASE_PATH
                                        + "/"
                                        + createPostgresSource(admin, uniqueCode())
                                        + "/sync-runs"),
                        null)
                .andExpect(status().isUnprocessableContent());

        assertThat(runCount()).isEqualTo(runsBefore);
        assertThat(activeRuns(mapped)).isZero();
    }

    /** 원천 교체 뒤 재연결을 기다리는 CDC 테이블은 재연결 때 어차피 다시 읽으므로 막는다. FULL_COMPARE 테이블은 커넥터와 상관없어 받는다. */
    @Test
    void rejectsCdcTablesWaitingReconnectButQueuesFullCompare() throws Exception {
        long cdcTable = mappedTable("erp.work_order");
        long compareTable = resource(sourceId, "erp.daily_plan", "FULL_COMPARE");
        mapping(compareTable, true);
        recovery("SOURCE_CHANGED");
        long runsBefore = runCount();

        send(admin, post(syncRunsPath()), ids(cdcTable, compareTable))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.details.recoveryReason").value("SOURCE_CHANGED"))
                .andExpect(jsonPath("$.error.details.ids.length()").value(1))
                .andExpect(jsonPath("$.error.details.ids[0]").value(cdcTable));
        send(admin, post(syncRunsPath()), null).andExpect(status().isConflict());
        assertThat(runCount()).isEqualTo(runsBefore);

        send(admin, post(syncRunsPath()), ids(compareTable))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.resources[0].sourceResourceId").value(compareTable));

        recovery("SLOT_LOST");
        send(admin, post(syncRunsPath()), ids(cdcTable))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.resources[0].sourceResourceId").value(cdcTable));
    }

    @Test
    void rejectsMissingSourceAndNonAdmin() throws Exception {
        long mapped = mappedTable("erp.work_order");
        LoginSession viewer = loginAs("VIEWER");

        send(admin, post(BASE_PATH + "/999999999/sync-runs"), null)
                .andExpect(status().isNotFound());
        send(viewer, post(syncRunsPath()), ids(mapped)).andExpect(status().isForbidden());

        assertThat(activeRuns(mapped)).isZero();
    }

    @Test
    void concurrentRequestsForSameTableShareOneRun() throws Exception {
        long workOrder = mappedTable("erp.work_order");
        Callable<SyncRunAcceptedResponse> request =
                () -> service.request(sourceId, List.of(workOrder), adminAccount());

        List<SyncRunAcceptedResponse> responses;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<SyncRunAcceptedResponse> first = pool.submit(request);
            Future<SyncRunAcceptedResponse> second = pool.submit(request);
            responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }

        assertThat(responses)
                .extracting(r -> r.resources().get(0).syncRunId())
                .containsOnly(responses.get(0).resources().get(0).syncRunId());
        assertThat(responses)
                .extracting(SyncRunAcceptedResponse::syncRunId)
                .containsOnlyOnce((Long) null);
        assertThat(activeRuns(workOrder)).isEqualTo(1);
        assertThat(runCount()).isEqualTo(1);
    }

    /** 다른 요청이 같은 테이블을 넣고 아직 커밋하지 않았으면 그 커밋을 기다렸다가 합류하고, 자기가 만든 빈 실행은 남기지 않는다. */
    @Test
    void waitsForUncommittedInsertThenJoinsIt() throws Exception {
        long workOrder = mappedTable("erp.work_order");
        Callable<SyncRunAcceptedResponse> request =
                () -> service.request(sourceId, List.of(workOrder), adminAccount());

        long holderRun;
        List<SyncRunAcceptedResponse> responses;
        // 실패해도 잠금이 먼저 풀리도록 연결을 스레드 풀보다 먼저 닫는다(나중에 선언).
        try (var pool = Executors.newFixedThreadPool(2);
                Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holderRun = insertActiveRun(holder, workOrder);
            Future<SyncRunAcceptedResponse> first = pool.submit(request);
            Future<SyncRunAcceptedResponse> second = pool.submit(request);
            awaitLockWaiters(2);
            holder.commit();
            responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }

        assertThat(responses)
                .allSatisfy(
                        r -> {
                            assertThat(r.syncRunId()).isNull();
                            assertThat(r.joinedExisting()).isTrue();
                            assertThat(r.resources().get(0).syncRunId()).isEqualTo(holderRun);
                        });
        assertThat(runCount()).isEqualTo(1);
    }

    /**
     * 겹치는 테이블을 고른 두 요청({A,B}·{B,C})이 서로 막히지 않는다. B는 다른 요청이 넣고 아직 커밋하지 않은 상태라 둘 다 기다렸다가 그 실행에 합류하고,
     * A·C는 각자 새 실행에 들어간다.
     */
    @Test
    void overlappingConcurrentRequestsShareOnlyTheCommonTable() throws Exception {
        long a = mappedTable("erp.a");
        long b = mappedTable("erp.b");
        long c = mappedTable("erp.c");

        long holderRun;
        SyncRunAcceptedResponse ab;
        SyncRunAcceptedResponse bc;
        try (var pool = Executors.newFixedThreadPool(2);
                Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holderRun = insertActiveRun(holder, b);
            Future<SyncRunAcceptedResponse> first =
                    pool.submit(() -> service.request(sourceId, List.of(a, b), adminAccount()));
            Future<SyncRunAcceptedResponse> second =
                    pool.submit(() -> service.request(sourceId, List.of(c, b), adminAccount()));
            awaitLockWaiters(2);
            holder.commit();
            ab = first.get(20, TimeUnit.SECONDS);
            bc = second.get(20, TimeUnit.SECONDS);
        }

        assertThat(ab.resources())
                .extracting(r -> r.sourceResourceId() + ":" + r.syncRunId() + ":" + r.joined())
                .containsExactly(
                        a + ":" + ab.syncRunId() + ":false", b + ":" + holderRun + ":true");
        assertThat(bc.resources())
                .extracting(r -> r.sourceResourceId() + ":" + r.syncRunId() + ":" + r.joined())
                .containsExactly(
                        b + ":" + holderRun + ":true", c + ":" + bc.syncRunId() + ":false");
        assertThat(ab.syncRunId()).isNotNull().isNotEqualTo(bc.syncRunId());
        assertThat(List.of(activeRuns(a), activeRuns(b), activeRuns(c))).containsOnly(1L);
        assertThat(runCount()).isEqualTo(3);
    }

    /** 테이블 행은 대기 중인데 실행은 끝난 어긋난 데이터면 끝없이 다시 시도하지 않고 오류로 끝낸다. 새 실행도 남기지 않는다. */
    @Test
    void failsInsteadOfLoopingWhenActiveRowBelongsToFinishedRun() throws Exception {
        long workOrder = mappedTable("erp.work_order");
        long finishedRun = activeRun(workOrder, "RECOVERY", "QUEUED");
        jdbcClient
                .sql(
                        """
                        update sync_run set status = 'SUCCEEDED', started_at = now(), finished_at = now()
                        where id = :id
                        """)
                .param("id", finishedRun)
                .update();

        send(admin, post(syncRunsPath()), ids(workOrder))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(runCount()).isEqualTo(1);
    }

    private String syncRunsPath() {
        return BASE_PATH + "/" + sourceId + "/sync-runs";
    }

    private static String ids(Long... ids) {
        return "{\"sourceResourceIds\": " + List.of(ids) + "}";
    }

    private static long longAt(MvcResult result, String path) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), path))
                .longValue();
    }

    private LoginAccount adminAccount() {
        return new LoginAccount(admin.accountId(), "admin", "ADMIN", false);
    }

    private long mappedTable(String key) {
        long id = table(key);
        mapping(id, true);
        return id;
    }

    private long table(String key) {
        return resource(sourceId, key);
    }

    private long resource(long dataSourceId, String key) {
        return resource(dataSourceId, key, "CDC");
    }

    private long resource(long dataSourceId, String key, String strategy) {
        return jdbcClient
                .sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy)
                        values (:s, :key, 'TABLE', true, :strategy) returning id
                        """)
                .param("s", dataSourceId)
                .param("key", key)
                .param("strategy", strategy)
                .query(Long.class)
                .single();
    }

    private void recovery(String reason) {
        jdbcClient
                .sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset,
                                                recovery_reason, recovery_at)
                        values (:topic, 0, :s, 0, :reason, now())
                        on conflict (topic, partition_no)
                        do update set recovery_reason = excluded.recovery_reason, recovery_at = now()
                        """)
                .param("topic", "manual-" + sourceId + ".all")
                .param("s", sourceId)
                .param("reason", reason)
                .update();
    }

    private void mapping(long resourceId, boolean active) {
        jdbcClient
                .sql(
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

    /** 다른 곳에서 넣은 대기·실행 중 실행. 실행 중이어도 테이블 행은 QUEUED로 남는다. */
    private long activeRun(long resourceId, String triggerKind, String runStatus) {
        long runId =
                jdbcClient
                        .sql(
                                """
                                insert into sync_run (data_source_id, trigger_kind, status, started_at,
                                                      heartbeat_at, requested_by)
                                values (:s, :trigger, :status,
                                        case when :status = 'RUNNING' then now() end,
                                        case when :status = 'RUNNING' then now() end,
                                        case when :trigger = 'MANUAL' then :by end)
                                returning id
                                """)
                        .param("s", sourceId)
                        .param("trigger", triggerKind)
                        .param("status", runStatus)
                        .param("by", admin.accountId())
                        .query(Long.class)
                        .single();
        jdbcClient
                .sql(
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

    private long insertActiveRun(Connection connection, long resourceId) throws Exception {
        long runId;
        try (PreparedStatement run =
                connection.prepareStatement(
                        "insert into sync_run (data_source_id, trigger_kind) values (?, 'RECOVERY') returning id")) {
            run.setLong(1, sourceId);
            try (ResultSet rs = run.executeQuery()) {
                rs.next();
                runId = rs.getLong(1);
            }
        }
        try (PreparedStatement resource =
                connection.prepareStatement(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id, mode)
                        values (?, ?, ?, 'FULL')
                        """)) {
            resource.setLong(1, runId);
            resource.setLong(2, sourceId);
            resource.setLong(3, resourceId);
            resource.executeUpdate();
        }
        return runId;
    }

    /** 배치가 실행을 끝낸 것처럼 바꾼다. */
    private void finish(long runId) {
        jdbcClient
                .sql(
                        """
                        update sync_run_resource set status = 'SUCCEEDED', started_at = now(),
                                                     finished_at = now()
                        where sync_run_id = :id
                        """)
                .param("id", runId)
                .update();
        jdbcClient
                .sql("update sync_run set status = 'SUCCEEDED', finished_at = now() where id = :id")
                .param("id", runId)
                .update();
    }

    /** 대기·실행 중 유일 조건에 걸려 기다리는 연결이 count개가 될 때까지 기다린다. */
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting =
                    jdbcClient
                            .sql(
                                    """
                                    select count(*) from pg_stat_activity
                                    where datname = current_database()
                                      and wait_event_type = 'Lock'
                                      and query ilike '%insert into sync_run_resource%'
                                    """)
                            .query(Long.class)
                            .single();
            if (waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("두 요청이 테이블 행 삽입을 기다리지 않습니다.");
    }

    private List<Long> queuedResources(long runId) {
        return jdbcClient
                .sql(
                        """
                        select source_resource_id from sync_run_resource
                        where sync_run_id = :id and mode = 'FULL' and status = 'QUEUED'
                        order by source_resource_id
                        """)
                .param("id", runId)
                .query(Long.class)
                .list();
    }

    private long activeRuns(long resourceId) {
        return jdbcClient
                .sql(
                        """
                        select count(*) from sync_run_resource
                        where source_resource_id = :r and status in ('QUEUED', 'RUNNING')
                        """)
                .param("r", resourceId)
                .query(Long.class)
                .single();
    }

    private long runCount() {
        return jdbcClient
                .sql("select count(*) from sync_run where data_source_id = :s")
                .param("s", sourceId)
                .query(Long.class)
                .single();
    }

    private long untargetedAuditCount() {
        return jdbcClient
                .sql(
                        """
                        select count(*) from action_execution
                        where event_kind = 'SYNC_RUN' and target_id is null
                          and (input ->> 'dataSourceId')::bigint = :s
                        """)
                .param("s", sourceId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> auditLine(long runId) {
        return jdbcClient
                .sql(
                        """
                        select event_kind, actor_type, entry_point, status, checked_permission_code
                        from action_execution where target_type = 'sync_run' and target_id = :id
                        """)
                .param("id", runId)
                .query()
                .singleRow();
    }
}
