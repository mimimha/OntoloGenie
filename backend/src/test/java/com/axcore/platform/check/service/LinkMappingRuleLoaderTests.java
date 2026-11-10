package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.check.CheckRunTestFixtures;
import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.domain.LinkMappingRule;
import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.ontology.domain.LinkCardinality;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 승인 규칙을 판정 엔진 입력으로 바꾸는 규칙(S15P31C106-190)을 실제 PostgreSQL 저장 형식으로 확인한다. */
@SpringBootTest
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@Transactional
class LinkMappingRuleLoaderTests {

    private static final String WORKER_TO_EMPLOYEE =
            "[{\"op\":\"PAD_ZEROS\",\"arg\":4},{\"op\":\"ADD_PREFIX\",\"arg\":\"GP\"}]";

    @Autowired LinkMappingRuleLoader loader;

    @Autowired RuleEvaluationEngine engine;

    @Autowired JdbcClient jdbcClient;

    private CheckRunTestFixtures fixtures;
    private long workerType;
    private long employeeType;
    private long workerId;
    private long employeeNo;
    private long linkType;
    private long workerTable;
    private long employeeTable;

    @BeforeEach
    void prepare() {
        fixtures = new CheckRunTestFixtures(jdbcClient);
        workerType = fixtures.insertObjectType("WORKER");
        employeeType = fixtures.insertObjectType("EMPLOYEE");
        workerId = fixtures.insertProperty(workerType, "id");
        employeeNo = fixtures.insertProperty(employeeType, "emp_no");
        linkType = fixtures.insertLinkType(workerType, employeeType, "MANY_TO_ONE");
        long source = fixtures.insertDataSource();
        workerTable = fixtures.insertResource(source, "mes.worker");
        employeeTable = fixtures.insertResource(source, "erp.employee");
        fixtures.insertObjectMapping(workerTable, workerType, true);
        fixtures.insertObjectMapping(employeeTable, employeeType, true);
    }

    @Test
    void convertsRuleToEngineInput() {
        long ruleId = rule("{\"status\": \"ACTIVE\"}");
        long otherTable = fixtures.insertResource(fixtures.insertDataSource(), "mes.other");
        CheckDataBasis runBasis =
                basis(
                        new ResourceCheckpoint(workerTable, 7L),
                        new ResourceCheckpoint(employeeTable, null),
                        new ResourceCheckpoint(otherTable, 9L));

        LinkMappingRule rule = loader.load(ruleId, runBasis);

        assertThat(rule.linkMappingId()).isEqualTo(ruleId);
        assertThat(rule.input().target().linkMappingId()).isEqualTo(ruleId);
        assertThat(rule.input().cardinality()).isEqualTo(LinkCardinality.MANY_TO_ONE);
        // 실행 전체 기준에서 이 규칙이 읽는 자원만 남긴다.
        assertThat(rule.input().dataBasis().resources())
                .containsExactly(
                        new ResourceCheckpoint(workerTable, 7L),
                        new ResourceCheckpoint(employeeTable, null));
        assertThat(rule.sourceType().id()).isEqualTo(workerType);
        assertThat(rule.sourceType().code()).isEqualTo(fixtures.typeCode(workerType));
        assertThat(rule.targetType().code()).isEqualTo(fixtures.typeCode(employeeType));
        assertThat(rule.sourceResourceIds()).containsExactly(workerTable);
        assertThat(rule.targetResourceIds()).containsExactly(employeeTable);
        assertThat(rule.sourceFilter()).containsEntry("status", "ACTIVE");
        assertThat(rule.targetFilter()).isEmpty();

        CheckCondition condition = rule.input().conditions().getFirst();
        assertThat(condition.sequenceNo()).isEqualTo(1);
        assertThat(condition.sourceProperty()).isEqualTo(fixtures.typeCode(workerType) + ".id");
        assertThat(condition.targetProperty())
                .isEqualTo(fixtures.typeCode(employeeType) + ".emp_no");
        assertThat(condition.sourceTransforms())
                .containsExactly(
                        new TransformStep(TransformOperation.PAD_ZEROS, 4),
                        new TransformStep(TransformOperation.ADD_PREFIX, "GP"));
        assertThat(condition.targetTransforms()).isEmpty();
    }

    @Test
    void producesInputTheEngineCanJudge() {
        long ruleId = rule("{}");
        LinkMappingRule rule =
                loader.load(
                        ruleId,
                        basis(
                                new ResourceCheckpoint(workerTable, 7L),
                                new ResourceCheckpoint(employeeTable, 8L)));
        String workerKey = fixtures.typeCode(workerType) + ".id";
        String employeeKey = fixtures.typeCode(employeeType) + ".emp_no";

        var evaluation =
                engine.evaluate(
                        new CheckInput(
                                rule.input(),
                                List.of(
                                        new CheckRow(
                                                workerTable,
                                                Map.of("id", 31),
                                                null,
                                                Map.of(workerKey, "31")),
                                        new CheckRow(
                                                workerTable,
                                                Map.of("id", 32),
                                                null,
                                                Map.of(workerKey, "32"))),
                                List.of(
                                        new CheckRow(
                                                employeeTable,
                                                Map.of("emp_no", "GP0031"),
                                                null,
                                                Map.of(employeeKey, "GP0031")))));

        assertThat(evaluation.rows())
                .extracting(row -> row.outcome())
                .containsExactly(CheckOutcome.MATCHED, CheckOutcome.UNMATCHED);
    }

    @Test
    void readsOnlyActiveMappings() {
        long ruleId = rule("{}");
        long oldTable = fixtures.insertResource(fixtures.insertDataSource(), "mes.worker_old");
        fixtures.insertObjectMapping(oldTable, workerType, false);

        assertThat(loader.resourceIds(ruleId)).containsExactly(workerTable, employeeTable);
    }

    @Test
    void rejectsBasisWithoutRuleResources() {
        long ruleId = rule("{}");

        assertThatThrownBy(
                        () -> loader.load(ruleId, basis(new ResourceCheckpoint(workerTable, 7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빠졌습니다");
    }

    @Test
    void rejectsRuleWithoutConditions() {
        long ruleId = fixtures.insertLinkMapping(linkType, "ACTIVE", "{}");

        assertThatThrownBy(() -> loader.load(ruleId, fullBasis()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비교 조건이 없습니다");
    }

    @Test
    void rejectsUnknownRule() {
        assertThatThrownBy(() -> loader.resourceIds(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnreadableStoredTransform() {
        long ruleId = fixtures.insertLinkMapping(linkType, "ACTIVE", "{}");
        fixtures.insertCondition(ruleId, 1, workerId, employeeNo, "[{\"op\":\"NOPE\"}]", "[]");

        assertThatThrownBy(() -> loader.load(ruleId, fullBasis()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("조건 1");
    }

    private long rule(String sourceFilter) {
        long ruleId = fixtures.insertLinkMapping(linkType, "ACTIVE", sourceFilter);
        fixtures.insertCondition(ruleId, 1, workerId, employeeNo, WORKER_TO_EMPLOYEE, "[]");
        return ruleId;
    }

    private CheckDataBasis fullBasis() {
        return basis(
                new ResourceCheckpoint(workerTable, null),
                new ResourceCheckpoint(employeeTable, null));
    }

    private static CheckDataBasis basis(ResourceCheckpoint... checkpoints) {
        return new CheckDataBasis(List.of(checkpoints));
    }
}
