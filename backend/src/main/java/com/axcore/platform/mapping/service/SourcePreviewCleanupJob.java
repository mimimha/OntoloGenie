package com.axcore.platform.mapping.service;

import com.axcore.platform.source.repository.SourcePreviewRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결과를 지울 시각(expires_at, 요청 1시간 뒤)이 지난 원천 표본(016)·매핑 미리보기(040) 작업을 배치에서 지운다. 결과에 원천 값이 들어 있기 때문이다.
 * 지운 뒤 102는 404를 돌려주고 화면은 다시 요청한다. 한 문장 DELETE라 여러 인스턴스가 돌아도 괜찮다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class SourcePreviewCleanupJob {

    private final SourcePreviewRepository previewRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${axcore.source-preview.cleanup-delay}")
    public void run() {
        try {
            int deleted = previewRepository.deleteExpired(Instant.now(clock));
            if (deleted > 0) {
                log.info("만료된 원천 미리보기를 지웠습니다 count={}", deleted);
            }
        } catch (RuntimeException e) {
            // DB 연결이 끊긴 경우 등. 다음 주기에 다시 시도한다.
            log.error("만료된 원천 미리보기 정리 실패", e);
        }
    }
}
