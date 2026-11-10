package com.axcore.platform.check.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.domain.LinkMappingRule;
import com.axcore.platform.check.domain.LinkMappingRule.ObjectTypeRef;
import com.axcore.platform.check.domain.ObjectRowFilter;
import com.axcore.platform.check.repository.CheckObjectReader;
import com.axcore.platform.check.repository.CheckObjectReader.CheckObject;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.repository.CheckRunRepository.RunToExecute;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검사 실행(check_run)을 배치에서 돌린다(S15P31C106-191, FN-CHK-01·02). 수동 검사(MANUAL)·재검증(REVERIFY)·수집 뒤
 * 검사(SYNC)가 같은 경로를 쓴다. 승인 전 후보 시험(CANDIDATE)은 원천을 읽어야 해서 가져가지 않는다({@link #claimCondition}).
 *
 * <p><b>run</b>(읽기 전용 REPEATABLE READ 트랜잭션 하나): 규칙과 객체를 한 스냅샷에서 읽는다(결정 문서 2026-11-05 8장).
 *
 * <ol>
 *   <li>접수 때 정해 둔 규칙 개정본 목록({@code scope.linkMappingIds})을 읽는다.
 *   <li>규칙마다 190 변환기로 판정 엔진 입력을 만들고, 출발·도착 유형의 활성 객체를 읽어 행 조건을 적용한 뒤 판정한다. 검사는 원천이 아니라 수집한 객체를
 *       본다(9.1). 엔진은 조건의 속성 이름({@code 유형코드.속성코드})으로 값을 찾으므로 그 이름으로 넣는다.
 *   <li>규칙 하나를 판정할 수 없으면(반영 중이 아님, 조건·변환·행 조건을 읽을 수 없음, 데이터 기준에 자원이 빠짐, 도착 값 변환 실패) 그 규칙만 건너뛰고 감사
 *       결과에 이유를 남긴다. 모든 규칙을 건너뛰면 검사는 FAILED다.
 * </ol>
 *
 * <p><b>complete</b>(한 트랜잭션): 검사 행을 잠가 RUNNING인지 보고, 판정한 규칙마다 {@code check_result} 한 행(건수, 같은 규칙
 * 개정본의 직전 결과, 정상이 아닌 행 표본 최대 {@value #SAMPLE_LIMIT}개)을 저장한 뒤 SUCCEEDED로 바꾼다.
 *
 * <p>아직 하지 않는 것: SYNC·MANUAL 판정의 issue 등록과 원천 사이 미연결의 확인 대기(191-3), 재검증의 문제별 검증 기록·비교(134·135).
 * MVP라 규칙마다 양 끝 유형의 활성 객체를 모두 읽는다(181과 같은 한계).
 */
@Slf4j
@Component
@Profile("batch")
public class CheckRunJob implements QueuedJobHandler<CheckRunJob.Outcome> {

    static final int SAMPLE_LIMIT = 20;
    static final String NOT_ACTIVE = "반영 중인 규칙이 아닙니다.";

    private final CheckRunRepository runs;
    private final CheckResultRepository results;
    private final LinkMappingRepository linkMappings;
    private final LinkMappingRuleLoader ruleLoader;
    private final CheckObjectReader objects;
    private final RuleEvaluationEngine engine;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    public CheckRunJob(
            CheckRunRepository runs,
            CheckResultRepository results,
            LinkMappingRepository linkMappings,
            LinkMappingRuleLoader ruleLoader,
            CheckObjectReader objects,
            RuleEvaluationEngine engine,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.runs = runs;
        this.results = results;
        this.linkMappings = linkMappings;
        this.ruleLoader = ruleLoader;
        this.objects = objects;
        this.engine = engine;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.clock = clock;
    }

    @Override
    public QueuedJobType type() {
        return QueuedJobType.CHECK_RUN;
    }

    @Override
    public String claimCondition() {
        return "trigger_kind <> 'CANDIDATE'";
    }

    @Override
    public Outcome run(long jobId) {
        return snapshot.execute(
                status -> {
                    // 읽기만 하므로 커밋하지 않는다. 규칙 하나를 건너뛸 때 변환기(@Transactional) 안에서 난 예외가 이 트랜잭션을
                    // rollback-only로 표시해도, 직접 되돌리기로 끝내면 UnexpectedRollbackException이 나지 않는다.
                    status.setRollbackOnly();
                    return evaluate(jobId);
                });
    }

    private Outcome evaluate(long jobId) {
        RunToExecute run =
                runs.findToExecute(jobId)
                        .orElseThrow(() -> new IllegalStateException("검사 실행이 없습니다: " + jobId));
        if ("CANDIDATE".equals(run.triggerKind())) {
            throw new QueuedJobFailure("후보 시험은 검사 실행기가 처리하지 않습니다.");
        }
        if (run.linkMappingIds().isEmpty()) {
            throw new QueuedJobFailure("검사할 규칙이 없습니다.");
        }
        Set<Long> active =
                linkMappings
                        .findByIdInAndStatus(run.linkMappingIds(), LinkMappingStatus.ACTIVE)
                        .stream()
                        .map(LinkMapping::getId)
                        .collect(Collectors.toSet());

        List<RuleEvaluated> evaluated = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        for (long linkMappingId : run.linkMappingIds()) {
            if (!active.contains(linkMappingId)) {
                skipped.add(new SkippedRule(linkMappingId, NOT_ACTIVE));
                continue;
            }
            try {
                evaluated.add(evaluateRule(ruleLoader.load(linkMappingId, run.dataBasis())));
            } catch (SkipRule | IllegalArgumentException | IllegalStateException e) {
                // 규칙 하나의 입력·데이터 문제다. DB 오류(DataAccessException)는 여기서 잡지 않아 검사 전체가 실패한다.
                String reason =
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn(
                        "검사 규칙 건너뜀 checkRunId={} linkMappingId={}: {}",
                        jobId,
                        linkMappingId,
                        reason);
                skipped.add(new SkippedRule(linkMappingId, reason));
            }
        }
        if (evaluated.isEmpty()) {
            throw new QueuedJobFailure(
                    "검사할 수 있는 규칙이 없습니다. 건너뛴 규칙: "
                            + skipped.stream()
                                    .map(s -> s.linkMappingId() + "(" + s.reason() + ")")
                                    .collect(Collectors.joining(", ")));
        }
        return new Outcome(run.triggerKind(), List.copyOf(evaluated), List.copyOf(skipped));
    }

    private RuleEvaluated evaluateRule(LinkMappingRule rule) {
        List<CheckRow> sourceRows =
                rows(rule.sourceType(), rule.sourceResourceIds(), rule.sourceFilter(), "출발");
        List<CheckRow> targetRows =
                rows(rule.targetType(), rule.targetResourceIds(), rule.targetFilter(), "도착");
        CheckEvaluation evaluation =
                engine.evaluate(new CheckInput(rule.input(), sourceRows, targetRows));
        return new RuleEvaluated(evaluation, samples(evaluation));
    }

    private List<CheckRow> rows(
            ObjectTypeRef type, List<Long> resourceIds, Map<String, Object> stored, String side) {
        ObjectRowFilter filter =
                ObjectRowFilter.parse(stored, type.code())
                        .orElseThrow(() -> new SkipRule(side + " 행 조건을 읽을 수 없습니다."));
        List<CheckRow> rows = new ArrayList<>();
        for (CheckObject object : objects.activeObjects(type.id(), resourceIds)) {
            if (!filter.matches(object.properties())) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            object.properties()
                    .forEach((code, value) -> values.put(type.code() + "." + code, value));
            rows.add(
                    new CheckRow(
                            object.sourceResourceId(), object.sourceKey(), object.id(), values));
        }
        return rows;
    }

    /**
     * 정상이 아닌 행 표본. 062가 보여 주는 rawValue·transformedValue·classification·reason에 객체·원천 위치와 대응한 도착 객체를
     * 더한다. 조건이 여럿이면 값을 조건 순서대로 " / "로 잇는다.
     */
    private static List<Map<String, Object>> samples(CheckEvaluation evaluation) {
        List<CheckCondition> conditions = evaluation.rule().conditions();
        List<Map<String, Object>> samples = new ArrayList<>();
        for (RowResult row : evaluation.rows()) {
            if (row.outcome() == CheckOutcome.MATCHED) {
                continue;
            }
            if (samples.size() == SAMPLE_LIMIT) {
                break;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("objectInstanceId", row.source().objectInstanceId());
            sample.put("sourceResourceId", row.source().sourceResourceId());
            sample.put("sourceKey", row.source().sourceKey());
            sample.put("classification", row.outcome().name());
            sample.put(
                    "rawValue",
                    joined(
                            conditions.stream()
                                    .map(c -> row.source().values().get(c.sourceProperty()))
                                    .toList()));
            sample.put(
                    "transformedValue",
                    joined(
                            conditions.stream()
                                    .map(c -> (Object) row.transformedValues().get(c.sequenceNo()))
                                    .toList()));
            sample.put("reason", row.reason());
            sample.put(
                    "targetObjectIds",
                    row.targets().stream()
                            .map(MatchedTarget::row)
                            .map(CheckRow::objectInstanceId)
                            .toList());
            samples.add(sample);
        }
        return samples;
    }

    /** 값이 모두 비었으면 null. 하나라도 있으면 빈 값은 빈 문자열로 두고 잇는다. */
    private static String joined(List<Object> values) {
        if (values.stream().allMatch(Objects::isNull)) {
            return null;
        }
        return values.stream()
                .map(value -> value == null ? "" : String.valueOf(value))
                .collect(Collectors.joining(" / "));
    }

    @Override
    public void complete(long jobId, Outcome outcome) {
        if (!runs.lockIfRunning(jobId)) {
            throw new QueuedJobFailure("검사 실행이 이미 끝났거나 정리되어 결과를 저장하지 않았습니다.");
        }
        for (RuleEvaluated rule : outcome.evaluated()) {
            long linkMappingId = rule.evaluation().rule().target().linkMappingId();
            Long previous =
                    results.findTopByLinkMappingIdOrderByIdDesc(linkMappingId)
                            .map(CheckResult::getId)
                            .orElse(null);
            results.save(CheckResult.of(jobId, previous, rule.evaluation(), rule.samples()));
        }
        runs.markSucceeded(jobId, Instant.now(clock));
    }

    @Override
    public Map<String, Object> auditResult(Outcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggerKind", outcome.triggerKind());
        result.put("checkedRuleCount", outcome.evaluated().size());
        result.put(
                "skippedRules",
                outcome.skipped().stream()
                        .map(
                                s ->
                                        Map.<String, Object>of(
                                                "linkMappingId", s.linkMappingId(),
                                                "reason", s.reason()))
                        .toList());
        return result;
    }

    /** run이 판정한 결과. complete가 저장한다. */
    public record Outcome(
            String triggerKind, List<RuleEvaluated> evaluated, List<SkippedRule> skipped) {}

    /** 규칙 하나의 판정과 저장할 표본. */
    public record RuleEvaluated(CheckEvaluation evaluation, List<Map<String, Object>> samples) {}

    /** 판정하지 않은 규칙과 이유. 이유에는 원천 값을 넣지 않는다. */
    public record SkippedRule(long linkMappingId, String reason) {}

    /** 규칙 하나를 판정할 수 없을 때. 그 규칙만 건너뛴다. */
    private static final class SkipRule extends RuntimeException {

        SkipRule(String message) {
            super(message);
        }
    }
}
