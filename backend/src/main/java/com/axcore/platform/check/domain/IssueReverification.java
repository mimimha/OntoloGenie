package com.axcore.platform.check.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 재검증(REVERIFY, 범위 ISSUES)이 문제 하나를 다시 판정한 결과(Jira 246). 검사 실행기가 만들고 {@code
 * IssueReverificationRecorder}(135)에 넘긴다.
 *
 * <p>문제는 규칙 코드로 묶이므로, 문제가 처음 나온 개정본이 아니라 같은 코드의 반영 중인 개정본으로 판정한다. 판정은 문제의 객체({@code
 * issue.object_instance_id})가 그 개정본의 출발 행으로 나온 결과다.
 *
 * @param linkMappingId 판정에 쓴 규칙 개정본. 같은 코드의 반영 중인 개정본이 없으면 null
 * @param detail PASSED·FAILED일 때 070 {@code detail}에 넣을 값: {@code rawValue}, {@code
 *     transformedValue}, {@code targetFound}, {@code classification}. NOT_EVALUATED면 비어 있다
 * @param reason NOT_EVALUATED인 이유. 그 밖에는 null
 */
public record IssueReverification(
        long issueId,
        Long linkMappingId,
        Verdict verdict,
        Map<String, Object> detail,
        String reason) {

    public IssueReverification {
        Objects.requireNonNull(verdict, "verdict");
        if ((verdict == Verdict.NOT_EVALUATED) == (reason == null)) {
            throw new IllegalArgumentException("판정하지 못한 결과에만 이유가 있습니다.");
        }
        // 원천 값이 null일 수 있어 Map.copyOf를 쓰지 않는다.
        detail =
                detail == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public enum Verdict {
        /** 출발 행이 정상(MATCHED)이다. 문제가 더는 나오지 않는다. */
        PASSED,
        /** 출발 행이 여전히 정상이 아니다(미연결·중복·조건 위반). */
        FAILED,
        /**
         * 판정하지 못했다: 문제에 객체가 없음, 같은 코드의 규칙을 판정하지 못함(반영 중 아님·건너뜀), 객체가 판정 대상에 없음(원천 삭제·행 조건 밖). 기록할지는
         * 135가 정한다.
         */
        NOT_EVALUATED
    }
}
