package com.axcore.platform.check.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import com.axcore.platform.batch.service.QueuedJobFailure;
import com.axcore.platform.batch.service.QueuedJobHandler;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.service.CandidateCheckPlanLoader.Plan;
import com.axcore.platform.check.service.CandidateCheckPlanLoader.RowMapping;
import com.axcore.platform.check.service.CandidateCheckPlanLoader.Side;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceEndpoint;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.domain.SourceRow;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceTableReader;
import com.axcore.platform.source.repository.SourceTableReader.Table;
import com.axcore.platform.source.repository.SourceTableReader.TooManyRowsException;
import com.axcore.platform.source.service.SourceCredentialException;
import com.axcore.platform.source.service.SourceCredentialService;
import com.axcore.platform.source.service.SourceRowConverter;
import com.axcore.platform.source.service.SourceRowConverter.ConversionException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("batch")
public class CandidateCheckJob implements QueuedJobHandler<CheckEvaluation> {

    private final CandidateCheckPlanLoader plans;
    private final CheckRunRepository runs;
    private final CheckResultRepository results;
    private final DataSourceRepository sources;
    private final SourceCredentialService credentials;
    private final SourceTableReader tables;
    private final SourceRowConverter converter;
    private final RuleEvaluationEngine engine;
    private final Clock clock;
    private final int maxRows;

    public CandidateCheckJob(
            CandidateCheckPlanLoader plans,
            CheckRunRepository runs,
            CheckResultRepository results,
            DataSourceRepository sources,
            SourceCredentialService credentials,
            SourceTableReader tables,
            SourceRowConverter converter,
            RuleEvaluationEngine engine,
            Clock clock,
            @Value("${axcore.source-load.max-rows}") int maxRows) {
        this.plans = plans;
        this.runs = runs;
        this.results = results;
        this.sources = sources;
        this.credentials = credentials;
        this.tables = tables;
        this.converter = converter;
        this.engine = engine;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    @Override
    public QueuedJobType type() {
        return QueuedJobType.CHECK_RUN;
    }

    @Override
    public String claimCondition() {
        return "trigger_kind = 'CANDIDATE'";
    }

    @Override
    public CheckEvaluation run(long jobId) {
        Plan plan = plans.load(jobId);
        // 접수 시점의 수집 ID는 작업 기준을 남기는 메타데이터다. 원천은 지금 조회한 행으로 판정한다.
        Map<Long, List<SourceRow>> rows = readRows(jobId, plan);
        try {
            return engine.evaluate(
                    new CheckInput(
                            plan.input(),
                            toCheckRows(plan.source(), rows),
                            toCheckRows(plan.target(), rows)),
                    plan.source().typeCode().equals(plan.target().typeCode()));
        } catch (IllegalArgumentException e) {
            throw new QueuedJobFailure("후보의 비교 조건을 원천 행에 적용할 수 없습니다.");
        }
    }

    private Map<Long, List<SourceRow>> readRows(long jobId, Plan plan) {
        Map<Long, Map<Long, List<RowMapping>>> bySource = new LinkedHashMap<>();
        Stream.concat(plan.source().mappings().stream(), plan.target().mappings().stream())
                .forEach(
                        mapping ->
                                bySource.computeIfAbsent(
                                                mapping.dataSourceId(),
                                                ignored -> new LinkedHashMap<>())
                                        .computeIfAbsent(
                                                mapping.resourceId(), ignored -> new ArrayList<>())
                                        .add(mapping));

        Map<Long, List<SourceRow>> rows = new LinkedHashMap<>();
        int remaining = maxRows;
        for (var entry : bySource.entrySet()) {
            long sourceId = entry.getKey();
            List<Table> toRead =
                    entry.getValue().values().stream().map(CandidateCheckJob::table).toList();
            Map<Long, List<SourceRow>> read = readSource(jobId, sourceId, toRead, remaining);
            remaining -= read.values().stream().mapToInt(List::size).sum();
            rows.putAll(read);
        }
        return rows;
    }

    private Map<Long, List<SourceRow>> readSource(
            long jobId, long sourceId, List<Table> toRead, int remaining) {
        var source =
                sources.findById(sourceId)
                        .orElseThrow(() -> new QueuedJobFailure("후보 시험의 원천 연결 설정이 없습니다."));
        if (source.getSourceKind() != SourceKind.POSTGRES) {
            throw new QueuedJobFailure("후보 시험은 PostgreSQL 원천만 읽을 수 있습니다.");
        }
        try {
            var endpoint = SourceEndpoint.from(source.getConnectionConfig());
            var account =
                    credentials
                            .findAccount(sourceId, CredentialPurpose.SYNC)
                            .orElseThrow(
                                    () -> new QueuedJobFailure("SYNC 계정이 등록되지 않아 원천을 읽을 수 없습니다."));
            return tables.read(endpoint, account, toRead, remaining).rows();
        } catch (TooManyRowsException e) {
            throw new QueuedJobFailure("후보 시험의 원천 행 수가 조회 한도(%d건)를 넘었습니다.".formatted(maxRows));
        } catch (SQLException e) {
            log.warn(
                    "후보 시험 원천 조회 실패 checkRunId={} dataSourceId={} sqlState={}",
                    jobId,
                    sourceId,
                    e.getSQLState());
            throw new QueuedJobFailure("후보 시험의 원천 테이블을 읽을 수 없습니다.");
        } catch (SourceCredentialException | IllegalArgumentException e) {
            throw new QueuedJobFailure("후보 시험의 원천 연결 설정을 확인해 주세요.");
        }
    }

    private static Table table(List<RowMapping> mappings) {
        RowMapping first = mappings.getFirst();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        for (RowMapping mapping : mappings) {
            keys.addAll(mapping.keyColumns());
            mapping.properties().values().forEach(property -> columns.add(property.column()));
        }
        columns.addAll(keys);
        return new Table(first.resourceId(), first.resourceKey(), List.copyOf(columns), keys);
    }

    private List<CheckRow> toCheckRows(Side side, Map<Long, List<SourceRow>> rows) {
        List<CheckRow> result = new ArrayList<>();
        for (RowMapping mapping : side.mappings()) {
            for (SourceRow row : rows.get(mapping.resourceId())) {
                Map<String, Object> properties = new LinkedHashMap<>();
                mapping.properties()
                        .forEach(
                                (code, property) -> {
                                    try {
                                        properties.put(
                                                code,
                                                converter.propertyValue(
                                                        row.values().get(property.column()),
                                                        property.steps(),
                                                        property.dataType()));
                                    } catch (ConversionException ignored) {
                                        // 수집과 같이 바꿀 수 없는 값은 속성에서 뺀다.
                                    }
                                });
                if (!side.filter().matches(properties)) {
                    continue;
                }
                Map<String, Object> key = new LinkedHashMap<>();
                mapping.keyColumns().forEach(column -> key.put(column, row.keyTexts().get(column)));
                Map<String, Object> values = new LinkedHashMap<>();
                // 키가 없는 행도 검사 건수에는 남기되, 객체로 연결될 수 없는 행이므로 매칭하지 않는다.
                if (!key.containsValue(null)) {
                    properties.forEach(
                            (code, value) -> values.put(side.typeCode() + "." + code, value));
                }
                result.add(new CheckRow(mapping.resourceId(), key, null, values));
            }
        }
        return result;
    }

    @Override
    public void complete(long jobId, CheckEvaluation evaluation) {
        if (!runs.lockIfRunning(jobId)) {
            throw new QueuedJobFailure("검사 실행이 이미 끝났거나 정리되어 결과를 저장하지 않았습니다.");
        }
        results.save(CheckResult.of(jobId, null, evaluation, samples(evaluation)));
        runs.markSucceeded(jobId, Instant.now(clock));
    }

    @Override
    public Map<String, Object> auditResult(CheckEvaluation evaluation) {
        return Map.of(
                "proposalItemId", evaluation.rule().target().proposalItemId(),
                "revision", evaluation.rule().target().proposalItemRevision(),
                "totalCount", evaluation.totalCount(),
                "matchedCount", evaluation.matchedCount());
    }

    private static List<Map<String, Object>> samples(CheckEvaluation evaluation) {
        var conditions = evaluation.rule().conditions();
        return evaluation.rows().stream()
                .filter(row -> row.outcome() != CheckOutcome.MATCHED)
                .limit(CheckRunJob.SAMPLE_LIMIT)
                .<Map<String, Object>>map(
                        row -> {
                            Map<String, Object> sample = new LinkedHashMap<>();
                            sample.put("sourceResourceId", row.source().sourceResourceId());
                            sample.put("sourceKey", row.source().sourceKey());
                            sample.put("classification", row.outcome().name());
                            sample.put("rawValue", CheckRowText.rawValue(row, conditions));
                            sample.put(
                                    "transformedValue",
                                    CheckRowText.transformedValue(row, conditions));
                            sample.put("reason", row.reason());
                            if (!row.targets().isEmpty()) {
                                sample.put("targetKey", row.targets().getFirst().row().sourceKey());
                            }
                            return sample;
                        })
                .toList();
    }
}
