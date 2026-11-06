package com.axcore.platform.analysis.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 분석 실행 생성의 작업 테이블 저장. analysis_run·analysis_run_target·sync_run은 Entity를 두지 않는 작업 테이블이라
 * JdbcClient로 다룬다. 같은 대상의 활성 실행은 조건부 insert(ON CONFLICT DO NOTHING)로 하나만 만든다. 만든 실행의 응답 변환은
 * AnalysisQueryRepository가 맡는다.
 */
@Repository
@RequiredArgsConstructor
public class AnalysisCreateRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public boolean syncExists(long id) {
        return jdbc.sql("select exists(select 1 from sync_run where id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public Optional<Long> insertIfAbsent(
            String kind,
            String key,
            Map<String, Object> range,
            Long syncId,
            long accountId,
            Instant now) {
        return jdbc.sql(
                        """
                insert into analysis_run(run_kind, work_key, analysis_range, based_on_sync_run_id, requested_by, requested_at)
                values (:kind, :key, cast(:range as jsonb), :sync, :account, :now)
                on conflict (work_key) where status in ('QUEUED','RUNNING') do nothing returning id
                """)
                .param("kind", kind)
                .param("key", key)
                .param("range", json.writeValueAsString(range))
                .param("sync", syncId, Types.BIGINT)
                .param("account", accountId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    public Optional<Long> findActiveForUpdate(String key) {
        return jdbc.sql(
                        "select id from analysis_run where work_key=:key and status in ('QUEUED','RUNNING') for update")
                .param("key", key)
                .query(Long.class)
                .optional();
    }

    public void insertTargets(long runId, List<Long> ids) {
        for (long id : ids) {
            jdbc.sql(
                            "insert into analysis_run_target(analysis_run_id, source_resource_id) values (:run, :resource)")
                    .param("run", runId)
                    .param("resource", id)
                    .update();
        }
    }
}
