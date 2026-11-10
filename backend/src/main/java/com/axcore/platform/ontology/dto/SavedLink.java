package com.axcore.platform.ontology.dto;

import com.axcore.platform.audit.domain.ChangeEntry;
import java.util.Objects;

/**
 * 확정 연결을 저장한 결과(Jira 329).
 *
 * @param linkId 저장했거나 이미 있던 연결
 * @param previousOrigin 되살린 행의 이전 출처(RULE·MANUAL·DOCUMENT). 새로 만들었거나 이미 있었으면 null
 * @param change 감사 기록의 changes에 넣을 전후 내용. 이미 있던 연결이면 null(바뀐 것이 없다)
 */
public record SavedLink(long linkId, Outcome outcome, String previousOrigin, ChangeEntry change) {

    public SavedLink {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.ALREADY_LINKED) != (change == null)) {
            throw new IllegalArgumentException("이미 있던 연결만 바뀐 내용이 없습니다.");
        }
    }

    public enum Outcome {
        /** 쌍에 행이 없어 새로 넣었다. */
        CREATED,
        /** 쌍의 비활성 행(규칙 연결 포함)을 확정 연결로 되살렸다. */
        REACTIVATED,
        /** 같은 쌍의 활성 문서 연결이 이미 있어 그대로 두었다(같은 메모가 같은 객체를 다시 언급). */
        ALREADY_LINKED
    }
}
