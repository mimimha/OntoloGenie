package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.batch.service.QueuedJobRunner;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.service.CheckRunJob.Outcome;
import com.axcore.platform.check.service.CheckRunJob.SkippedRule;
import com.axcore.platform.source.service.CdcReloadJob;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
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
 * 검사 실행기(191-2)를 실제 PostgreSQL로 확인한다. 작업 표에서 가져가 객체로 판정하고 규칙별 결과를 저장하기까지를 {@link QueuedJobRunner}로
 * 돌린다.
 *
 * <p>기본 장면: 작업지시(WO, 출발)의 eq_code가 설비(EQ, 도착)의 code와 같으면 연결되는 MANY_TO_ONE 규칙. WO1은 E1 하나와 맞고(정상),
 * WO2는 맞는 설비가 없고(미연결), WO3은 E3이 둘이라 중복이다.
 */
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class CheckRunJobTests {

    // 주기 실행이 시험이 넣은 검사를 먼저 가져가지 않게 막는다.
    @MockitoBean QueuedJobPoller queuedJobPoller;
    // 감시 작업이 시험 데이터에 다시 읽기를 넣지 않게 막는다.
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired QueuedJobRunner runner;
    @Autowired CheckRunJob job;
    @Autowired CheckResultRepository results;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private CheckRunTestFixtures fixtures;
    private long accountId;
    private long woType;
    private long eqType;
    private long woResource;
    private long eqResource;
    private long woMapping;
    private long eqMapping;
    private long woEqCode;
    private long eqCode;
    private long linkType;

    @BeforeEach
    void setUp() {
        // 앞 시험이 남긴 대기·실행 중 검사를 끝낸다. runNext는 가장 먼저 들어온 검사를 가져간다.
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
        fixtures.insertProperty(woType, "status");
        eqCode = fixtures.insertProperty(eqType, "code");
        long dataSource = fixtures.insertDataSource();
        woResource = fixtures.insertResource(dataSource, "mes.work_order");
        eqResource = fixtures.insertResource(dataSource, "mes.equipment");
        woMapping = fixtures.insertObjectMapping(woResource, woType, true);
        eqMapping = fixtures.insertObjectMapping(eqResource, eqType, true);
        linkType = fixtures.insertLinkType(woType, eqType, "MANY_TO_ONE");

        fixtures.insertObject(
                woMapping, woType, "WO1", "{\"eq_code\": \"E1\", \"status\": \"OPEN\"}", "ACTIVE");
        fixtures.insertObject(
                woMapping, woType, "WO2", "{\"eq_code\": \"E2\", \"status\": \"OPEN\"}", "ACTIVE");
        fixtures.insertObject(
                woMapping, woType, "WO3", "{\"eq_code\": \"E3\", \"status\": \"DONE\"}", "ACTIVE");
        fixtures.insertObject(
                woMapping, woType, "WO4", "{\"eq_code\": \"E9\"}", "DELETED_AT_SOURCE");
        fixtures.insertObject(null, woType, "WO5", "{\"eq_code\": \"E8\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ1", "{\"code\": \"E1\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ3a", "{\"code\": \"E3\"}", "ACTIVE");
        fixtures.insertObject(eqMapping, eqType, "EQ3b", "{\"code\": \"E3\"}", "ACTIVE");
    }

    @Test
    void savesResultPerRuleFromActiveSourceObjects() {
        long rule = rule("{}");
        long runId = queue("MANUAL", rule);

        assertThat(runner.runNext(job)).isTrue();

        assertThat(status(runId)).isEqualTo("SUCCEEDED");
        List<CheckResult> saved = resultsOf(runId);
        assertThat(saved).hasSize(1);
        CheckResult result = saved.getFirst();
        assertThat(result.getLinkMappingId()).isEqualTo(rule);
        assertThat(result.getPreviousResultId()).isNull();
        // 원천에서 지운 WO4와 플랫폼에서 만든 WO5는 판정하지 않는다.
        assertThat(
                        List.of(
                                result.getTotalCount(),
                                result.getMatchedCount(),
                                result.getUnmatchedCount(),
                                result.getDuplicateCount(),
                                result.getViolationCount()))
                .containsExactly(3, 1, 1, 1, 0);
        assertThat(result.getSampleRows())
                .extracting(
                        s -> s.get("sourceKey"),
                        s -> s.get("classification"),
                        s -> s.get("rawValue"),
                        s -> s.get("transformedValue"))
                .containsExactly(
                        tuple(Map.of("id", "WO2"), "UNMATCHED", "E2", "E2"),
                        tuple(Map.of("id", "WO3"), "DUPLICATE", "E3", "E3"));
        assertThat((List<?>) result.getSampleRows().get(1).get("targetObjectIds")).hasSize(2);
    }

    @Test
    void appliesRuleRowFilterToObjects() {
        long rule =
                rule(
                        "{\"property\": \""
                                + fixtures.typeCode(woType)
                                + ".status\", \"equals\": \"OPEN\"}");
        long runId = queue("MANUAL", rule);

        runner.runNext(job);

        CheckResult result = resultsOf(runId).getFirst();
        // OPEN인 WO1(정상)·WO2(미연결)만 본다.
        assertThat(
                        List.of(
                                result.getTotalCount(),
                                result.getMatchedCount(),
                                result.getUnmatchedCount()))
                .containsExactly(2, 1, 1);
    }

    @Test
    void ignoresObjectsFromInactiveMappings() {
        long rule = rule("{}");
        long otherResource = fixtures.insertResource(fixtures.insertDataSource(), "old.equipment");
        long inactive = fixtures.insertObjectMapping(otherResource, eqType, false);
        fixtures.insertObject(inactive, eqType, "OLD2", "{\"code\": \"E2\"}", "ACTIVE");
        long runId = queue("MANUAL", rule);

        runner.runNext(job);

        // 비활성 매핑에서 온 설비 E2는 도착으로 보지 않아 WO2는 여전히 미연결이다.
        assertThat(resultsOf(runId).getFirst().getUnmatchedCount()).isEqualTo(1);
    }

    @Test
    void linksPreviousResultOfSameRuleRevision() {
        long rule = rule("{}");
        long first = queue("MANUAL", rule);
        runner.runNext(job);
        long second = queue("REVERIFY", rule);

        runner.runNext(job);

        assertThat(status(second)).isEqualTo("SUCCEEDED");
        assertThat(resultsOf(second).getFirst().getPreviousResultId())
                .isEqualTo(resultsOf(first).getFirst().getId());
    }

    @Test
    void skipsRulesThatCannotBeCheckedAndChecksTheRest() {
        long good = rule("{}");
        long retired = rule("RETIRED", "{}", "[]");
        long badFilter = rule("{\"property\": \"OTHER.status\", \"equals\": \"OPEN\"}");
        long badTransform = rule("ACTIVE", "{}", "[{\"op\": \"NO_SUCH_OP\"}]");
        long runId = queue("MANUAL", good, retired, badFilter, badTransform);
        markRunning(runId);

        Outcome outcome = job.run(runId);

        assertThat(outcome.evaluated()).hasSize(1);
        assertThat(outcome.skipped())
                .extracting(SkippedRule::linkMappingId)
                .containsExactlyInAnyOrder(retired, badFilter, badTransform);
        assertThat(outcome.skipped())
                .filteredOn(s -> s.linkMappingId() == retired)
                .extracting(SkippedRule::reason)
                .containsExactly(CheckRunJob.NOT_ACTIVE);
        assertThat(job.auditResult(outcome))
                .containsEntry("checkedRuleCount", 1)
                .extractingByKey("skippedRules")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(3);
    }

    @Test
    void failsWhenNoRuleCanBeChecked() {
        long retired = rule("RETIRED", "{}", "[]");
        long runId = queue("MANUAL", retired);

        runner.runNext(job);

        assertThat(status(runId)).isEqualTo("FAILED");
        assertThat(errorMessage(runId)).startsWith("검사할 수 있는 규칙이 없습니다.");
        assertThat(resultsOf(runId)).isEmpty();
    }

    @Test
    void leavesCandidateTrialsQueued() {
        long candidate = fixtures.insertQueuedRun("CANDIDATE", "{}", "{}", null);

        assertThat(runner.runNext(job)).isFalse();

        assertThat(status(candidate)).isEqualTo("QUEUED");
    }

    /** 활성 규칙: WO.eq_code = EQ.code, 변환 없음. */
    private long rule(String sourceFilter) {
        return rule("ACTIVE", sourceFilter, "[]");
    }

    /** 규칙 개정본의 비교 조건은 개정본을 만든 트랜잭션에서만 넣을 수 있다(트리거). */
    private long rule(String status, String sourceFilter, String sourceTransforms) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        tx -> {
                            long rule = fixtures.insertLinkMapping(linkType, status, sourceFilter);
                            fixtures.insertCondition(
                                    rule, 1, woEqCode, eqCode, sourceTransforms, "[]");
                            return rule;
                        });
    }

    private long queue(String triggerKind, long... ruleIds) {
        String ids =
                java.util.Arrays.stream(ruleIds)
                        .mapToObj(String::valueOf)
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
        String scope = "{\"kind\": \"LINK_MAPPINGS\", \"linkMappingIds\": [" + ids + "]}";
        String basis =
                "{\"resources\": [{\"sourceResourceId\": %d, \"lastSuccessfulSyncRunId\": null},"
                                .formatted(woResource)
                        + " {\"sourceResourceId\": %d, \"lastSuccessfulSyncRunId\": null}]}"
                                .formatted(eqResource);
        return fixtures.insertQueuedRun(triggerKind, scope, basis, accountId);
    }

    private void markRunning(long runId) {
        jdbc.sql("update check_run set status = 'RUNNING', started_at = now() where id = :id")
                .param("id", runId)
                .update();
    }

    private List<CheckResult> resultsOf(long runId) {
        return results.findAll().stream().filter(r -> r.getCheckRunId() == runId).toList();
    }

    private String status(long runId) {
        return jdbc.sql("select status from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    private String errorMessage(long runId) {
        return jdbc.sql("select error_message from check_run where id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }
}
