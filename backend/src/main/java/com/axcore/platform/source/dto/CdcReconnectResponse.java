package com.axcore.platform.source.dto;

/**
 * 원천 교체 뒤 커넥터를 새 DB에 맞췄다는 알림의 결과(199).
 *
 * @param dataSourceId 원천
 * @param recoveryReason 바뀐 복구 이유. 늘 {@code SLOT_LOST}(새 DB에 맞춘 커넥터는 새 복제 슬롯을 쓴다)
 * @param reloadTables 다시 읽기 대상으로 표시한 CDC 테이블 수. 켜진 매핑이 있는 것만 배치 감시 작업이 다시 읽는다
 */
public record CdcReconnectResponse(long dataSourceId, String recoveryReason, int reloadTables) {}
