package com.axcore.platform.source.cdc;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 원천 토픽({@code {data_source.code}.all}) 메시지 한 건(Jira 198-2). 원천마다 토픽 하나·파티션 1개에 변경, 트랜잭션 경계,
 * heartbeat가 원천 커밋 순서대로 온다(316, {@code infra/cdc/README.md}).
 */
public sealed interface CdcMessage
        permits CdcMessage.ChangeEvent, CdcMessage.TransactionBoundary, CdcMessage.Heartbeat {

    /** 행 변경. 원천 트랜잭션 경계 안에서만 온다. */
    enum Operation {
        CREATE,
        UPDATE,
        DELETE,
        /** 표 전체 비우기. 행별 삭제가 오지 않는다. */
        TRUNCATE
    }

    /**
     * @param txId 원천 트랜잭션 번호(32비트, 최상위 번호). epoch 복원은 198-3
     * @param lsn 변경 위치. 재전송 거르기에 쓴다
     * @param commitTimeMs 원천 커밋 시각({@code source.ts_ms}, 원천 서버 시계)
     * @param processedTimeMs Debezium이 처리한 시각({@code ts_ms}, Connect 서버 시계). 처리 지연 = processed −
     *     commit
     * @param before 변경 전 행. REPLICA IDENTITY DEFAULT라 삭제는 PK만, 수정은 보통 비어 있다(없으면 null)
     * @param after 변경 후 행(삭제·TRUNCATE면 null)
     * @param schema 메시지 스키마(행 구조 해석에 쓴다, DebeziumRowDecoder.rowFields). 스키마 없는 메시지면 null
     */
    record ChangeEvent(
            Operation op,
            String sourceSchema,
            String sourceTable,
            long txId,
            long lsn,
            long commitTimeMs,
            long processedTimeMs,
            JsonNode before,
            JsonNode after,
            JsonNode schema)
            implements CdcMessage {

        /** 원천 테이블 이름. {@code source_resource.resource_key}와 같은 {스키마}.{이름}. */
        public String resourceKey() {
            return sourceSchema + "." + sourceTable;
        }
    }

    enum BoundaryStatus {
        BEGIN,
        END
    }

    /**
     * @param txId {@code id}({@code txId:LSN})의 트랜잭션 번호 부분. BEGIN과 END의 LSN 부분은 서로 다르다
     * @param eventCount END의 변경 수. BEGIN이면 null
     * @param eventCountsByTable END의 표별 변경 수({@code 스키마.표} → 수). BEGIN이면 빈 값
     */
    record TransactionBoundary(
            BoundaryStatus status,
            long txId,
            String id,
            Integer eventCount,
            Map<String, Integer> eventCountsByTable)
            implements CdcMessage {}

    /**
     * Debezium heartbeat. 판정 근거가 아니라 커넥터가 살아 있다는 신호다(결정 문서 8장). 트랜잭션 중간에 낄 수 있다.
     *
     * @param timeMs Connect 서버 시각
     */
    record Heartbeat(long timeMs) implements CdcMessage {}
}
