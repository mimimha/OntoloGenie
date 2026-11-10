package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.source.service.CdcReloadJob;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SYNC·수동 검사의 판정(191-3)을 실제 PostgreSQL로 확인한다: 같은 원천 규칙은 바로 issue, 원천 사이 규칙의 미연결은 확인 대기,
 * 건너뛰기·보류·승격·닫기.
 *
 * <p>장면은 {@link CheckRunJobTests}와 같다. 작업지시(WO)의 eq_code가 설비(EQ)의 code와 같으면 연결되는 MANY_TO_ONE 규칙이고,
 * WO1은 정상, WO2는 미연결, WO3은 설비 E3이 둘이라 중복이다. 작업지시 표는 ERP 원천의 CDC 표다. 설비 표는 같은 원천의 CDC 표(같은 원천 규칙)이거나
 * MES 원천의 FULL_COMPARE 표(원천 사이 규칙)다.
 */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class CheckIssueJudgmentTests {

    @MockitoBean QueuedJobPoller queuedJobPoller;
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired QueuedJobRunner runner;
    @Autowired CheckRunJob job;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private CheckRunTestFixtures fixtures;
    private long accountId;
    private long erp;
    private long woType;
    private long eqType;
    private long woEqCode;
    private long eqCode;
    private long woResource;
    private long eqResource;
    private long woMapping;
    private long eqMapping;
    private long linkType;
    private long wo2;
    private long wo3;

    @BeforeEach
    void setUp() {
        jdbc.sql(
                        """
                        update check_run
                        set status = 'FAILED', started_at = coalesce(started_at, now()),
                            finished_at = now(), error_message = '시험 정리'
                        where status in ('QUEUED', 'RUNNING')
                        """)
                .update();
        fixtures = new CheckRunTestFixtures(jdbc);
        accountId = fixtures.insertAdmin();
        woType = fixtures.insertObjectType("WO");
        eqType = fixtures.insertObjectType("EQ");
        woEqCode = fixtures.insertProperty(woType, "eq_code");
        eqCode = fixtures.insertProperty(eqType, "code");
        linkType = fixtures.insertLinkType(woType, eqType, "MANY_TO_ONE");
        erp = fixtures.insertDataSource();
        woResource = fixtures.insertResource(erp, "erp.work_order");
        makeCdc(woResource);
        healthy(erp, Instant.now().minus(Duration.ofHours(1)));
        woMapping = fixtures.insertObjectMapping(woResource, woType, true);
    }

    /** 설비 표를 작업지시와 같은 ERP 원천의 CDC 표로 둔다. */
    private void sameSource() {
        eqResource = fixtures.insertResource(erp, "erp.equipment");
        makeCdc(eqResource);
        objects();
    }

    /** 설비 표를 MES 원천의 FULL_COMPARE 표로 두고, lastReadAt에 마지막 성공 수집이 끝났다. */
    private void crossSource(Instant lastReadAt) {
        long mes = fixtures.insertDataSource();
        eqResource = fixtures.insertResource(mes, "mes.equipment");
        fixtures.insertSync(mes, eqResource, accountId, "SUCCEEDED", lastReadAt);
        objects();
    }

    private void objects() {
        eqMapping = fixtures.insertObjectMapping(eqResource, eqType, true);
        fixtures.insertObject(woMapping, woType, "WO1", "{\"eq_code\": \"E1\"}", "ACTIVE");
        wo2 = fixtures.insertObject(woMapping, woType, "WO2", "{\"eq_code\": \"E2\"}", "ACTIVE");
        wo3 = fixtures.insertObject(woMapping, woType, "WO3", "{\"eq_code\": \"E3\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ1", "{\"code\": \"E1\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ3a", "{\"code\": \"E3\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ3b", "{\"code\": \"E3\"}", "ACTIVE");
    }

    @Test
    void sameSourceRuleRegistersEveryProblemAsIssueOnce() {
        sameSource();
        long rule = rule("ACTIVE");

        long first = run("SYNC", rule);
        long second = run("SYNC", rule);

        assertThat(status(first)).isEqualTo("SUCCEEDED");
        assertThat(issuesOf(rule))
                .extracting(Issue::kind, Issue::objectId, Issue::detectedRunId)
                .containsExactlyInAnyOrder(
                        tuple("UNMATCHED", wo2, first), tuple("DUPLICATE", wo3, first));
        assertThat(status(second)).isEqualTo("SUCCEEDED");
        assertThat(pendingsOf(rule)).isEmpty();
    }

    @Test
    void crossSourceRuleSendsUnmatchedToPendingAndDuplicateToIssue() {
        crossSource(Instant.now());
        long rule = rule("ACTIVE");

        long runId = run("SYNC", rule);

        assertThat(issuesOf(rule)).extracting(Issue::kind).containsExactly("DUPLICATE");
        assertThat(pendingsOf(rule))
                .extracting(Pending::objectId, Pending::status, Pending::lastRunId)
                .containsExactly(tuple(wo2, "WAITING", runId));
    }

    @Test
    void closesPendingWhenLinkedOrSourceDeleted() {
        crossSource(Instant.now());
        long rule = rule("ACTIVE");
        long wo4 =
                fixtures.insertObject(woMapping, woType, "WO4", "{\"eq_code\": \"E4\"}", "ACTIVE");
        run("SYNC", rule);
        fixtures.insertObject(eqMapping, eqType, "EQ2", "{\"code\": \"E2\"}", "ACTIVE");
        jdbc.sql("update object_instance set status = 'DELETED_AT_SOURCE' where id = :id")
                .param("id", wo4)
                .update();

        run("SYNC", rule);

        assertThat(pendingsOf(rule))
                .extracting(Pending::objectId, Pending::status, Pending::closeReason)
                .containsExactlyInAnyOrder(
                        tuple(wo2, "TRANSIENT", "LINKED"),
                        tuple(wo4, "TRANSIENT", "SOURCE_DELETED"));
        assertThat(issuesOf(rule)).extracting(Issue::kind).containsExactly("DUPLICATE");
    }

    @Test
    void promotesPendingAfterWaitWhenSourcesStayHealthy() {
        Instant firstSeen = Instant.now().minus(Duration.ofMinutes(30));
        crossSource(Instant.now().minus(Duration.ofMinutes(5)));
        long rule = rule("ACTIVE");
        run("SYNC", rule);
        setFirstSeen(rule, firstSeen);

        long runId = run("SYNC", rule);

        List<Issue> unmatched =
                issuesOf(rule).stream().filter(i -> i.kind().equals("UNMATCHED")).toList();
        assertThat(unmatched)
                .extracting(Issue::objectId, Issue::detectedRunId)
                .containsExactly(tuple(wo2, runId));
        assertThat(pendingsOf(rule))
                .extracting(Pending::status, Pending::closeReason, Pending::issueId)
                .containsExactly(tuple("PROMOTED", "PROMOTED", unmatched.getFirst().id()));
    }

    @Test
    void doesNotPromoteWithoutFullReadAfterFirstSeenOrWhenCdcSourceUnhealthy() {
        crossSource(Instant.now().minus(Duration.ofHours(2)));
        long rule = rule("ACTIVE");
        run("SYNC", rule);
        setFirstSeen(rule, Instant.now().minus(Duration.ofMinutes(30)));

        // FULL_COMPARE 표의 마지막 성공 수집이 처음 본 시각보다 앞선다.
        run("SYNC", rule);
        assertThat(pendingsOf(rule)).extracting(Pending::status).containsExactly("WAITING");

        // 수집은 새로 됐지만 CDC 원천이 지금 비정상이다.
        long mes =
                jdbc.sql("select data_source_id from source_resource where id = :id")
                        .param("id", eqResource)
                        .query(Long.class)
                        .single();
        fixtures.insertSync(mes, eqResource, accountId, "SUCCEEDED", Instant.now());
        jdbc.sql("update cdc_offset set healthy_since = null where data_source_id = :id")
                .param("id", erp)
                .update();
        run("SYNC", rule);
        assertThat(pendingsOf(rule)).extracting(Pending::status).containsExactly("WAITING");
        assertThat(issuesOf(rule)).extracting(Issue::kind).containsExactly("DUPLICATE");
    }

    @Test
    void holdsUnmatchedWhenTargetTableHasOpenKeyFailure() {
        sameSource();
        long rule = rule("ACTIVE");
        long syncRun = fixtures.insertSync(erp, eqResource, accountId, "SUCCEEDED", Instant.now());
        jdbc.sql(
                        """
                        insert into source_load_failure (
                            source_resource_id, object_source_mapping_id, source_key, column_name,
                            reason, last_sync_run_id
                        )
                        values (:resource, :mapping, null, 'code', 'KEY_MISSING', :run)
                        """)
                .param("resource", eqResource)
                .param("mapping", eqMapping)
                .param("run", syncRun)
                .update();

        long runId = run("SYNC", rule);

        assertThat(status(runId)).isEqualTo("SUCCEEDED");
        assertThat(issuesOf(rule)).extracting(Issue::kind).containsExactly("DUPLICATE");
        assertThat(pendingsOf(rule)).isEmpty();
    }

    @Test
    void skipsRuleWhileCdcTableIsNotLoadedOrSourceNeedsRecovery() {
        sameSource();
        long rule = rule("ACTIVE");
        jdbc.sql("update source_resource set loaded_lsn = null where id = :id")
                .param("id", eqResource)
                .update();

        long notLoaded = run("SYNC", rule);

        // SYNC는 모든 규칙을 건너뛰어도 결과 없는 성공이다. 이유는 감사 결과에만 남는다.
        assertThat(status(notLoaded)).isEqualTo("SUCCEEDED");
        assertThat(resultCount(notLoaded)).isZero();
        assertThat(auditResult(notLoaded)).contains("전체 적재 전인 CDC 표");

        makeCdc(eqResource);
        jdbc.sql(
                        """
                        update cdc_offset set healthy_since = null, recovery_reason = 'SLOT_LOST',
                                              recovery_at = now()
                        where data_source_id = :id
                        """)
                .param("id", erp)
                .update();
        long recovering = run("SYNC", rule);

        assertThat(auditResult(recovering)).contains("복구가 필요한 원천");
        assertThat(issuesOf(rule)).isEmpty();
    }

    @Test
    void closesPendingOfRetiredRule() {
        crossSource(Instant.now());
        long retiring = rule("ACTIVE");
        run("SYNC", retiring);
        jdbc.sql("update link_mapping set status = 'RETIRED', retired_at = now() where id = :id")
                .param("id", retiring)
                .update();
        long other = rule("ACTIVE");

        run("SYNC", other);

        assertThat(pendingsOf(retiring))
                .extracting(Pending::status, Pending::closeReason)
                .containsExactly(tuple("TRANSIENT", "RULE_RETIRED"));
    }

    @Test
    void reverifyDoesNotRegisterIssues() {
        sameSource();
        long rule = rule("ACTIVE");

        // 재검증은 비교 대상 검사가 있어야 한다.
        fixtures.insertRun("MANUAL", "SUCCEEDED", accountId, "MANUAL:" + UUID.randomUUID());

        long runId = run("REVERIFY", rule);

        assertThat(status(runId)).isEqualTo("SUCCEEDED");
        assertThat(issuesOf(rule)).isEmpty();
    }

    private long rule(String status) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        tx -> {
                            long rule = fixtures.insertLinkMapping(linkType, status, "{}");
                            fixtures.insertCondition(rule, 1, woEqCode, eqCode, "[]", "[]");
                            return rule;
                        });
    }

    /** 검사 하나를 넣고 실행기로 돌린다. */
    private long run(String triggerKind, long ruleId) {
        String scope = "{\"kind\": \"LINK_MAPPINGS\", \"linkMappingIds\": [" + ruleId + "]}";
        String basis =
                "{\"resources\": [{\"sourceResourceId\": %d}, {\"sourceResourceId\": %d}]}"
                        .formatted(woResource, eqResource);
        long runId =
                fixtures.insertQueuedRun(
                        triggerKind, scope, basis, triggerKind.equals("SYNC") ? null : accountId);
        assertThat(runner.runNext(job)).isTrue();
        return runId;
    }

    private void makeCdc(long resourceId) {
        jdbc.sql("update source_resource set sync_strategy = 'CDC', loaded_lsn = 1 where id = :id")
                .param("id", resourceId)
                .update();
    }

    private void healthy(long dataSourceId, Instant since) {
        jdbc.sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset, healthy_since)
                        values (:topic, 0, :source, 0, :since)
                        """)
                .param("topic", "test-" + UUID.randomUUID())
                .param("source", dataSourceId)
                .param("since", Timestamp.from(since), Types.TIMESTAMP)
                .update();
    }

    private void setFirstSeen(long ruleId, Instant firstSeen) {
        jdbc.sql("update link_check_pending set first_seen_at = :at where link_mapping_id = :rule")
                .param("at", Timestamp.from(firstSeen), Types.TIMESTAMP)
                .param("rule", ruleId)
                .update();
    }

    private List<Issue> issuesOf(long ruleId) {
        return jdbc.sql(
                        """
                        select id, issue_kind, object_instance_id, detected_check_run_id
                        from issue where link_mapping_id = :rule order by id
                        """)
                .param("rule", ruleId)
                .query(
                        (rs, row) ->
                                new Issue(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getLong(3),
                                        rs.getLong(4)))
                .list();
    }

    private List<Pending> pendingsOf(long ruleId) {
        return jdbc.sql(
                        """
                        select source_object_id, status, close_reason, last_check_run_id, issue_id
                        from link_check_pending where link_mapping_id = :rule order by id
                        """)
                .param("rule", ruleId)
                .query(
                        (rs, row) ->
                                new Pending(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getLong(4),
                                        rs.getObject(5, Long.class)))
                .list();
    }

    private String status(long runId) {
        return jdbc.sql("select status from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private int resultCount(long runId) {
        return jdbc.sql("select count(*) from check_result where check_run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single();
    }

    /** 배치가 남긴 감사 결과 줄(SYSTEM + BATCH)의 result. */
    private String auditResult(long runId) {
        return jdbc.sql(
                        """
                        select result::text from action_execution
                        where target_type = 'check_run' and target_id = :id
                        order by id desc limit 1
                        """)
                .param("id", runId)
                .query(String.class)
                .single();
    }

    record Issue(long id, String kind, long objectId, long detectedRunId) {}

    record Pending(
            long objectId, String status, String closeReason, long lastRunId, Long issueId) {}
}
