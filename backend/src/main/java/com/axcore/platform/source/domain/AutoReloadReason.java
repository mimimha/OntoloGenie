package com.axcore.platform.source.domain;

/**
 * 감시 작업이 CDC 테이블의 전체 다시 읽기를 넣은 이유(S15P31C106-330). sync_run에는 이유 칸이 없어 감사 기록 접수 줄에만 남긴다. 둘 이상 해당하면
 * 선언 순서대로 앞의 것 하나만 쓴다(결정 문서 11.1).
 */
public enum AutoReloadReason {
    /** 그 원천의 cdc_offset에 recovery_reason이 있다. */
    RECOVERY,
    /** 이 테이블의 전체 읽기가 끝난 적이 없다(full_load_done_gen IS NULL). */
    FIRST_LOAD,
    /** 그 밖의 다시 읽기 표시. 매핑 변경·TRUNCATE·TOAST를 구분하지 않는다. */
    RELOAD_REQUESTED
}
