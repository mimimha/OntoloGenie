package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckCondition;
import com.axcore.platform.check.domain.CheckEvaluation.RowResult;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 판정한 출발 행의 원본값·변환값을 표본(check_result)과 issue에 같은 모양으로 남긴다. 조건이 여럿이면 조건 순서대로 " / "로 잇는다. */
final class CheckRowText {

    private CheckRowText() {}

    static String rawValue(RowResult row, List<CheckCondition> conditions) {
        return joined(
                conditions.stream()
                        .map(c -> row.source().values().get(c.sourceProperty()))
                        .toList());
    }

    static String transformedValue(RowResult row, List<CheckCondition> conditions) {
        return joined(
                conditions.stream()
                        .map(c -> (Object) row.transformedValues().get(c.sequenceNo()))
                        .toList());
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
}
