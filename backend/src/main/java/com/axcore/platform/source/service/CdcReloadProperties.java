package com.axcore.platform.source.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CDC 테이블 자동 다시 읽기 감시 작업의 재시도 기준(S15P31C106-330). 전체 읽기가 실패한 테이블은 retryDelay 동안 다시 넣지 않고, 마지막 성공 이후
 * maxFailures번 연속 실패하면 자동으로는 더 넣지 않는다(결정 문서 11.1).
 */
@ConfigurationProperties("axcore.source-load.reload-watch")
public record CdcReloadProperties(Duration retryDelay, Integer maxFailures) {

    public CdcReloadProperties {
        retryDelay = retryDelay == null ? Duration.ofMinutes(5) : retryDelay;
        maxFailures = maxFailures == null ? 3 : maxFailures;
        if (retryDelay.isNegative() || maxFailures < 1) {
            throw new IllegalArgumentException("다시 읽기 재시도 기준은 0 이상, 실패 횟수는 1 이상이어야 합니다.");
        }
    }
}
