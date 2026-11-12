package com.axcore.platform.source.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 컨테이너에서 주기가 된 원천의 FULL_COMPARE 테이블 수집을 넣는다(S15P31C106-318). 여러 배치 인스턴스가 돌아도 원천 행 잠금과 대기·실행 중 유일
 * 조건(uq_sync_run_resource_active)으로 한 번만 들어간다. {@code
 * axcore.source-load.schedule-watch.enabled=false}면 띄우지 않는다.
 */
@Slf4j
@Component
@Profile("batch")
@ConditionalOnProperty(
        name = "axcore.source-load.schedule-watch.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ScheduledSyncJob {

    private final ScheduledSyncService scheduledSyncService;

    @Scheduled(fixedDelayString = "${axcore.source-load.schedule-watch.delay}")
    public void run() {
        try {
            List<Long> queued = scheduledSyncService.queueDueSyncs();
            if (!queued.isEmpty()) {
                log.info("FULL_COMPARE 테이블 주기 수집을 넣었습니다 syncRunIds={}", queued);
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("FULL_COMPARE 테이블 주기 수집 확인 실패", e);
        }
    }
}
