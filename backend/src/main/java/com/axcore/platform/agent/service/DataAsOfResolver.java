package com.axcore.platform.agent.service;

import com.axcore.platform.agent.repository.DataAsOfRepository;
import com.axcore.platform.agent.repository.DataAsOfRepository.SourceTable;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent 조회 결과의 기준 시점(정책 문서 4.1·4.2, 145). 결과가 나온 원천 표마다 플랫폼이 그 표를 마지막으로 확인한 시각을 구하고, 그중 가장 이른 시각을
 * 쓴다. 표 하나라도 확인 시각을 알 수 없으면 기준 시점을 알 수 없다(null). 규칙은 2026-11-11 이주헌 님과 정했다.
 *
 * <ul>
 *   <li>공통: 결과 객체를 가져오는 매핑이 꺼져 있으면(수집 중지) 그 표를 확인한 시각이 그 객체 값에 맞지 않아 알 수 없다.
 *   <li>CDC 표: 그 원천의 마지막 메시지 시각(변경·heartbeat). 수집이 비정상이거나 복구가 필요하면, 그 표를 아직 전체 읽기하지 않았으면(첫 적재 전·다시
 *       읽기 대기·진행 중), 그 원천의 CDC 소비자가 시작하지 않았으면 알 수 없다.
 *   <li>전체 비교 수집 표: 마지막 성공한 전체 읽기의 시작 시각. 전체 읽기는 시작할 때의 스냅샷 기준이라 완료 시각을 쓰면 실제보다 최신처럼 보인다.
 *       건너뛴(SKIPPED) 수집은 다른 실행이 읽은 것이라 그 시작 시각은 쓰지 않는다.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DataAsOfResolver {
    static final String CDC = "CDC";

    private final DataAsOfRepository tables;

    /** 객체들이 나온 원천 표 기준의 기준 시점. 원천 표가 하나도 없으면(플랫폼이 만든 객체뿐이면) 알 수 없다. */
    public Instant forObjects(Collection<Long> objectIds) {
        if (objectIds.isEmpty()) {
            return null;
        }
        return earliest(tables.tablesOfObjects(new LinkedHashSet<>(objectIds)));
    }

    /** 관계 한 건의 양 끝 객체가 나온 원천 표 기준의 기준 시점. */
    public Instant forLink(long linkId) {
        return earliest(tables.tablesOfLink(linkId));
    }

    static Instant earliest(List<SourceTable> sourceTables) {
        if (sourceTables.isEmpty()) {
            return null;
        }
        Instant earliest = null;
        for (SourceTable table : sourceTables) {
            Instant confirmedAt = confirmedAt(table);
            if (confirmedAt == null) {
                return null;
            }
            if (earliest == null || confirmedAt.isBefore(earliest)) {
                earliest = confirmedAt;
            }
        }
        return earliest;
    }

    /** 플랫폼이 그 표를 마지막으로 확인한 시각. 알 수 없으면 null이다. */
    static Instant confirmedAt(SourceTable table) {
        if (!table.mappingActive()) {
            return null;
        }
        if (CDC.equals(table.syncStrategy())) {
            boolean trusted =
                    table.hasCdcOffset()
                            && table.healthySince() != null
                            && table.loadedLsn() != null;
            return trusted ? table.lastMessageAt() : null;
        }
        return table.lastFullStartedAt();
    }
}
