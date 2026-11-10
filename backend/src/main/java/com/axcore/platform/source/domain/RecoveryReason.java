package com.axcore.platform.source.domain;

/**
 * 원천 하나의 CDC 반영을 이어 갈 수 없어 그 원천의 CDC 테이블을 전체 다시 읽어야 하는 이유(V12 {@code cdc_offset.recovery_reason}).
 * 값은 V12 {@code ck_cdc_offset_recovery_reason}과 같아야 한다. 하나라도 빠지면 소비자가 그 행을 읽을 때 실패해 같은 트랜잭션을 끝없이 다시
 * 시도한다. 소비자는 표시만 하고, 다시 읽기 실행은 배치 감시 작업이 {@code trigger_kind=RECOVERY}로 넣는다(결정 문서 11장 1·2, 11.1).
 * 표시는 다시 읽기를 저장하는 트랜잭션이 푼다(199, {@code CdcConsumerRepository#releaseRecoveryIfReloaded}).
 */
public enum RecoveryReason {
    /** 복제 슬롯이 유실·재생성되어 그 사이 변경을 받을 수 없다. */
    SLOT_LOST,
    /** 저장된 소비 위치가 토픽 보관 기간을 넘었다. */
    OFFSET_EXPIRED,
    /** 원천 트랜잭션 번호(txId)의 epoch를 확신할 수 없다(198-3). */
    EPOCH_UNCERTAIN,
    /** 원천 트랜잭션 하나의 변경이 상한을 넘었다(198-2). */
    TRANSACTION_TOO_LARGE,
    /** 메시지 순서·모양이 어긋났다(198-2). */
    PROTOCOL_ERROR,
    /**
     * 원천 접속 정보(host·port·database)가 바뀌었다(API 012). 커넥터를 새 DB에 맞출 때까지 반영하지 않는다. 이때는 {@code
     * loaded_lsn}을 비우지 않는다. 맞춘 뒤 199가 그 원천의 CDC 테이블을 다시 읽기 대상으로 표시한다(결정 문서 11.1 3).
     */
    SOURCE_CHANGED
}
