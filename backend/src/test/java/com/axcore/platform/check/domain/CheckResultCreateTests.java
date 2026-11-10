package com.axcore.platform.check.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 판정 엔진 결과로 검사 결과를 만들어 JPA로 저장한다(191 실행기·후보 시험 실행기 공용 생성 메서드). */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CheckResultCreateTests {

    private static final long RESOURCE = 1;

    @Autowired CheckResultRepository repository;
    @Autowired JdbcClient jdbc;

    private CheckRunTestFixtures fixtures;
    private long runId;
    private long ruleId;

    @BeforeEach
    void setUp() {
        fixtures = new CheckRunTestFixtures(jdbc);
        long accountId = fixtures.insertAdmin();
        runId = fixtures.insertRun("MANUAL", "RUNNING", accountId, "MANUAL:" + UUID.randomUUID());
        long source = fixtures.insertObjectType("WO");
        long target = fixtures.insertObjectType("EQ");
        ruleId =
                fixtures.insertLinkMapping(
                        fixtures.insertLinkType(source, target, "MANY_TO_ONE"), "ACTIVE", "{}");
    }

    @Test
    void savesRuleResultWithCountsTargetAndSamplesFromEvaluation() {
        CheckEvaluation evaluation =
                evaluation(
                        CheckTarget.forLinkMapping(ruleId),
                        CheckOutcome.MATCHED,
                        CheckOutcome.MATCHED,
                        CheckOutcome.UNMATCHED,
                        CheckOutcome.DUPLICATE,
                        CheckOutcome.VIOLATION);
        Map<String, Object> sample = new HashMap<>();
        sample.put("rawValue", "W23");
        sample.put("transformedValue", null);
        sample.put("classification", "UNMATCHED");
        sample.put("reason", "대상 객체 없음");

        CheckResult first =
                repository.saveAndFlush(CheckResult.of(runId, null, evaluation, List.of(sample)));
        CheckResult second =
                repository.saveAndFlush(
                        CheckResult.of(runId, first.getId(), evaluation, List.of()));

        CheckResult loaded = repository.findById(first.getId()).orElseThrow();
        assertThat(loaded.getCheckRunId()).isEqualTo(runId);
        assertThat(loaded.getLinkMappingId()).isEqualTo(ruleId);
        assertThat(loaded.getProposalItemId()).isNull();
        assertThat(loaded.getProposalItemRevision()).isNull();
        assertThat(
                        List.of(
                                loaded.getTotalCount(),
                                loaded.getMatchedCount(),
                                loaded.getUnmatchedCount(),
                                loaded.getDuplicateCount(),
                                loaded.getViolationCount()))
                .containsExactly(5, 2, 1, 1, 1);
        assertThat(loaded.getSampleRows()).hasSize(1);
        assertThat(loaded.getSampleRows().getFirst())
                .containsEntry("rawValue", "W23")
                .containsEntry("transformedValue", null)
                .containsEntry("classification", "UNMATCHED");
        assertThat(repository.findById(second.getId()).orElseThrow().getPreviousResultId())
                .isEqualTo(first.getId());
    }

    @Test
    void takesProposalTargetFromEvaluation() {
        CheckResult result =
                CheckResult.of(
                        runId,
                        null,
                        evaluation(CheckTarget.forProposal(7, 3), CheckOutcome.UNMATCHED),
                        List.of());

        assertThat(result.getLinkMappingId()).isNull();
        assertThat(result.getProposalItemId()).isEqualTo(7);
        assertThat(result.getProposalItemRevision()).isEqualTo(3);
        assertThat(result.getTotalCount()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidIds() {
        CheckEvaluation evaluation = evaluation(CheckTarget.forLinkMapping(ruleId));

        assertThatThrownBy(() -> CheckResult.of(0, null, evaluation, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckResult.of(runId, 0L, evaluation, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckResult.of(runId, null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    private static CheckEvaluation evaluation(CheckTarget target, CheckOutcome... outcomes) {
        CheckRuleInput rule =
                new CheckRuleInput(
                        target,
                        new CheckDataBasis(List.of(new ResourceCheckpoint(RESOURCE, null))),
                        LinkCardinality.MANY_TO_ONE,
                        List.of(
                                new CheckCondition(
                                        1, "WO.eq_id", "EQ.id", "EQ", List.of(), List.of())));
        List<RowResult> rows = new java.util.ArrayList<>();
        int key = 1;
        for (CheckOutcome outcome : outcomes) {
            CheckRow row =
                    new CheckRow(RESOURCE, Map.of("id", String.valueOf(key++)), null, Map.of());
            rows.add(new RowResult(row, outcome, Map.of(), List.of(), outcome.name()));
        }
        return new CheckEvaluation(rule, rows);
    }
}
