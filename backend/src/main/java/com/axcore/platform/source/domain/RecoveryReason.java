package com.axcore.platform.source.domain;

/**
 * 원천 하나의 CDC 반영을 이어 갈 수 없어 그 원천의 CDC 테이블을 전체 다시 읽어야 하는 이유(V12 {@code cdc_offset.recovery_reason}).
 * 소비자는 표시만 하고, 다시 읽기 실행은 배치 감시 작업이 {@code trigger_kind=RECOVERY}로 넣는다(결정 문서 11장 1·2). 표시를 푸는 일은
 * 199.
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
    PROTOCOL_ERROR
}
