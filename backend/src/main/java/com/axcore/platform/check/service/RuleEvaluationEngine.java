package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckEvaluation;
import com.axcore.platform.check.domain.CheckEvaluation.MatchedTarget;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import com.axcore.platform.check.domain.CheckInput;
import com.axcore.platform.check.domain.CheckOutcome;
import com.axcore.platform.check.domain.CheckRow;
import com.axcore.platform.ontology.domain.LinkMatchGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 규칙 조건에 따라 출발 행마다 최종 판정 하나를 만든다.
 *
 * <p>비교는 변환 후 문자열의 EQ 기준이며, 원천 조회와 결과 저장은 호출 측에서 처리한다.
 */
@Component
public class RuleEvaluationEngine {

    private final ValueTransformer valueTransformer;

    public RuleEvaluationEngine(ValueTransformer valueTransformer) {
        this.valueTransformer = Objects.requireNonNull(valueTransformer, "valueTransformer");
    }

    public CheckEvaluation evaluate(CheckInput input) {
        return evaluate(input, false);
    }

    CheckEvaluation evaluate(CheckInput input, boolean excludesSelf) {
        Objects.requireNonNull(input, "input");

        if (input.sourceRows().isEmpty()) {
            return new CheckEvaluation(input.rule(), List.of());
        }

        var conditions = input.rule().conditions();
        var cardinality = input.rule().cardinality();
        var duplicateSourceKeys = duplicateKeys(input.sourceRows());
        var duplicateTargetKeys = duplicateKeys(input.targetRows());

        // 도착 행을 비교값별로 묶어 출발 행마다 전체 도착 목록을 순회하지 않는다.
        Map<List<String>, TargetGroup> targetIndex = new HashMap<>();

        for (CheckRow target : input.targetRows()) {
            var prepared = prepare(target, conditions, false);

            // 연동 가정: 도착 변환 실패는 숨기지 않고 실행 오류로 전달한다.
            if (prepared.reason() != null) {
                throw new IllegalArgumentException("도착 행을 비교값으로 변환할 수 없습니다. " + prepared.reason());
            }

            if (prepared.key() != null) {
                targetIndex
                        .computeIfAbsent(prepared.key(), key -> new TargetGroup())
                        .add(
                                identity(target, excludesSelf),
                                new MatchedTarget(target, prepared.values()),
                                duplicateTargetKeys.contains(rowKey(target)));
            }
        }
        // RowResult가 출발 행마다 같은 도착 묶음을 복사하지 않도록 한 번만 불변 리스트로 만든다.
        targetIndex.values().forEach(TargetGroup::freeze);
        TargetGroup emptyTargets = new TargetGroup();

        var sources =
                input.sourceRows().stream().map(row -> prepare(row, conditions, true)).toList();

        // 출발이 ONE인 관계에서는 같은 도착에 연결되는 서로 다른 출발도 확인한다.
        Map<List<String>, Set<Object>> sourceOwners = new HashMap<>();
        for (PreparedRow source : sources) {
            if (source.key() != null) {
                sourceOwners
                        .computeIfAbsent(source.key(), key -> new HashSet<>())
                        .add(identity(source.row(), excludesSelf));
            }
        }

        List<RowResult> results = new ArrayList<>();

        for (PreparedRow source : sources) {
            Object sourceId = identity(source.row(), excludesSelf);
            TargetGroup targets = targetIndex.getOrDefault(source.key(), emptyTargets);
            var matches =
                    new LinkMatchGroup<>(
                            sourceOwners.getOrDefault(source.key(), Set.of()),
                            targets.positions.keySet(),
                            excludesSelf);
            List<Integer> selfIndexes =
                    excludesSelf ? targets.positions.getOrDefault(sourceId, List.of()) : List.of();

            CheckOutcome outcome;
            String reason;

            // 조건 위반 > 중복 > 미연결 > 정상 순서로 최종 분류한다.
            if (source.reason() != null) {
                outcome = CheckOutcome.VIOLATION;
                reason = source.reason();
            } else if (duplicateSourceKeys.contains(rowKey(source.row()))) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "출발 원천 키가 중복되었습니다.";
            } else if (targets.duplicateIds.size()
                    > (excludesSelf && targets.duplicateIds.contains(sourceId) ? 1 : 0)) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "대응한 도착 원천 키가 중복되었습니다.";
            } else if (cardinality.destinationIsOne() && matches.targetCount(sourceId) > 1) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "도착이 ONE인 관계에 복수 대상이 대응합니다.";
            } else if (cardinality.sourceIsOne() && matches.hasCompetingSources(sourceId)) {
                outcome = CheckOutcome.DUPLICATE;
                reason = "출발이 ONE인 관계에서 같은 도착에 복수 출발이 대응합니다.";
            } else if (matches.targetCount(sourceId) == 0) {
                outcome = CheckOutcome.UNMATCHED;
                reason = "모든 비교 조건을 충족하는 도착 대상이 없습니다.";
            } else {
                outcome = CheckOutcome.MATCHED;
                reason = null;
            }

            results.add(
                    new RowResult(
                            source.row(),
                            outcome,
                            source.values(),
                            targets.rows,
                            reason,
                            selfIndexes));
        }

        return new CheckEvaluation(input.rule(), results);
    }

    private PreparedRow prepare(CheckRow row, List<CheckCondition> conditions, boolean source) {
        Map<Integer, String> values = new LinkedHashMap<>();

        for (CheckCondition condition : conditions) {
            String property = source ? condition.sourceProperty() : condition.targetProperty();
            var transforms = source ? condition.sourceTransforms() : condition.targetTransforms();
            Object raw = row.values().get(property);

            // 결측값에서 접두어 등의 변환으로 가짜 비교값을 만들지 않는다.
            if (raw == null || raw instanceof String text && text.isBlank()) {
                values.put(condition.sequenceNo(), null);
                continue;
            }

            if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) {
                values.put(condition.sequenceNo(), null);
                return new PreparedRow(
                        row, values, null, "조건 " + condition.sequenceNo() + "의 비교값은 단일 값이어야 합니다.");
            }

            try {
                String transformed = valueTransformer.transform(String.valueOf(raw), transforms);
                values.put(condition.sequenceNo(), transformed);
            } catch (ValueTransformer.TransformException e) {
                values.put(condition.sequenceNo(), null);

                // 원본 데이터는 사유 문자열에 넣지 않는다.
                return new PreparedRow(
                        row,
                        values,
                        null,
                        "조건 " + condition.sequenceNo() + "의 " + e.operation() + " 변환 실패");
            }
        }

        // 결측값끼리는 서로 일치하는 대상으로 연결하지 않는다.
        boolean missing =
                values.values().stream().anyMatch(value -> value == null || value.isBlank());
        List<String> key = missing ? null : List.copyOf(values.values());

        return new PreparedRow(row, values, key, null);
    }

    private Set<RowKey> duplicateKeys(List<CheckRow> rows) {
        Map<RowKey, Integer> counts = new HashMap<>();
        Set<RowKey> duplicates = new HashSet<>();

        for (CheckRow row : rows) {
            RowKey key = rowKey(row);
            int count = counts.merge(key, 1, Integer::sum);
            if (count > 1) {
                duplicates.add(key);
            }
        }

        return duplicates;
    }

    private RowKey rowKey(CheckRow row) {
        // 서로 다른 원천 자원의 같은 키 값은 별개의 행이다.
        return new RowKey(row.sourceResourceId(), row.sourceKey());
    }

    private Object identity(CheckRow row, boolean excludesSelf) {
        // 반영된 객체는 객체 ID로, 아직 객체가 없는 후보 시험은 원천 자원과 원천 키로 식별한다.
        return excludesSelf && row.objectInstanceId() != null
                ? row.objectInstanceId()
                : rowKey(row);
    }

    private static final class TargetGroup {
        private List<MatchedTarget> rows = new ArrayList<>();
        private final Map<Object, List<Integer>> positions = new HashMap<>();
        private final Set<Object> duplicateIds = new HashSet<>();

        private void add(Object id, MatchedTarget row, boolean duplicateKey) {
            positions.computeIfAbsent(id, key -> new ArrayList<>()).add(rows.size());
            rows.add(row);
            if (duplicateKey) {
                duplicateIds.add(id);
            }
        }

        private void freeze() {
            rows = List.copyOf(rows);
        }
    }

    private record RowKey(long sourceResourceId, Map<String, Object> sourceKey) {}

    private record PreparedRow(
            CheckRow row, Map<Integer, String> values, List<String> key, String reason) {}
}
