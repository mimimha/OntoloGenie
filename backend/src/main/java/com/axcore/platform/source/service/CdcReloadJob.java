package com.axcore.platform.source.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 컨테이너에서 다시 읽기 표시가 붙은 CDC 테이블을 주기적으로 찾아 전체 읽기를 넣는다(S15P31C106-330). 여러 배치 인스턴스가 돌아도 같은 테이블은
 * 대기·실행 중 유일 조건(uq_sync_run_resource_active)으로 한 번만 들어간다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class CdcReloadJob {

    private final CdcReloadService cdcReloadService;

    @Scheduled(fixedDelayString = "${axcore.source-load.reload-watch.delay}")
    public void run() {
        try {
            List<Long> queued = cdcReloadService.queueDueReloads();
            if (!queued.isEmpty()) {
                log.info("CDC 테이블 다시 읽기를 넣었습니다 syncRunIds={}", queued);
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("CDC 테이블 다시 읽기 감시 실패", e);
        }
    }
}
