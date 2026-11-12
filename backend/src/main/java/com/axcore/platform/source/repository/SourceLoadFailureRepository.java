package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.LoadFailure;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집 실패 기록({@code source_load_failure}, V12, 결정 문서 9.2). 같은 실패는 열린 기록 하나에 횟수만 올리고, 다시 성공하면 닫는다.
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): 조건부 upsert다. "열린 기록 하나에 횟수만 올리기"는 부분 유일
 * 인덱스 {@code uq_source_load_failure_open}을 쓰는 {@code ON CONFLICT ... WHERE status = 'OPEN' DO
 * UPDATE} 한 문장이어야 동시에 기록해도 열린 기록이 둘로 갈리지 않는다. 엔티티로 조회한 뒤 저장하면 두 트랜잭션이 둘 다 "없음"을 본다. 아직 Entity가 없는
 * 표라 닫기(조건부 일괄 갱신)도 같은 클래스에서 기존 방식(JdbcClient)으로 한다.
 */
@Repository
@RequiredArgsConstructor
public class SourceLoadFailureRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    /**
     * 실패를 남긴다. 같은 매핑·키·컬럼·이유의 열린 기록이 있으면 횟수와 마지막으로 본 시각·실행만 올린다.
     *
     * @param sourceKey 원천 키. 키를 만들지 못했으면 null(같은 매핑·컬럼·이유의 키 없는 실패는 하나로 모인다)
     */
    public void record(
            long resourceId,
            long mappingId,
            Map<String, Object> sourceKey,
            LoadFailure failure,
            long syncRunId) {
        jdbc.sql(
                        """
                        insert into source_load_failure as f
                            (source_resource_id, object_source_mapping_id, source_key, column_name,
                             property_definition_id, reason, last_sync_run_id)
                        values (:resource, :mapping, cast(:key as jsonb), :column, :property, :reason, :run)
                        on conflict (object_source_mapping_id, source_key, column_name, reason)
                            where status = 'OPEN'
                        do update set occurrences = f.occurrences + 1,
                                      last_seen_at = now(),
                                      last_sync_run_id = excluded.last_sync_run_id,
                                      property_definition_id = excluded.property_definition_id
                        """)
                .param("resource", resourceId)
                .param("mapping", mappingId)
                .param("key", sourceKey == null ? null : json.writeValueAsString(sourceKey))
                .param("column", failure.columnName())
                .param("property", failure.propertyDefinitionId())
                .param("reason", failure.reason().name())
                .param("run", syncRunId)
                .update();
    }

    /**
     * 그 매핑·원천 키를 다시 썼을 때, 이번에 다시 실패하지 않은 열린 기록을 닫는다.
     *
     * @param stillFailing 이번에도 실패한 것
     * @return 닫은 수
     */
    public int closeResolved(
            long mappingId, Map<String, Object> sourceKey, Collection<LoadFailure> stillFailing) {
        return jdbc.sql(
                        """
                        update source_load_failure
                        set status = 'CLOSED', closed_at = now()
                        where object_source_mapping_id = :mapping
                          and source_key = cast(:key as jsonb)
                          and status = 'OPEN'
                          and not (column_name || ':' || reason = any(:still))
                        """)
                .param("mapping", mappingId)
                .param("key", json.writeValueAsString(sourceKey))
                .param(
                        "still",
                        stillFailing.stream()
                                .map(f -> f.columnName() + ":" + f.reason().name())
                                .toArray(String[]::new))
                .update();
    }

    /**
     * 전체 읽기(362)가 끝날 때, 그 매핑의 열린 기록 중 이번 읽기에서 다시 나오지 않은 것을 닫는다(V12 주석 "다음 전체 읽기에서 다시 나오지 않음"). 이번에
     * 다시 실패한 것은 {@link #record}가 {@code last_sync_run_id}를 이번 실행으로 바꿔 두므로 남는다. 읽기를 시작한 뒤에 본 실패(CDC가
     * 그 사이 남긴 것)는 이 스냅샷이 보지 못한 변경일 수 있어 닫지 않는다. 기준은 실행 시작 시각이다(읽기는 그 뒤에 한다).
     *
     * @return 닫은 수
     */
    public int closeNotSeenInFullLoad(long mappingId, long syncRunId) {
        return jdbc.sql(
                        """
                        update source_load_failure f
                        set status = 'CLOSED', closed_at = now()
                        from sync_run s
                        where s.id = :run
                          and f.object_source_mapping_id = :mapping
                          and f.status = 'OPEN'
                          and f.last_sync_run_id <> :run
                          and f.last_seen_at < s.started_at
                        """)
                .param("mapping", mappingId)
                .param("run", syncRunId)
                .update();
    }

    /** 원천에서 지워진 행의 열린 기록을 모두 닫는다(그 테이블의 모든 매핑). 닫은 수. */
    public int closeDeleted(long resourceId, Map<String, Object> sourceKey) {
        return jdbc.sql(
                        """
                        update source_load_failure
                        set status = 'CLOSED', closed_at = now()
                        where source_resource_id = :resource
                          and source_key = cast(:key as jsonb)
                          and status = 'OPEN'
                        """)
                .param("resource", resourceId)
                .param("key", json.writeValueAsString(sourceKey))
                .update();
    }
}
