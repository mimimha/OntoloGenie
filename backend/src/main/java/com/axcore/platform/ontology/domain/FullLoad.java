package com.axcore.platform.ontology.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 전체 읽기 한 번(Jira 197이 만든다).
 *
 * @param snapshot 읽은 트랜잭션의 pg_current_snapshot() 값(xmin:xmax:진행 중 목록)
 * @param syncRunId 이 전체 읽기의 수집 실행 ID. 그대로 세대 번호(loaded_gen)로 쓴다. 나중 실행일수록 크다
 */
public record FullLoad(String snapshot, long syncRunId) {

    // 소유 수량자(++, *+)로 되짚기와 묶음 반복의 재귀를 없앤다. 진행 중 트랜잭션 목록이 아주 길어도 스택이 넘치지 않는다.
    private static final Pattern SNAPSHOT_FORMAT =
            Pattern.compile("\\d++:\\d++:(?:\\d++(?:,\\d++)*+)?");

    public FullLoad {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!SNAPSHOT_FORMAT.matcher(snapshot).matches()) {
            throw new IllegalArgumentException("스냅샷 형식이 아닙니다: " + snapshot);
        }
        if (syncRunId < 1) {
            throw new IllegalArgumentException("수집 실행 ID는 1 이상입니다.");
        }
    }

    public long generation() {
        return syncRunId;
    }
}
