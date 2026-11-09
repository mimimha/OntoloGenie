package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.RecoveryReason;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CDC 소비자(198-5)의 원천별 상태({@code cdc_offset})와 CDC 수집 기록({@code sync_run}·{@code
 * sync_run_resource}).
 *
 * <p>JdbcClient를 쓰는 이유(.ai/rules/database.md "조회·저장 방식"). 표마다 경우가 달라 메서드 주석에도 적는다.
 *
 * <ul>
 *   <li>{@code sync_run}·{@code sync_run_resource}: Entity를 두지 않는 작업 테이블이다({@link
 *       SyncRunRepository}와 같다).
 *   <li>{@code cdc_offset}: 조건부 insert와 잠금이다. 원천마다 한 행을 처음 반영할 때 {@code INSERT ... ON CONFLICT DO
 *       NOTHING}으로 만들고 곧바로 {@code SELECT ... FOR UPDATE}로 잡아야, 처음 반영이 동시에 와도 키 충돌 없이 한 소비자만 지나간다.
 *       아직 Entity가 없는 표라 기존 방식(JdbcClient)을 따르고, {@code xid8} 칸은 JPA 기본 형식이 없다.
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class CdcConsumerRepository {

    /** 원천 토픽은 파티션 1개다(316, 결정 문서 9.6). */
    static final int PARTITION = 0;

    private final JdbcClient jdbc;

    /**
     * 원천의 소비 상태 행을 잠근다. 없으면 만든다(다음 위치 0). 같은 원천을 두 소비자가 동시에 반영하지 못한다. JdbcClient: 조건부 insert({@code
     * ON CONFLICT DO NOTHING})와 잠금.
     *
     * @throws IllegalStateException 그 토픽이 다른 원천의 것으로 저장되어 있다
     */
    public OffsetState lockOffset(long dataSourceId, String topic) {
        jdbc.sql(
                        """
                        insert into cdc_offset (topic, partition_no, data_source_id, next_offset)
                        values (:topic, :partition, :source, 0)
                        on conflict (topic, partition_no) do nothing
                        """)
                .param("topic", topic)
                .param("partition", PARTITION)
                .param("source", dataSourceId)
                .update();
        OffsetState state =
                jdbc.sql(
                                """
                                select data_source_id, next_offset, last_txid::text as last_txid,
                                       recovery_reason
                                from cdc_offset where topic = :topic and partition_no = :partition
                                for update
                                """)
                        .param("topic", topic)
                        .param("partition", PARTITION)
                        .query(
                                (rs, row) -> {
                                    String txid = rs.getString("last_txid");
                                    String reason = rs.getString("recovery_reason");
                                    return new OffsetState(
                                            rs.getLong("data_source_id"),
                                            rs.getLong("next_offset"),
                                            txid == null ? null : Long.parseLong(txid),
                                            reason == null ? null : RecoveryReason.valueOf(reason));
                                })
                        .single();
        if (state.dataSourceId() != dataSourceId) {
            throw new IllegalStateException(
                    "토픽 %s는 다른 원천(%d)의 소비 위치입니다.".formatted(topic, state.dataSourceId()));
        }
        return state;
    }

    /**
     * 원천 트랜잭션 하나를 반영한 뒤의 상태. 복구가 필요한 원천에는 부르지 않는다(healthy_since와 함께 쓰는 CHECK). JdbcClient: Entity가
     * 없는 {@code cdc_offset}(기존 방식)과 {@code xid8} 칸.
     *
     * @param lastCommitAt 그 트랜잭션의 원천 커밋 시각. 모르면 null(기존 값 유지)
     * @param processingLagMs 처리 지연. 모르면 null(기존 값 유지)
     */
    public void saveApplied(
            String topic,
            long nextOffset,
            long lastTxid,
            Instant lastCommitAt,
            Instant lastMessageAt,
            Integer processingLagMs) {
        jdbc.sql(
                        """
                        update cdc_offset
                        set next_offset = :next,
                            last_txid = cast(cast(:txid as text) as xid8),
                            last_commit_at = coalesce(cast(:commitAt as timestamptz), last_commit_at),
                            last_message_at = cast(:messageAt as timestamptz),
                            processing_lag_ms = coalesce(cast(:lag as integer), processing_lag_ms),
                            healthy_since = coalesce(healthy_since, now()),
                            updated_at = now()
                        where topic = :topic and partition_no = :partition
                        """)
                .param("next", nextOffset)
                .param("txid", lastTxid)
                .param("commitAt", lastCommitAt == null ? null : Timestamp.from(lastCommitAt))
                .param("messageAt", Timestamp.from(lastMessageAt))
                .param("lag", processingLagMs)
                .param("topic", topic)
                .param("partition", PARTITION)
                .update();
    }

    /**
     * 복구가 필요하다고 표시한다. 이미 표시되어 있으면 처음 이유와 시각을 둔다. 위치는 옮기지 않는다. JdbcClient: Entity가 없는 {@code
     * cdc_offset}(기존 방식).
     */
    public void markRecovery(String topic, RecoveryReason reason) {
        jdbc.sql(
                        """
                        update cdc_offset
                        set recovery_reason = coalesce(recovery_reason, :reason),
                            recovery_at = coalesce(recovery_at, now()),
                            healthy_since = null,
                            updated_at = now()
                        where topic = :topic and partition_no = :partition
                        """)
                .param("reason", reason.name())
                .param("topic", topic)
                .param("partition", PARTITION)
                .update();
    }

    /**
     * 원천 트랜잭션 하나의 CDC 수집 실행. 반영과 같은 트랜잭션에서 SUCCEEDED로만 넣는다(결정 문서 11장 4·5). JdbcClient: Entity를 두지
     * 않는 작업 테이블.
     */
    public long insertCdcRun(long dataSourceId) {
        return jdbc.sql(
                        """
                        insert into sync_run (data_source_id, trigger_kind, status, started_at, finished_at)
                        values (:source, 'CDC', 'SUCCEEDED', now(), now())
                        returning id
                        """)
                .param("source", dataSourceId)
                .query(Long.class)
                .single();
    }

    /** 그 실행에서 반영한 테이블 하나(INCREMENTAL). JdbcClient: Entity를 두지 않는 작업 테이블. */
    public void insertRunResource(long syncRunId, long dataSourceId, ResourceCounts counts) {
        jdbc.sql(
                        """
                        insert into sync_run_resource
                            (sync_run_id, data_source_id, source_resource_id, mode, status,
                             from_lsn, to_lsn, rows_read, rows_upserted, rows_deleted, started_at, finished_at)
                        values (:run, :source, :resource, 'INCREMENTAL', 'SUCCEEDED',
                                :fromLsn, :toLsn, :read, :upserted, :deleted, now(), now())
                        """)
                .param("run", syncRunId)
                .param("source", dataSourceId)
                .param("resource", counts.resourceId())
                .param("fromLsn", counts.fromLsn())
                .param("toLsn", counts.toLsn())
                .param("read", counts.events())
                .param("upserted", counts.upserted())
                .param("deleted", counts.deleted())
                .update();
    }

    /**
     * @param lastTxid 마지막으로 반영한 원천 트랜잭션(xid8). 처음이면 null
     * @param recoveryReason 복구가 필요하면 그 이유
     */
    public record OffsetState(
            long dataSourceId, long nextOffset, Long lastTxid, RecoveryReason recoveryReason) {}

    /**
     * @param events 이 테이블의 변경 수(rows_read)
     * @param upserted 쓴 객체 수(매핑마다)
     * @param deleted 삭제 표시한 객체 수
     */
    public record ResourceCounts(
            long resourceId, long fromLsn, long toLsn, int events, int upserted, int deleted) {}
}
