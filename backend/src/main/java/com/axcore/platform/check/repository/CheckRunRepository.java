package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.CheckDataBasis.ResourceCheckpoint;
import com.axcore.platform.check.dto.CheckRunResponse;
import com.axcore.platform.check.dto.CheckRunResponse.DataBasis;
import com.axcore.platform.check.dto.CheckRunResponse.Resource;
import com.axcore.platform.check.dto.CheckRunSearchRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 실행(check_run) 조회와 수동 검사·재검증 접수(059·060·061), 실행기(191-2)의 실행 행 읽기·잠금·완료.
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"): {@code check_run}은 Entity를 두지 않는 작업
 * 테이블이다. 접수는 부분 유일 인덱스 {@code uq_check_run_active}를 쓰는 조건부 insert({@code ON CONFLICT DO NOTHING})로
 * 같은 검사를 하나만 남긴다. 데이터 기준(원천 자원별 마지막 성공 수집 실행)은 다른 도메인 표({@code source_resource}·{@code
 * sync_run_resource})를 한 번의 SQL로 join하는 읽기 전용 조회다.
 */
@Repository
@RequiredArgsConstructor
public class CheckRunRepository {

    private static final String SELECT_RUN =
            """
            select id, trigger_kind, status, scope::text as scope, compared_to_run_id,
                   requested_at, requested_by, started_at, finished_at, error_message,
                   sync_run_id, data_basis::text as data_basis
            from check_run
            """;

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Page<CheckRunResponse> search(CheckRunSearchRequest request, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where true");
        Map<String, Object> parameters = new HashMap<>();
        if (request.triggerKind() != null) {
            where.append(" and trigger_kind = :triggerKind");
            parameters.put("triggerKind", request.triggerKind());
        }
        if (request.status() != null) {
            where.append(" and status = :status");
            parameters.put("status", request.status());
        }
        if (request.from() != null) {
            where.append(" and requested_at >= :from");
            parameters.put("from", Timestamp.from(request.from()));
        }
        if (request.to() != null) {
            where.append(" and requested_at <= :to");
            parameters.put("to", Timestamp.from(request.to()));
        }

        long total =
                jdbc.sql("select count(*) from check_run" + where)
                        .params(parameters)
                        .query(Long.class)
                        .single();
        // 최근에 접수된 검사부터 보여준다. 같은 시각이면 나중에 만든 검사가 먼저다.
        List<CheckRunResponse> content =
                jdbc.sql(
                                SELECT_RUN
                                        + where
                                        + " order by requested_at desc, id desc"
                                        + " limit :size offset :offset")
                        .params(parameters)
                        .param("size", pageable.getPageSize())
                        .param("offset", pageable.getOffset())
                        .query((rs, rowNum) -> toResponse(rs))
                        .list();
        return new PageImpl<>(content, pageable, total);
    }

    public Optional<CheckRunResponse> findById(long id) {
        return jdbc.sql(SELECT_RUN + " where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> toResponse(rs))
                .optional();
    }

    /** 비교 대상으로 쓸 이전 검사의 종류와 상태. */
    public Optional<RunState> findState(long id) {
        return jdbc.sql("select trigger_kind, status from check_run where id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new RunState(rs.getString(1), rs.getString(2)))
                .optional();
    }

    /** 원천 자원마다 마지막으로 성공한 수집 실행. 성공한 수집이 없으면 null이다. 후보 검사(CANDIDATE)와 같은 기준이다. */
    public List<ResourceCheckpoint> loadCheckpoints(Collection<Long> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        select r.id as source_resource_id, (
                            select s.sync_run_id from sync_run_resource s
                            where s.source_resource_id = r.id and s.status = 'SUCCEEDED'
                            order by s.finished_at desc, s.sync_run_id desc limit 1
                        ) as last_sync
                        from source_resource r
                        where r.id in (:ids)
                        order by r.id
                        """)
                .param("ids", resourceIds)
                .query(
                        (rs, rowNum) ->
                                new ResourceCheckpoint(
                                        rs.getLong("source_resource_id"),
                                        rs.getObject("last_sync", Long.class)))
                .list();
    }

    /** 같은 기준으로 성공한 가장 최근 검사. */
    public Optional<Long> findSucceeded(String workKey) {
        return jdbc.sql(
                        """
                        select id from check_run
                        where work_key = :key and status = 'SUCCEEDED'
                        order by finished_at desc, id desc limit 1
                        """)
                .param("key", workKey)
                .query(Long.class)
                .optional();
    }

    /** 같은 기준으로 대기·실행 중인 검사. uq_check_run_active 때문에 많아야 하나다. */
    public Optional<Long> findActive(String workKey) {
        return jdbc.sql(
                        """
                        select id from check_run
                        where work_key = :key and status in ('QUEUED', 'RUNNING')
                        """)
                .param("key", workKey)
                .query(Long.class)
                .optional();
    }

    /** 대기 중 검사를 넣는다. 같은 기준의 대기·실행 중 검사가 이미 있으면 넣지 않고 빈 값을 돌려준다. */
    public Optional<Long> insertQueued(NewRun run) {
        return jdbc.sql(
                        """
                        insert into check_run (
                            trigger_kind, status, scope, work_key, data_basis,
                            compared_to_run_id, requested_by, requested_at
                        )
                        values (
                            :triggerKind, 'QUEUED', cast(:scope as jsonb), :workKey,
                            cast(:basis as jsonb), :comparedTo, :requestedBy, :now
                        )
                        on conflict (work_key) where status in ('QUEUED', 'RUNNING') do nothing
                        returning id
                        """)
                .param("triggerKind", run.triggerKind())
                .param("scope", run.scopeJson())
                .param("workKey", run.workKey())
                .param("basis", run.basisJson())
                .param("comparedTo", run.comparedToRunId(), Types.BIGINT)
                .param("requestedBy", run.requestedBy())
                .param("now", Timestamp.from(run.requestedAt()))
                .query(Long.class)
                .optional();
    }

    /** 실행기(191-2)가 돌릴 검사: 종류, 접수 때 정한 규칙 개정본 목록, 데이터 기준. */
    public Optional<RunToExecute> findToExecute(long id) {
        return jdbc.sql(
                        "select trigger_kind, scope::text as scope, data_basis::text as data_basis"
                                + " from check_run where id = :id")
                .param("id", id)
                .query(
                        (rs, rowNum) ->
                                new RunToExecute(
                                        id,
                                        rs.getString("trigger_kind"),
                                        linkMappingIds(rs.getString("scope")),
                                        basis(rs.getString("data_basis"))))
                .optional();
    }

    /** 검사 행을 잠그고 아직 RUNNING인지 본다. 결과를 저장하는 동안 실패 처리와 겹치지 않게 한다. */
    public boolean lockIfRunning(long id) {
        return jdbc.sql("select status = 'RUNNING' from check_run where id = :id for update")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public void markSucceeded(long id, Instant finishedAt) {
        jdbc.sql(
                        """
                        update check_run set status = 'SUCCEEDED', finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("id", id)
                .update();
    }

    private List<Long> linkMappingIds(String scope) {
        Object ids = json.readValue(scope, OBJECT).get("linkMappingIds");
        List<Long> converted = new ArrayList<>();
        if (ids instanceof List<?> list) {
            for (Object item : list) {
                converted.add(((Number) item).longValue());
            }
        }
        return List.copyOf(converted);
    }

    /** 저장된 데이터 기준. 기준이 비어 있는 실행({@code {}})은 자원이 없는 기준이다. */
    private CheckDataBasis basis(String stored) {
        Object resources = json.readValue(stored, OBJECT).get("resources");
        List<ResourceCheckpoint> checkpoints = new ArrayList<>();
        if (resources instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> resource) {
                    Object syncRun = resource.get("lastSuccessfulSyncRunId");
                    checkpoints.add(
                            new ResourceCheckpoint(
                                    ((Number) resource.get("sourceResourceId")).longValue(),
                                    syncRun == null ? null : ((Number) syncRun).longValue()));
                }
            }
        }
        return new CheckDataBasis(checkpoints);
    }

    private CheckRunResponse toResponse(ResultSet rs) throws SQLException {
        return new CheckRunResponse(
                rs.getLong("id"),
                rs.getString("trigger_kind"),
                rs.getString("status"),
                json.readValue(rs.getString("scope"), OBJECT),
                rs.getObject("compared_to_run_id", Long.class),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getObject("requested_by", Long.class),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("error_message"),
                rs.getObject("sync_run_id", Long.class),
                dataBasis(rs.getString("data_basis")));
    }

    /**
     * 저장된 데이터 기준을 응답 모양으로 바꾼다. 저장 칸 이름은 {@code CheckDataBasis}의 lastSuccessfulSyncRunId이고, 응답은
     * 명세(V5 반영본)의 lastSyncRunId다. 기준이 비어 있는 실행({@code {}})은 빈 목록이다.
     */
    private DataBasis dataBasis(String stored) {
        Object resources = json.readValue(stored, OBJECT).get("resources");
        List<Resource> converted = new ArrayList<>();
        if (resources instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> resource) {
                    Object syncRun = resource.get("lastSuccessfulSyncRunId");
                    if (syncRun == null) {
                        syncRun = resource.get("lastSyncRunId");
                    }
                    converted.add(
                            new Resource(
                                    ((Number) resource.get("sourceResourceId")).longValue(),
                                    syncRun == null ? null : ((Number) syncRun).longValue()));
                }
            }
        }
        return new DataBasis(converted);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record RunState(String triggerKind, String status) {}

    public record RunToExecute(
            long id, String triggerKind, List<Long> linkMappingIds, CheckDataBasis dataBasis) {}

    public record NewRun(
            String triggerKind,
            String scopeJson,
            String workKey,
            String basisJson,
            Long comparedToRunId,
            long requestedBy,
            Instant requestedAt) {}
}
