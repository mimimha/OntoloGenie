package com.axcore.platform.check.service;

import com.axcore.platform.check.service.SyncCheckRequester.Result;
import com.axcore.platform.check.service.SyncCheckRequester.SyncRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배치 컨테이너에서 수집 뒤 검사(SYNC)를 주기적으로 넣는다(S15P31C106-191-4, 247: 검사는 batch {@code @Scheduled}). 넣기만 하고
 * 실행은 작업 표 처리기({@link CheckRunJob})가 한다. {@code axcore.check.sync.enabled=false}면 띄우지 않는다.
 */
@Slf4j
@Component
@Profile("batch")
@ConditionalOnProperty(
        name = "axcore.check.sync.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class SyncCheckJob {

    private final SyncCheckRequester requester;

    @Scheduled(fixedDelayString = "${axcore.check.sync.interval}")
    public void run() {
        try {
            Optional<SyncRequest> request = requester.request();
            if (request.isPresent() && request.get().result() == Result.QUEUED) {
                log.info("SYNC 검사를 넣었습니다 checkRunId={}", request.get().checkRunId());
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("SYNC 검사 요청 실패", e);
        }
    }
}
