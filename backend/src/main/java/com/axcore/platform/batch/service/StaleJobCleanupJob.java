package com.axcore.platform.batch.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 컨테이너에서 멈춘 작업을 작업 표마다 주기적으로 정리한다(S15P31C106-218). 한 문장 UPDATE라 여러 인스턴스가 돌아도 같은 행을 두 번 바꾸지 않는다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class StaleJobCleanupJob {

    private final StaleJobCleaner staleJobCleaner;

    @Scheduled(fixedDelayString = "${axcore.batch.timeout-check-delay}")
    public void run() {
        for (QueuedJobType type : QueuedJobType.values()) {
            try {
                List<Long> failed = staleJobCleaner.failStale(type);
                if (!failed.isEmpty()) {
                    log.warn("멈춘 작업을 시간 초과로 FAILED 처리했습니다 type={} ids={}", type, failed);
                }
            } catch (RuntimeException e) {
                // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
                log.error("멈춘 작업 정리 실패 type={}", type, e);
            }
        }
    }
}
