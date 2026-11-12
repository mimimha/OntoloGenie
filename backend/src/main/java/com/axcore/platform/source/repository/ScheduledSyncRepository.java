package com.axcore.platform.source.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * FULL_COMPARE 테이블의 주기 수집(FN-BAT-01·02, S15P31C106-318)이 대상을 찾고 실행을 넣는다. JdbcClient: Entity가 없는 작업
 * 표(sync_run, sync_run_resource)에 조건부 insert를 하고, 대상 판단에
 * 원천(data_source)·매핑(object_source_mapping)·작업 표를 한 SQL로 join·검사한다.
 *
 * <p>주기의 기준은 그 원천의 마지막 SCHEDULED 실행의 접수 시각이다. 없으면 바로 넣는다.
 */
@Repository
@RequiredArgsConstructor
public class ScheduledSyncRepository {

    private static final String DUE =
            """
            d.sync_enabled and d.sync_interval_minutes is not null
            and coalesce((select max(s.requested_at) from sync_run s
                          where s.data_source_id = d.id and s.trigger_kind = 'SCHEDULED'),
                         '-infinity'::timestamptz)
                <= cast(:now as timestamptz) - make_interval(mins => d.sync_interval_minutes)
            and exists (select 1 from source_resource r
                        where r.data_source_id = d.id and r.sync_strategy = 'FULL_COMPARE'
                          and exists (select 1 from object_source_mapping m
                                      where m.source_resource_id = r.id and m.is_active)
                          and not exists (select 1 from sync_run_resource a
                                          where a.source_resource_id = r.id
                                            and a.status in ('QUEUED', 'RUNNING')))
            """;

    private final JdbcClient jdbc;

    /**
     * 주기가 된 원천. 자동 수집이 켜져 있고, 마지막 SCHEDULED 접수에서 주기가 지났고, 대기·실행 중이 아닌 켜진 매핑의 FULL_COMPARE 테이블이 있는
     * 원천이다. ID 순서다.
     */
    public List<Long> findDueSources(Instant now, int limit) {
        return jdbc.sql(
                        "select d.id from data_source d where "
                                + DUE
                                + " order by d.id limit :limit")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /**
     * 원천 행을 잠그고, 그 뒤 아직 주기가 된 상태인지 다시 본다. 여러 배치 인스턴스가 같은 원천을 함께 골라도 먼저 넣은 쪽이 커밋하면 뒤쪽은 false를 받는다. 키
     * 잠금(FOR KEY SHARE)과 겹치지 않는 잠금이라 다른 곳의 sync_run 넣기는 막지 않는다.
     */
    public boolean lockIfDue(long dataSourceId, Instant now) {
        jdbc.sql("select 1 from data_source where id = :id for no key update")
                .param("id", dataSourceId)
                .query(Integer.class)
                .optional();
        return jdbc.sql(
                        "select exists (select 1 from data_source d where d.id = :id and "
                                + DUE
                                + ")")
                .param("id", dataSourceId)
                .param("now", Timestamp.from(now))
                .query(Boolean.class)
                .single();
    }

    /** 켜진 매핑이 있는 이 원천의 FULL_COMPARE 테이블. CDC 테이블은 넣지 않는다. ID 순서다. */
    public List<Long> findTargetResources(long dataSourceId) {
        return jdbc.sql(
                        """
                        select r.id from source_resource r
                        where r.data_source_id = :source and r.sync_strategy = 'FULL_COMPARE'
                          and exists (select 1 from object_source_mapping m
                                      where m.source_resource_id = r.id and m.is_active)
                        order by r.id
                        """)
                .param("source", dataSourceId)
                .query(Long.class)
                .list();
    }

    /** trigger_kind SCHEDULED의 빈 실행을 QUEUED로 넣는다. 요청자는 없다. */
    public long insertScheduledRun(long dataSourceId, Instant requestedAt) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, requested_at)
                        values (:source, 'SCHEDULED', :requestedAt)
                        returning id
                        """)
                .param("source", dataSourceId)
                .param("requestedAt", Timestamp.from(requestedAt))
                .query(Long.class)
                .single();
    }

    /**
     * 테이블들을 FULL·QUEUED로 실행에 넣고 넣은 테이블 ID를 돌려준다. 이미 대기·실행 중인 테이블(uq_sync_run_resource_active)은 그
     * 실행에 합류한 것으로 보고 넣지 않는다(217). 그 사이 FULL_COMPARE가 아니게 되었거나 매핑이 꺼진 테이블도 넣지 않는다.
     */
    public List<Long> insertFullResources(
            long syncRunId, long dataSourceId, List<Long> resourceIds) {
        return jdbc.sql(
                        """
                        insert into sync_run_resource
                            (sync_run_id, data_source_id, source_resource_id, mode)
                        select :run, r.data_source_id, r.id, 'FULL'
                        from source_resource r
                        where r.id = any(:ids) and r.data_source_id = :source
                          and r.sync_strategy = 'FULL_COMPARE'
                          and exists (select 1 from object_source_mapping m
                                      where m.source_resource_id = r.id and m.is_active)
                        order by r.id
                        on conflict (source_resource_id) where status in ('QUEUED', 'RUNNING')
                        do nothing
                        returning source_resource_id
                        """)
                .param("run", syncRunId)
                .param("source", dataSourceId)
                .param("ids", resourceIds.toArray(Long[]::new))
                .query(Long.class)
                .list();
    }
}
