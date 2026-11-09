package com.axcore.platform.batch.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 멈춘 작업 정리 기준(S15P31C106-218). sync_run은 실행 틀이 heartbeat마다 heartbeat_at을 올리고, syncRun 동안 갱신이 없으면 멈춘
 * 것으로 본다. heartbeat 칸이 없는 표는 시작 시각부터 잰다. 시간 초과 기준(U3)은 결정 대기라 sync_run 외에는
 * 임시값이다(application-batch.yml 주석).
 */
@ConfigurationProperties("axcore.batch.timeout")
public record BatchTimeoutProperties(
        Duration heartbeat,
        Duration syncRun,
        Duration sourceCheck,
        Duration sourcePreview,
        Duration checkRun) {

    public BatchTimeoutProperties {
        heartbeat = heartbeat == null ? Duration.ofSeconds(30) : heartbeat;
        syncRun = syncRun == null ? Duration.ofMinutes(5) : syncRun;
        sourceCheck = sourceCheck == null ? Duration.ofMinutes(10) : sourceCheck;
        sourcePreview = sourcePreview == null ? Duration.ofMinutes(10) : sourcePreview;
        checkRun = checkRun == null ? Duration.ofMinutes(30) : checkRun;
        if (heartbeat.isNegative() || heartbeat.isZero()) {
            throw new IllegalArgumentException("heartbeat 간격은 0보다 커야 합니다.");
        }
        for (Duration timeout : new Duration[] {syncRun, sourceCheck, sourcePreview, checkRun}) {
            if (timeout.compareTo(Duration.ofMinutes(1)) < 0) {
                throw new IllegalArgumentException("작업 시간 초과 기준은 1분 이상이어야 합니다.");
            }
        }
        if (heartbeat.multipliedBy(2).compareTo(syncRun) > 0) {
            throw new IllegalArgumentException("sync_run 시간 초과 기준은 heartbeat 간격의 두 배 이상이어야 합니다.");
        }
    }

    public Duration forType(QueuedJobType type) {
        return switch (type) {
            case SYNC_RUN -> syncRun;
            case SOURCE_CHECK -> sourceCheck;
            case SOURCE_PREVIEW -> sourcePreview;
            case CHECK_RUN -> checkRun;
        };
    }
}
