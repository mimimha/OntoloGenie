package com.axcore.platform.batch.domain;

import com.axcore.platform.audit.domain.EventKind;

/**
 * 배치가 가져가 실행하는 작업 표. api가 QUEUED 행을 넣고 배치가 RUNNING → SUCCEEDED/FAILED로 바꾼다. 모든 표가 status,
 * requested_at, started_at, finished_at, error_message 칸을 같은 뜻으로 가진다.
 */
public enum QueuedJobType {
    SYNC_RUN("sync_run", EventKind.SYNC_RUN, true),
    CHECK_RUN("check_run", EventKind.CHECK_RUN, false),
    SOURCE_CHECK("source_connection_check", EventKind.SOURCE_CHECK, false),
    SOURCE_PREVIEW("source_preview", EventKind.SOURCE_PREVIEW, false);

    private final String table;
    private final EventKind eventKind;
    private final boolean heartbeat;

    QueuedJobType(String table, EventKind eventKind, boolean heartbeat) {
        this.table = table;
        this.eventKind = eventKind;
        this.heartbeat = heartbeat;
    }

    /** 작업 표 이름. 감사 기록의 target_type으로도 쓴다. */
    public String table() {
        return table;
    }

    public EventKind eventKind() {
        return eventKind;
    }

    /**
     * 표에 heartbeat_at 칸이 있는지. 있으면 실행 틀이 실행 중에 갱신하고, 멈춘 작업 정리는 마지막 heartbeat(없으면 시작 시각)로 판단한다. 없으면
     * 시작 시각으로 판단한다(S15P31C106-218).
     */
    public boolean hasHeartbeat() {
        return heartbeat;
    }
}
