package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.service.SyncCheckRequester.Result;
import com.axcore.platform.check.service.SyncCheckRequester.SyncRequest;
import com.axcore.platform.source.service.CdcReloadJob;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집 뒤 검사(SYNC) 요청(191-4)을 실제 PostgreSQL로 확인한다. 주기 실행(SyncCheckJob)은 시험 설정에서 꺼 두고 요청 로직을 직접 부른다.
 */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class SyncCheckRequesterTests {

    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired SyncCheckRequester requester;
    @Autowired QueuedJobRunner runner;
    @Autowired CheckRunJob job;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationContext context;

    private CheckRunTestFixtures fixtures;
    private long accountId;
    private long dataSource;
    private long woResource;
    private long rule;
    private long woMapping;
    private long woType;
    private long eqType;
    private long linkType;

    @BeforeEach
    void setUp() {
        // 앞 시험이 남긴 대기·실행 중 검사, 반영 중 규칙, 확인 대기를 정리한다. SYNC 범위는 반영 중인 규칙 전부다.
        jdbc.sql(
                        """
                        update check_run
                        set status = 'FAILED', started_at = coalesce(started_at, now()),
                            finished_at = now(), error_message = '시험 정리'
                        where status in ('QUEUED', 'RUNNING')
                        """)
                .update();
        jdbc.sql(
                        """
                        update link_check_pending
                        set status = 'TRANSIENT', close_reason = 'LINKED',
                            closed_at = greatest(now(), first_seen_at)
                        where status = 'WAITING'
                        """)
                .update();
        fixtures = new CheckRunTestFixtures(jdbc);
        fixtures.retireAllRules();
        accountId = fixtures.insertAdmin();
        woType = fixtures.insertObjectType("WO");
        eqType = fixtures.insertObjectType("EQ");
        long woEqCode = fixtures.insertProperty(woType, "eq_code");
        long eqCode = fixtures.insertProperty(eqType, "code");
        linkType = fixtures.insertLinkType(woType, eqType, "MANY_TO_ONE");
        dataSource = fixtures.insertDataSource();
        woResource = fixtures.insertResource(dataSource, "erp.work_order");
        long eqResource = fixtures.insertResource(dataSource, "erp.equipment");
        woMapping = fixtures.insertObjectMapping(woResource, woType, true);
        fixtures.insertObjectMapping(eqResource, eqType, true);
        fixtures.insertSync(dataSource, woResource, accountId, "SUCCEEDED", Instant.now());
        fixtures.insertSync(dataSource, eqResource, accountId, "SUCCEEDED", Instant.now());
        rule =
                new TransactionTemplate(transactionManager)
                        .execute(
                                tx -> {
                                    long id = fixtures.insertLinkMapping(linkType, "ACTIVE", "{}");
                                    fixtures.insertCondition(id, 1, woEqCode, eqCode, "[]", "[]");
                                    return id;
                                });
    }

    @Test
    void queuesSyncOfAllActiveRulesWithLastSuccessfulSyncBasis() {
        SyncRequest request = requester.request().orElseThrow();

        assertThat(request.result()).isEqualTo(Result.QUEUED);
        Map<String, Object> row =
                jdbc.sql(
                                """
                                select trigger_kind, status, requested_by, sync_run_id, work_key,
                                       scope::text as scope, jsonb_array_length(data_basis -> 'resources') as resources
                                from check_run where id = :id
                                """)
                        .param("id", request.checkRunId())
                        .query()
                        .singleRow();
        assertThat(row)
                .containsEntry("trigger_kind", "SYNC")
                .containsEntry("status", "QUEUED")
                .containsEntry("requested_by", null)
                .containsEntry("sync_run_id", null)
                .containsEntry("resources", 2);
        assertThat((String) row.get("scope")).contains("\"ALL\"").contains("[" + rule + "]");
        assertThat((String) row.get("work_key")).startsWith("SYNC:-:").doesNotContain(":t");
    }

    @Test
    void joinsQueuedSyncWithSameBasis() {
        long first = requester.request().orElseThrow().checkRunId();

        SyncRequest second = requester.request().orElseThrow();

        assertThat(second.result()).isEqualTo(Result.JOINED);
        assertThat(second.checkRunId()).isEqualTo(first);
    }

    @Test
    void reusesSucceededSyncUntilDataChanges() {
        long first = requester.request().orElseThrow().checkRunId();
        assertThat(runner.runNext(job)).isTrue();
        assertThat(status(first)).isEqualTo("SUCCEEDED");

        SyncRequest unchanged = requester.request().orElseThrow();
        fixtures.insertSync(dataSource, woResource, accountId, "SUCCEEDED", Instant.now());
        SyncRequest changed = requester.request().orElseThrow();

        assertThat(unchanged.result()).isEqualTo(Result.REUSED);
        assertThat(unchanged.checkRunId()).isEqualTo(first);
        assertThat(changed.result()).isEqualTo(Result.QUEUED);
        assertThat(changed.checkRunId()).isNotEqualTo(first);
    }

    @Test
    void manualLinkChangeQueuesNewSyncInsteadOfReusingOrJoining() {
        long first = requester.request().orElseThrow().checkRunId();
        long wo =
                fixtures.insertObject(woMapping, woType, "WO9", "{\"eq_code\": \"E9\"}", "ACTIVE");
        long eq = fixtures.insertObject(null, eqType, "EQ9", "{\"code\": \"X9\"}", "ACTIVE");

        // 수동 연결을 만들면 수집 실행이 없어도 기준이 바뀐다(367). 대기 중인 SYNC에 합류하지 않는다.
        long link =
                jdbc.sql(
                                """
                                insert into object_link (link_type_id, source_object_id, target_object_id, origin, confirmed_by)
                                values (:type, :source, :target, 'MANUAL', :account) returning id
                                """)
                        .param("type", linkType)
                        .param("source", wo)
                        .param("target", eq)
                        .param("account", accountId)
                        .query(Long.class)
                        .single();
        SyncRequest afterLink = requester.request().orElseThrow();

        // 수동 연결을 끄면 다시 바뀐다.
        jdbc.sql(
                        "update object_link set status = 'INACTIVE', inactivated_at = now(), updated_at = now() where id = :id")
                .param("id", link)
                .update();
        SyncRequest afterInactivate = requester.request().orElseThrow();

        assertThat(afterLink.result()).isEqualTo(Result.QUEUED);
        assertThat(afterLink.checkRunId()).isNotEqualTo(first);
        assertThat(afterInactivate.result()).isEqualTo(Result.QUEUED);
        assertThat(afterInactivate.checkRunId()).isNotIn(first, afterLink.checkRunId());
    }

    @Test
    void queuesNewSyncEachIntervalWhilePendingWaits() {
        long first = requester.request().orElseThrow().checkRunId();
        runner.runNext(job);
        long object = fixtures.insertObject(woMapping, woType, "WO1", "{}", "ACTIVE");
        jdbc.sql(
                        """
                        insert into link_check_pending (link_mapping_id, source_object_id, last_check_run_id)
                        values (:rule, :object, :run)
                        """)
                .param("rule", rule)
                .param("object", object)
                .param("run", first)
                .update();

        SyncRequest waiting = requester.request().orElseThrow();
        SyncRequest sameInterval = requester.request().orElseThrow();

        // 데이터 기준은 그대로지만 승격을 확인해야 하므로 성공한 SYNC를 다시 쓰지 않는다. 같은 주기 구간 안에서는 합류한다.
        assertThat(waiting.result()).isEqualTo(Result.QUEUED);
        assertThat(workKey(waiting.checkRunId())).contains(":t");
        assertThat(sameInterval.result()).isEqualTo(Result.JOINED);
        assertThat(sameInterval.checkRunId()).isEqualTo(waiting.checkRunId());
    }

    @Test
    void periodicJobIsOffInTests() {
        // 시험 설정(classpath:/config/application-batch.yml)이 주기 실행을 끈다. 켜면 시험이 넣은 검사 사이에 SYNC가 끼어든다.
        assertThat(context.getBeanNamesForType(SyncCheckJob.class)).isEmpty();
    }

    @Test
    void queuesNothingWithoutActiveRules() {
        fixtures.retireAllRules();

        assertThat(requester.request()).isEmpty();
    }

    private String status(long runId) {
        return jdbc.sql("select status from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private String workKey(long runId) {
        return jdbc.sql("select work_key from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }
}
