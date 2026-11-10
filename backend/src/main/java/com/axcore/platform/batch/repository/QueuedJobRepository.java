package com.axcore.platform.batch.repository;

import com.axcore.platform.batch.domain.ClaimedJob;
import com.axcore.platform.batch.domain.QueuedJobType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 작업 표의 상태 칸을 다룬다. 표 이름은 {@link QueuedJobType}에 정한 값만 쓴다. */
@Repository
@RequiredArgsConstructor
public class QueuedJobRepository {

    private final JdbcClient jdbcClient;

    /** 가장 먼저 들어온 QUEUED 행 하나를 RUNNING으로 바꿔 가져간다. 다른 배치 인스턴스가 잡고 있는 행은 기다리지 않고 건너뛴다(SKIP LOCKED). */
    public Optional<ClaimedJob> claimNext(QueuedJobType type, Instant startedAt) {
        return claimNext(type, "true", startedAt);
    }

    /**
     * {@link #claimNext(QueuedJobType, Instant)}와 같되 condition에 맞는 행만 가져간다. condition은 처리기가 코드에 적은
     * 상수 식이다({@code QueuedJobHandler.claimCondition}).
     */
    public Optional<ClaimedJob> claimNext(QueuedJobType type, String condition, Instant startedAt) {
        return jdbcClient
                .sql(
                        """
                        update %1$s set status = 'RUNNING', started_at = :startedAt
                        where id = (select id from %1$s where status = 'QUEUED' and (%2$s)
                                    order by requested_at, id
                                    limit 1 for update skip locked)
                        returning id, requested_at
                        """
                                .formatted(type.table(), condition))
                .param("startedAt", toTimestamp(startedAt))
                .query(
                        (rs, rowNum) ->
                                new ClaimedJob(
                                        type,
                                        rs.getLong("id"),
                                        rs.getObject("requested_at", OffsetDateTime.class)
                                                .toInstant(),
                                        startedAt))
                .optional();
    }

    public boolean isSucceeded(QueuedJobType type, long id) {
        return jdbcClient
                .sql("select status = 'SUCCEEDED' from %s where id = :id".formatted(type.table()))
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** RUNNING인 행만 FAILED로 바꾼다. 바꿨으면 true. */
    public boolean markFailed(
            QueuedJobType type, long id, String errorMessage, Instant finishedAt) {
        return jdbcClient
                        .sql(
                                """
                                update %s set status = 'FAILED', finished_at = :finishedAt,
                                              error_message = :errorMessage
                                where id = :id and status = 'RUNNING'
                                """
                                        .formatted(type.table()))
                        .param("finishedAt", toTimestamp(finishedAt))
                        .param("errorMessage", errorMessage)
                        .param("id", id)
                        .update()
                == 1;
    }

    /** RUNNING인 행의 heartbeat_at을 올린다. heartbeat 칸이 있는 표만 부른다. 이미 끝났거나 정리된 행이면 false. */
    public boolean touchHeartbeat(QueuedJobType type, long id, Instant now) {
        return jdbcClient
                        .sql(
                                "update %s set heartbeat_at = :now where id = :id and status = 'RUNNING'"
                                        .formatted(type.table()))
                        .param("now", toTimestamp(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /**
     * 기준 시각보다 오래 응답이 없는 RUNNING 행을 FAILED로 바꾸고 바꾼 행을 돌려준다. heartbeat 칸이 있으면 마지막 heartbeat(없으면 시작
     * 시각), 없으면 시작 시각으로 판단한다. 같은 행을 잠근 트랜잭션(결과 저장 중인 complete)이 있으면 끝나기를 기다린 뒤 다시 RUNNING인지 본다.
     */
    public List<ClaimedJob> failStale(
            QueuedJobType type, Instant cutoff, String errorMessage, Instant now) {
        String lastSign = type.hasHeartbeat() ? "coalesce(heartbeat_at, started_at)" : "started_at";
        return jdbcClient
                .sql(
                        """
                        update %s set status = 'FAILED', finished_at = :now, error_message = :errorMessage
                        where status = 'RUNNING' and %s < :cutoff
                        returning id, requested_at, started_at
                        """
                                .formatted(type.table(), lastSign))
                .param("now", toTimestamp(now))
                .param("errorMessage", errorMessage)
                .param("cutoff", toTimestamp(cutoff))
                .query(
                        (rs, rowNum) ->
                                new ClaimedJob(
                                        type,
                                        rs.getLong("id"),
                                        rs.getObject("requested_at", OffsetDateTime.class)
                                                .toInstant(),
                                        rs.getObject("started_at", OffsetDateTime.class)
                                                .toInstant()))
                .list();
    }

    private static OffsetDateTime toTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
