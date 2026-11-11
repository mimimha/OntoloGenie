package com.axcore.platform.source;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 원천 수집 이력(API 074)과 수집 상태·건수·실패 사유(API 075, S15P31C106-219). 실행 기록은 배치와 CDC 소비자가 쓰므로 여기서는 행으로 직접
 * 넣는다.
 */
@SpringBootTest(properties = SourceApiTestSupport.CREDENTIAL_KEY)
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SyncRunQueryApiTests extends SourceApiTestSupport {

    /** 저장 시각(updated_at = now())과 다른 원천 커밋 시각. 응답이 어느 칸을 읽는지 가린다. */
    private static final String LAST_COMMIT_AT = "2026-11-10T01:02:03Z";

    private LoginSession admin;
    private long sourceId;

    @BeforeEach
    void prepare() throws Exception {
        admin = loginAs("ADMIN");
        sourceId = createPostgresSource(admin, uniqueCode());
    }

    @Test
    void listsFullReadsNewestFirstWithoutCdcBatchesByDefault() throws Exception {
        long cdcTable = resource("erp.work_order", "CDC");
        long compareTable = resource("erp.daily_plan", "FULL_COMPARE");
        long recovery = run("RECOVERY", "SUCCEEDED", "3 hours", null, null);
        fullResource(recovery, cdcTable, "SUCCEEDED", 24_678_912L, 20, 18, 2, null);
        long manual = run("MANUAL", "FAILED", "2 hours", admin.accountId(), "원천에 접속하지 못했습니다.");
        fullResource(manual, cdcTable, "FAILED", null, 0, 0, 0, "원천에 접속하지 못했습니다.");
        fullResource(manual, compareTable, "FAILED", null, 0, 0, 0, "원천에 접속하지 못했습니다.");
        long cdc = run("CDC", "SUCCEEDED", "1 hour", null, null);
        incrementalResource(cdc, cdcTable, 100L, 180L);
        cdcOffset(LAST_COMMIT_AT);

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(manual))
                .andExpect(jsonPath("$.data[0].syncRunId").value(manual))
                .andExpect(jsonPath("$.data[0].dataSourceId").value(sourceId))
                .andExpect(jsonPath("$.data[0].triggerKind").value("MANUAL"))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].requestedBy").value(admin.accountId()))
                .andExpect(jsonPath("$.data[0].errorMessage").value("원천에 접속하지 못했습니다."))
                .andExpect(jsonPath("$.data[0].resources.length()").value(2))
                .andExpect(jsonPath("$.data[0].resources[0].sourceResourceId").value(cdcTable))
                .andExpect(jsonPath("$.data[0].resources[0].syncStrategy").value("CDC"))
                .andExpect(jsonPath("$.data[0].resources[1].syncStrategy").value("FULL_COMPARE"))
                .andExpect(jsonPath("$.data[0].resources[1].mode").value("FULL"))
                .andExpect(jsonPath("$.data[1].id").value(recovery))
                .andExpect(jsonPath("$.data[1].requestedBy").value(nullValue()))
                .andExpect(jsonPath("$.data[1].resources[0].toLsn").value(24_678_912L))
                .andExpect(jsonPath("$.data[1].resources[0].fromLsn").value(nullValue()))
                .andExpect(jsonPath("$.data[1].resources[0].rowsRead").value(20))
                .andExpect(jsonPath("$.data[1].resources[0].rowsUpserted").value(18))
                .andExpect(jsonPath("$.data[1].resources[0].rowsDeleted").value(2))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.meta.cdcLastAppliedAt").value(LAST_COMMIT_AT));

        list("?triggerKind=CDC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(cdc))
                .andExpect(jsonPath("$.data[0].requestedBy").value(nullValue()))
                .andExpect(jsonPath("$.data[0].resources[0].mode").value("INCREMENTAL"))
                .andExpect(jsonPath("$.data[0].resources[0].fromLsn").value(100))
                .andExpect(jsonPath("$.data[0].resources[0].toLsn").value(180));
    }

    @Test
    void filtersByStatusTriggerPeriodAndPages() throws Exception {
        long table = resource("erp.work_order", "CDC");
        long old = run("RECOVERY", "SUCCEEDED", "3 days", null, null);
        fullResource(old, table, "SUCCEEDED", 10L, 1, 1, 0, null);
        long failed = run("MANUAL", "FAILED", "2 days", admin.accountId(), "실패");
        fullResource(failed, table, "FAILED", null, 0, 0, 0, "실패");
        long recent = run("MANUAL", "SUCCEEDED", "1 day", admin.accountId(), null);
        fullResource(recent, table, "SUCCEEDED", 20L, 1, 1, 0, null);

        list("?status=FAILED")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(failed));
        list("?triggerKind=RECOVERY")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(old));
        String twoDaysAgo = requestedAt(failed);
        list("?from=" + twoDaysAgo)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].id").value(failed));
        list("?to=" + twoDaysAgo)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(old));
        list("?size=1&page=1")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(failed))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
    }

    @Test
    void omitsCdcLastAppliedAtWhenSourceHasNoCdcConsumer() throws Exception {
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.cdcLastAppliedAt").doesNotExist());
    }

    /** 소비자가 위치만 저장하고(heartbeat 등) 아직 반영한 원천 트랜잭션이 없으면, 저장 시각을 반영 시각으로 보이지 않는다. */
    @Test
    void omitsCdcLastAppliedAtUntilFirstTransactionIsApplied() throws Exception {
        cdcOffset(null);

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.cdcLastAppliedAt").doesNotExist());
    }

    @Test
    void returnsEmptyPageForPageBeyondIntOffset() throws Exception {
        long table = resource("erp.work_order", "CDC");
        long run = run("MANUAL", "SUCCEEDED", "1 hour", admin.accountId(), null);
        fullResource(run, table, "SUCCEEDED", 10L, 1, 1, 0, null);

        // offset = 100,000,000 × 100 = 10^10 > Integer.MAX_VALUE
        list("?page=100000000&size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void readsOneRunWithTableResults() throws Exception {
        long table = resource("erp.work_order", "CDC");
        long running = run("MANUAL", "RUNNING", "1 minute", admin.accountId(), null);
        fullResource(running, table, "QUEUED", null, 0, 0, 0, null);

        mockMvc.perform(get("/api/v1/sync-runs/" + running).cookie(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(running))
                .andExpect(jsonPath("$.data.syncRunId").value(running))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.resources.length()").value(1))
                .andExpect(jsonPath("$.data.resources[0].syncRunId").value(running))
                .andExpect(jsonPath("$.data.resources[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.data.resources[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.resources[0].finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.resources[0].errorMessage").value(nullValue()));
        mockMvc.perform(get("/api/v1/sync-runs/999999999").cookie(admin.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidConditionsMissingSourceAndNonAdmin() throws Exception {
        LoginSession viewer = loginAs("VIEWER");

        for (String query :
                new String[] {
                    "?status=DONE",
                    "?triggerKind=SYNC",
                    "?page=-1",
                    "?size=0",
                    "?size=101",
                    "?from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z",
                    "?from=2026-11-01"
                }) {
            list(query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(BASE_PATH + "/999999999/sync-runs").cookie(admin.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE_PATH + "/" + sourceId + "/sync-runs").cookie(viewer.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/sync-runs/1").cookie(viewer.session()))
                .andExpect(status().isForbidden());
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(
                get(BASE_PATH + "/" + sourceId + "/sync-runs" + query).cookie(admin.session()));
    }

    private long resource(String key, String strategy) {
        return jdbcClient
                .sql(
                        """
                        insert into source_resource (data_source_id, resource_key, resource_kind,
                                                     is_readable, sync_strategy)
                        values (:s, :key, 'TABLE', true, :strategy) returning id
                        """)
                .param("s", sourceId)
                .param("key", key)
                .param("strategy", strategy)
                .query(Long.class)
                .single();
    }

    /** ago 전에 접수한 실행. 대기 중이 아니면 접수와 같은 시각에 시작했고, 끝났으면 1분 뒤 끝났다. */
    private long run(String trigger, String runStatus, String ago, Long requestedBy, String error) {
        return jdbcClient
                .sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, status, requested_by,
                                              requested_at, started_at, heartbeat_at, finished_at,
                                              error_message)
                        values (:s, :trigger, :status, :by, now() - cast(:ago as interval),
                                case when :status <> 'QUEUED' then now() - cast(:ago as interval) end,
                                case when :status = 'RUNNING' then now() end,
                                case when :status in ('SUCCEEDED', 'FAILED')
                                     then now() - cast(:ago as interval) + interval '1 minute' end,
                                :error)
                        returning id
                        """)
                .param("s", sourceId)
                .param("trigger", trigger)
                .param("status", runStatus)
                .param("by", requestedBy)
                .param("ago", ago)
                .param("error", error)
                .query(Long.class)
                .single();
    }

    private void fullResource(
            long runId,
            long resourceId,
            String resourceStatus,
            Long toLsn,
            int read,
            int upserted,
            int deleted,
            String error) {
        jdbcClient
                .sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id,
                                                       mode, status, to_lsn, rows_read, rows_upserted,
                                                       rows_deleted, started_at, finished_at,
                                                       error_message)
                        select s.id, s.data_source_id, :r, 'FULL', :status, :toLsn, :read, :upserted,
                               :deleted,
                               case when :status <> 'QUEUED' then s.started_at end,
                               case when :status in ('SUCCEEDED', 'FAILED') then s.finished_at end,
                               :error
                        from sync_run s where s.id = :run
                        """)
                .param("run", runId)
                .param("r", resourceId)
                .param("status", resourceStatus)
                .param("toLsn", toLsn)
                .param("read", read)
                .param("upserted", upserted)
                .param("deleted", deleted)
                .param("error", error)
                .update();
    }

    private void incrementalResource(long runId, long resourceId, long fromLsn, long toLsn) {
        jdbcClient
                .sql(
                        """
                        insert into sync_run_resource (sync_run_id, data_source_id, source_resource_id,
                                                       mode, status, from_lsn, to_lsn, rows_read,
                                                       rows_upserted, started_at, finished_at)
                        select s.id, s.data_source_id, :r, 'INCREMENTAL', 'SUCCEEDED', :fromLsn, :toLsn,
                               3, 3, s.started_at, s.finished_at
                        from sync_run s where s.id = :run
                        """)
                .param("run", runId)
                .param("r", resourceId)
                .param("fromLsn", fromLsn)
                .param("toLsn", toLsn)
                .update();
    }

    /** 소비자 상태 행. 저장 시각은 지금이고, lastCommitAt이 null이면 아직 반영한 원천 트랜잭션이 없다. */
    private void cdcOffset(String lastCommitAt) {
        jdbcClient
                .sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset,
                                                updated_at, last_commit_at)
                        values (:topic, 0, :s, 0, now(), cast(:commitAt as timestamptz))
                        """)
                .param("topic", "history-" + sourceId + ".all")
                .param("s", sourceId)
                .param("commitAt", lastCommitAt)
                .update();
    }

    private String requestedAt(long runId) {
        return jdbcClient
                .sql("select requested_at from sync_run where id = :id")
                .param("id", runId)
                .query((rs, row) -> rs.getTimestamp(1).toInstant().toString())
                .single();
    }
}
