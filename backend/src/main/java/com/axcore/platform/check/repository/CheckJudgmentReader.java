package com.axcore.platform.check.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 검사 판정(191-3)이 issue·확인 대기를 정하기 전에 보는 수집 상태. 원천 테이블의 적재·전체 읽기·복구·정상 여부, 열린 수집 실패, 지운 객체를 읽는다.
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): 수집 도메인 표({@code source_resource}·{@code
 * sync_run_resource}·{@code cdc_offset}·{@code source_load_failure})와 Entity가 없는 {@code
 * object_instance}를 원천 테이블 단위로 모아 읽는 읽기 전용 집계라 한 번의 SQL로 읽는다. 쓰지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class CheckJudgmentReader {

    private final JdbcClient jdbc;

    /**
     * 원천 테이블마다 판정에 필요한 수집 상태. CDC 원천의 복구 필요·정상 시각은 원천(data_source)의 {@code cdc_offset}에서 온다. 원천에 소비
     * 상태 행이 없거나 하나라도 정상이 아니면 정상 시각은 null이다.
     */
    public List<ResourceState> resourceStates(Collection<Long> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        select r.id, r.data_source_id, r.sync_strategy,
                               r.loaded_lsn is not null as loaded,
                               exists (select 1 from sync_run_resource s
                                       where s.source_resource_id = r.id and s.mode = 'FULL'
                                         and s.status in ('QUEUED', 'RUNNING')) as full_pending,
                               (select max(s.finished_at) from sync_run_resource s
                                where s.source_resource_id = r.id and s.status = 'SUCCEEDED')
                                   as last_read_at,
                               o.recovery_reason, o.healthy_since
                        from source_resource r
                        left join lateral (
                            select max(c.recovery_reason) as recovery_reason,
                                   case when bool_or(c.healthy_since is null) then null
                                        else max(c.healthy_since) end as healthy_since
                            from cdc_offset c where c.data_source_id = r.data_source_id
                        ) o on true
                        where r.id = any(cast(:ids as bigint[]))
                        order by r.id
                        """)
                .param("ids", resourceIds.toArray(Long[]::new))
                .query(
                        (rs, row) ->
                                new ResourceState(
                                        rs.getLong("id"),
                                        rs.getLong("data_source_id"),
                                        "CDC".equals(rs.getString("sync_strategy")),
                                        rs.getBoolean("loaded"),
                                        rs.getBoolean("full_pending"),
                                        instant(rs.getObject("last_read_at", OffsetDateTime.class)),
                                        rs.getString("recovery_reason"),
                                        instant(
                                                rs.getObject(
                                                        "healthy_since", OffsetDateTime.class))))
                .list();
    }

    /**
     * 원천 테이블들의 열린 수집 실패. 키 실패(객체를 만들지 못한 행)는 property가 null이고, 속성 값 실패는 {@code 유형코드.속성코드}다.
     * objectTypeId는 그 실패를 낸 객체 매핑의 유형이다.
     */
    public List<OpenLoadFailure> openLoadFailures(Collection<Long> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        select distinct m.object_type_id, f.property_definition_id is null as key_failure,
                               ot.code || '.' || pd.code as property
                        from source_load_failure f
                        join object_source_mapping m on m.id = f.object_source_mapping_id
                        left join property_definition pd on pd.id = f.property_definition_id
                        left join object_type ot on ot.id = pd.object_type_id
                        where f.status = 'OPEN' and f.source_resource_id = any(cast(:ids as bigint[]))
                        """)
                .param("ids", resourceIds.toArray(Long[]::new))
                .query(
                        (rs, row) ->
                                new OpenLoadFailure(
                                        rs.getLong("object_type_id"),
                                        rs.getBoolean("key_failure"),
                                        rs.getString("property")))
                .list();
    }

    /** 주어진 객체 가운데 원천에서 지운(DELETED_AT_SOURCE) 것. */
    public Set<Long> deletedObjectIds(Collection<Long> objectIds) {
        if (objectIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                jdbc.sql(
                                "select id from object_instance"
                                        + " where id = any(cast(:ids as bigint[]))"
                                        + " and status = 'DELETED_AT_SOURCE'")
                        .param("ids", objectIds.toArray(Long[]::new))
                        .query(Long.class)
                        .list());
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * @param loaded CDC 테이블이 전체 읽기를 마쳤는지(loaded_lsn). FULL_COMPARE면 의미 없다
     * @param fullPending 전체 읽기(FULL)가 대기·실행 중인지
     * @param lastReadAt 마지막으로 성공한 수집이 끝난 시각. 없으면 null
     * @param recoveryReason 원천의 CDC 복구가 필요한 이유. 없으면 null
     * @param healthySince 원천의 CDC 수집이 정상이 된 시각. 비정상이거나 소비 상태가 없으면 null
     */
    public record ResourceState(
            long resourceId,
            long dataSourceId,
            boolean cdc,
            boolean loaded,
            boolean fullPending,
            Instant lastReadAt,
            String recoveryReason,
            Instant healthySince) {}

    public record OpenLoadFailure(long objectTypeId, boolean keyFailure, String property) {}
}
