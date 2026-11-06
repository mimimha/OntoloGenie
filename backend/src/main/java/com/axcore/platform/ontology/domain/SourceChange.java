package com.axcore.platform.ontology.domain;

/**
 * CDC 이벤트 하나의 위치(Jira 198이 만든다).
 *
 * @param transactionId 원천 트랜잭션 번호(xid8). Debezium txId(32비트)에 epoch를 복원한 값이다
 * @param lsn 이벤트의 LSN. 같은 이벤트가 다시 오면(재전송) 객체의 source_lsn 이하라 반영하지 않는다
 * @param syncRunId 이 이벤트를 반영하는 수집 실행(trigger_kind=CDC)
 */
public record SourceChange(long transactionId, long lsn, long syncRunId) {

    public SourceChange {
        if (transactionId < 3) {
            throw new IllegalArgumentException("원천 트랜잭션 번호는 3 이상입니다.");
        }
        if (lsn < 0) {
            throw new IllegalArgumentException("LSN은 0 이상입니다.");
        }
        if (syncRunId < 1) {
            throw new IllegalArgumentException("수집 실행 ID는 1 이상입니다.");
        }
    }
}
